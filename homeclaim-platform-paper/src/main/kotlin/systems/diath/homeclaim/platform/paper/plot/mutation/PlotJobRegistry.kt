package systems.diath.homeclaim.platform.paper.plot.mutation

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small concurrency guard for plot mutation/reset jobs.
 *
 * It prevents duplicate in-flight work for the same logical job key while keeping
 * the implementation platform-neutral and easy to test.
 */
internal class PlotJobRegistry {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun tryAcquire(key: String): JobHandle? {
        return if (inFlight.add(key)) JobHandle(key) else null
    }

    fun isActive(key: String): Boolean = inFlight.contains(key)

    fun runIfIdle(key: String, action: Runnable): Boolean {
        val handle = tryAcquire(key) ?: return false
        try {
            action.run()
            return true
        } finally {
            handle.close()
        }
    }

    inner class JobHandle internal constructor(
        private val key: String
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                inFlight.remove(key)
            }
        }
    }
}
