package systems.diath.homeclaim.platform.paper.plot.mutation

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Concurrency guard for plot mutation/reset jobs.
 *
 * It prevents duplicate in-flight work for the same logical job key, applies
 * light per-world backpressure, and can clean up stale entries if a task never
 * reaches its normal completion path.
 */
internal class PlotJobRegistry(
    private val nowProvider: () -> Long = System::currentTimeMillis
) {
    enum class JobKind {
        MUTATION,
        RESET
    }

    private data class JobRecord(
        val world: String,
        val kind: JobKind,
        val startedAt: Long,
        var cancelRequested: Boolean = false
    )

    data class JobSnapshot(
        val key: String,
        val world: String,
        val kind: JobKind,
        val ageMillis: Long,
        val cancelRequested: Boolean
    )

    private val lock = Any()
    private val inFlight = ConcurrentHashMap<String, JobRecord>()

    fun tryAcquire(
        key: String,
        world: String = "global",
        kind: JobKind = JobKind.MUTATION,
        maxConcurrentPerWorld: Int = Int.MAX_VALUE,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
    ): JobHandle? {
        synchronized(lock) {
            cleanupExpiredLocked(timeoutMillis)
            if (inFlight.containsKey(key)) return null

            val activeForWorld = inFlight.values.count { it.world == world && it.kind == kind }
            if (activeForWorld >= maxConcurrentPerWorld.coerceAtLeast(1)) return null

            inFlight[key] = JobRecord(world = world, kind = kind, startedAt = nowProvider())
            return JobHandle(key)
        }
    }

    fun isActive(key: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Boolean {
        synchronized(lock) {
            cleanupExpiredLocked(timeoutMillis)
            return inFlight.containsKey(key)
        }
    }

    fun activeJobs(
        world: String? = null,
        kind: JobKind? = null,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
    ): Int {
        synchronized(lock) {
            cleanupExpiredLocked(timeoutMillis)
            return inFlight.values.count { record ->
                (world == null || record.world == world) && (kind == null || record.kind == kind)
            }
        }
    }

    fun isCancellationRequested(key: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Boolean {
        synchronized(lock) {
            cleanupExpiredLocked(timeoutMillis)
            return inFlight[key]?.cancelRequested == true
        }
    }

    fun requestCancel(key: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Boolean {
        synchronized(lock) {
            cleanupExpiredLocked(timeoutMillis)
            val record = inFlight[key] ?: return false
            record.cancelRequested = true
            return true
        }
    }

    fun requestCancelAll(
        world: String? = null,
        kind: JobKind? = null,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
    ): Int {
        synchronized(lock) {
            cleanupExpiredLocked(timeoutMillis)
            var count = 0
            inFlight.values.forEach { record ->
                if ((world == null || record.world == world) && (kind == null || record.kind == kind)) {
                    if (!record.cancelRequested) {
                        record.cancelRequested = true
                        count++
                    }
                }
            }
            return count
        }
    }

    fun snapshot(
        world: String? = null,
        kind: JobKind? = null,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
    ): List<JobSnapshot> {
        synchronized(lock) {
            cleanupExpiredLocked(timeoutMillis)
            val now = nowProvider()
            return inFlight.entries
                .filter { (_, record) ->
                    (world == null || record.world == world) && (kind == null || record.kind == kind)
                }
                .map { (key, record) ->
                    JobSnapshot(
                        key = key,
                        world = record.world,
                        kind = record.kind,
                        ageMillis = (now - record.startedAt).coerceAtLeast(0L),
                        cancelRequested = record.cancelRequested
                    )
                }
                .sortedWith(compareBy<JobSnapshot>({ it.world }, { it.kind.name }, { it.key }))
        }
    }

    fun runIfIdle(key: String, action: Runnable): Boolean {
        val handle = tryAcquire(key) ?: return false
        try {
            action.run()
            return true
        } finally {
            handle.close()
        }
    }

    private fun cleanupExpiredLocked(timeoutMillis: Long) {
        if (timeoutMillis <= 0L) return
        val now = nowProvider()
        val iterator = inFlight.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if ((now - entry.value.startedAt) >= timeoutMillis) {
                iterator.remove()
            }
        }
    }

    inner class JobHandle internal constructor(
        private val key: String
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        fun requestCancel(): Boolean = this@PlotJobRegistry.requestCancel(key)

        fun isCancellationRequested(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Boolean {
            return this@PlotJobRegistry.isCancellationRequested(key, timeoutMillis)
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                synchronized(lock) {
                    inFlight.remove(key)
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L
    }
}
