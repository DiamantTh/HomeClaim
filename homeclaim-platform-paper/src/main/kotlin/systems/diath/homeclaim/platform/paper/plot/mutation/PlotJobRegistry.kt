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
        val startedAt: Long
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
