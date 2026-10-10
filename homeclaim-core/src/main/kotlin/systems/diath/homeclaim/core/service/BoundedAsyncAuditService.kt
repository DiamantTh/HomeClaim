package systems.diath.homeclaim.core.service

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps JDBC persistence off event threads using a bounded queue. Rejections and sink
 * failures are reported through [onFailure]; neither condition is silently swallowed.
 */
class BoundedAsyncAuditService(
    private val sink: AuditSink,
    capacity: Int = 4096,
    private val drainTimeoutMillis: Long = 2_000,
    private val onFailure: (String, Throwable?) -> Unit
) : AuditService, AutoCloseable {
    init {
        require(drainTimeoutMillis > 0) { "Audit drain timeout must be positive" }
    }

    private val queue = ArrayBlockingQueue<AuditEntry>(capacity.also { require(it > 0) })
    private val accepting = AtomicBoolean(true)
    private val failures = AtomicLong()
    private val rejected = AtomicLong()
    private val lifecycleLock = Any()
    private val worker = Thread(::drain, "HomeClaim-Audit-Writer").apply {
        isDaemon = true
        start()
    }

    val pendingCount: Int get() = queue.size
    val failureCount: Long get() = failures.get()
    val rejectedCount: Long get() = rejected.get()

    override fun append(entry: AuditEntry) {
        synchronized(lifecycleLock) {
            check(accepting.get()) { "Audit writer is closing" }
            if (!queue.offer(entry)) {
                rejected.incrementAndGet()
                report("Audit queue is full; rejected audit ${entry.auditId}", null)
            }
        }
    }

    private fun drain() {
        while (accepting.get() || queue.isNotEmpty()) {
            val entry = try {
                queue.poll(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                continue
            } ?: continue
            try {
                sink.append(entry)
            } catch (failure: Throwable) {
                report("Could not persist audit ${entry.auditId}", failure)
            }
        }
    }

    private fun report(message: String, failure: Throwable?) {
        failures.incrementAndGet()
        onFailure(message, failure)
    }

    /** Waits for queued audit data before the database pool is shut down. */
    override fun close() {
        synchronized(lifecycleLock) {
            accepting.set(false)
        }
        worker.interrupt()
        try {
            worker.join(drainTimeoutMillis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            report("Interrupted while draining audit queue (${queue.size} entries remain)", interrupted)
        }
        if (worker.isAlive) {
            report("Timed out draining audit queue (${queue.size} entries remain)", null)
        }
    }
}
