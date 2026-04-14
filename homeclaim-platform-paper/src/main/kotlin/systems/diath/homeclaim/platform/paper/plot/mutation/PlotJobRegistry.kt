package systems.diath.homeclaim.platform.paper.plot.mutation

import systems.diath.homeclaim.core.mutation.MutationJobInfo
import systems.diath.homeclaim.core.mutation.MutationReason
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.type.TypeReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concurrency guard for plot mutation/reset jobs.
 *
 * It prevents duplicate in-flight work for the same logical job key, applies
 * light per-world backpressure, and can clean up stale entries if a task never
 * reaches its normal completion path.
 * 
 * Also tracks job statistics: queued (in-flight), and failures per world.
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
        val reason: MutationReason?,
        val startedAt: Long,
        var cancelRequested: Boolean = false
    )

    data class JobSnapshot(
        val key: String,
        val world: String,
        val kind: JobKind,
        val reason: MutationReason?,
        val ageMillis: Long,
        val cancelRequested: Boolean
    )

    data class WorldJobStats(
        val world: String,
        val kind: JobKind,
        val queued: Int = 0,
        val failed: Int = 0
    )

    private data class PersistedJobs(
        val capturedAtMillis: Long,
        val jobs: List<JobSnapshot>
    )

    private val lock = Any()
    private val inFlight = ConcurrentHashMap<String, JobRecord>()
    // Track failures per world+kind key
    private val failuresCounts = ConcurrentHashMap<String, AtomicInteger>()

    fun tryAcquire(
        key: String,
        world: String = "global",
        kind: JobKind = JobKind.MUTATION,
        reason: MutationReason? = null,
        maxConcurrentPerWorld: Int = Int.MAX_VALUE,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
    ): JobHandle? {
        synchronized(lock) {
            cleanupExpiredLocked(timeoutMillis)
            if (inFlight.containsKey(key)) return null

            val activeForWorld = inFlight.values.count { it.world == world && it.kind == kind }
            if (activeForWorld >= maxConcurrentPerWorld.coerceAtLeast(1)) return null

            inFlight[key] = JobRecord(world = world, kind = kind, reason = reason, startedAt = nowProvider())
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
                        reason = record.reason,
                        ageMillis = (now - record.startedAt).coerceAtLeast(0L),
                        cancelRequested = record.cancelRequested
                    )
                }
                .sortedWith(compareBy<JobSnapshot>({ it.world }, { it.kind.name }, { it.key }))
        }
    }

    fun getStats(world: String? = null, kind: JobKind? = null): List<WorldJobStats> {
        synchronized(lock) {
            cleanupExpiredLocked(DEFAULT_TIMEOUT_MILLIS)
            val stats = mutableMapOf<String, WorldJobStats>()
            
            // Count queued jobs
            inFlight.values.forEach { record ->
                if ((world == null || record.world == world) && (kind == null || record.kind == kind)) {
                    val key = "${record.world}:${record.kind.name}"
                    val current = stats[key] ?: WorldJobStats(record.world, record.kind)
                    stats[key] = current.copy(queued = current.queued + 1)
                }
            }
            
            // Add failure counts
            failuresCounts.forEach { (key, count) ->
                val (w, k) = key.split(":").let { it[0] to JobKind.valueOf(it[1]) }
                if ((world == null || w == world) && (kind == null || k == kind)) {
                    val current = stats[key] ?: WorldJobStats(w, k)
                    stats[key] = current.copy(failed = count.get())
                }
            }
            
            return stats.values.toList()
        }
    }

    fun mutationJobInfo(
        world: String? = null,
        kind: JobKind? = null,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        defaultReason: MutationReason = MutationReason.ADMIN
    ): List<MutationJobInfo> {
        return snapshot(world = world, kind = kind, timeoutMillis = timeoutMillis).map { snapshot ->
            MutationJobInfo(
                ticketId = snapshot.key,
                world = snapshot.world,
                reason = snapshot.reason ?: defaultReason,
                queuedMillis = snapshot.ageMillis,
                running = true,
                cancelRequested = snapshot.cancelRequested
            )
        }
    }

    fun markFailed(world: String, kind: JobKind) {
        val key = "$world:${kind.name}"
        failuresCounts.computeIfAbsent(key) { AtomicInteger(0) }.incrementAndGet()
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

    /**
     * Serialize all active jobs to JSON format for persistence.
     * Useful for saving state before shutdown.
     */
    fun toPersistedFormat(): String {
        val payload = PersistedJobs(
            capturedAtMillis = nowProvider(),
            jobs = snapshot()
        )
        return try {
            JSON_MAPPER.writeValueAsString(payload)
        } catch (e: Exception) {
            "[]"
        }
    }

    /**
     * Restore jobs from previously persisted JSON format.
     * Returns the number of jobs successfully restored.
     * Skips any jobs that already exist in-flight.
     */
    fun fromPersistedFormat(json: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Int {
        val payload = json.trim()
        if (payload.isBlank() || payload == "{}" || payload == "[]") {
            return 0
        }

        return try {
            val persisted = if (payload.startsWith("[")) {
                PersistedJobs(
                    capturedAtMillis = nowProvider(),
                    jobs = JSON_MAPPER.readValue(payload, object: TypeReference<List<JobSnapshot>>() {})
                )
            } else {
                JSON_MAPPER.readValue(payload, PersistedJobs::class.java)
            }

            synchronized(lock) {
                cleanupExpiredLocked(timeoutMillis)
                var loaded = 0
                val now = nowProvider()
                persisted.jobs.forEach { snap ->
                    if (!inFlight.containsKey(snap.key)) {
                        val estimatedStart = persisted.capturedAtMillis - snap.ageMillis
                        val expired = timeoutMillis > 0L && (now - estimatedStart) >= timeoutMillis
                        if (!expired) {
                            inFlight[snap.key] = JobRecord(
                                world = snap.world,
                                kind = snap.kind,
                                reason = snap.reason,
                                startedAt = estimatedStart,
                                cancelRequested = snap.cancelRequested
                            )
                            loaded++
                        }
                    }
                }
                loaded
            }
        } catch (e: Exception) {
            0
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L
        val JSON_MAPPER: ObjectMapper = ObjectMapper().findAndRegisterModules()
    }
}
