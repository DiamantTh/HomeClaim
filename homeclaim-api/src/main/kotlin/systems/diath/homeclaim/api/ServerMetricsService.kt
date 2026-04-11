package systems.diath.homeclaim.api

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant

/**
 * Server-wide metrics collection for HomeClaim.
 * Aggregates plot job metrics, world information, and server health.
 */
interface ServerMetricsService {
    fun collectMetrics(): ServerMetrics
    fun collectPlotsMetrics(): PlotsMetrics
    fun collectWorldMetrics(worldName: String): WorldMetrics?
}

/**
 * Complete server metrics snapshot.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ServerMetrics(
    val timestamp: Long = Instant.now().epochSecond,
    val version: VersionInfo = VersionInfo(),
    val uptime: Long = 0L,
    val load: LoadInfo = LoadInfo(),
    val onlinePlayers: Int = 0,
    val maxPlayers: Int = 0,
    val tps: Float = 20.0f,
    val mspt: Long = 0L,
    val totalWorlds: Int = 0,
    val worlds: List<WorldMetrics> = emptyList(),
    val memory: MemoryMetrics = MemoryMetrics(),
    val plots: PlotsMetrics = PlotsMetrics()
)

/**
 * Version and build information.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class VersionInfo(
    val homeclaimVersion: String = "unknown",
    val javaVersion: String = "unknown",
    val serverImplementation: String = "unknown",  // Paper, Folia, Bukkit, etc.
    val serverVersion: String = "unknown"
)

/**
 * System load average metrics.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class LoadInfo(
    val oneMinuteAverage: Double = 0.0,
    val fiveMinuteAverage: Double = 0.0,
    val fifteenMinuteAverage: Double = 0.0,
    val availableProcessors: Int = 0
)

/**
 * Plot-specific metrics aggregated across all worlds.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PlotsMetrics(
    val totalActive: Int = 0,
    val totalQueued: Int = 0,
    val totalFailed: Int = 0,
    val byWorld: Map<String, WorldPlotsMetrics> = emptyMap(),
    val avgProcessingMs: Long = 0L,
    val oldestPendingSeconds: Long = 0L
)

/**
 * Plot metrics per world.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WorldPlotsMetrics(
    val worldName: String,
    val activeMutations: Int = 0,
    val queuedMutations: Int = 0,
    val failedMutations: Int = 0,
    val activeResets: Int = 0,
    val queuedResets: Int = 0,
    val failedResets: Int = 0,
    val totalPlots: Int = 0
)

/**
 * Per-world server metrics.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WorldMetrics(
    val name: String,
    val type: String = "NORMAL",
    val loaded: Boolean = true,
    val environment: String = "NORMAL",
    val chunkCount: Int = 0,
    val entityCount: Int = 0,
    val plots: WorldPlotsMetrics = WorldPlotsMetrics("unknown")
)

/**
 * Memory usage metrics.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MemoryMetrics(
    val usedMB: Long = 0L,
    val maxMB: Long = 0L,
    val allocatedMB: Long = 0L,
    val percentUsed: Float = 0f
)

/**
 * No-op implementation for when metrics are not available.
 */
object NoOpServerMetricsService : ServerMetricsService {
    override fun collectMetrics(): ServerMetrics = ServerMetrics()
    override fun collectPlotsMetrics(): PlotsMetrics = PlotsMetrics()
    override fun collectWorldMetrics(worldName: String): WorldMetrics? = null
}
