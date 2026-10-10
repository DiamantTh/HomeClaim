package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask
import org.bukkit.plugin.java.JavaPlugin
import systems.diath.homeclaim.core.mutation.MutationJobInfo
import systems.diath.homeclaim.core.mutation.MutationReason
import systems.diath.homeclaim.core.mutation.WorldMutationBackend
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.platform.paper.plot.PlotWorldChunkGenerator
import systems.diath.homeclaim.platform.paper.plot.PlotWorldConfigStore
import systems.diath.homeclaim.platform.paper.plot.sanitized
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Paper-only first cut for heavier plot resets.
 *
 * The job runs in small sync batches and restores the interior of a plot region
 * using the configured plot generator defaults, while clearing the build volume above.
 */
class PaperPlotResetService(
    private val plugin: JavaPlugin,
    private val configStore: PlotWorldConfigStore = PlotWorldConfigStore(plugin),
    private val mutationBackend: WorldMutationBackend = PaperSynchronousWorldMutationBackend()
) : PlotResetService {
    private val queueRegistry = PlotJobRegistry()
    private val activeResets = ConcurrentHashMap<String, () -> Unit>()

    override fun shutdown() {
        activeResets.values.toList().forEach { cancel ->
            runCatching(cancel).onFailure { error ->
                plugin.logger.log(java.util.logging.Level.SEVERE, "Could not finish a reset during shutdown", error)
            }
        }
    }

    override fun cancelPendingJobs(worldName: String?): Int {
        return queueRegistry.requestCancelAll(world = worldName, kind = PlotJobRegistry.JobKind.RESET)
    }

    override fun activeJobDiagnostics(worldName: String?): List<String> {
        return queueRegistry.snapshot(world = worldName, kind = PlotJobRegistry.JobKind.RESET).map { snapshot ->
            "reset:${snapshot.world}:${snapshot.key}:reason=${snapshot.reason?.name ?: MutationReason.RESET.name}:age=${snapshot.ageMillis}ms:cancelled=${snapshot.cancelRequested}"
        }
    }

    override fun activeJobs(worldName: String?): List<PlotJobSnapshot> {
        return queueRegistry.snapshot(world = worldName, kind = PlotJobRegistry.JobKind.RESET).map { snapshot ->
            PlotJobSnapshot(
                key = snapshot.key,
                world = snapshot.world,
                kind = PlotJobRegistry.JobKind.RESET.name,
                reason = snapshot.reason,
                ageMillis = snapshot.ageMillis,
                cancelRequested = snapshot.cancelRequested
            )
        }
    }

    override fun activeJobInfo(worldName: String?): List<MutationJobInfo> {
        return queueRegistry.mutationJobInfo(world = worldName, kind = PlotJobRegistry.JobKind.RESET, defaultReason = MutationReason.RESET)
    }

    override fun queueReset(region: Region, reason: PlotResetReason): Boolean {
        return queueReset(region, reason) { }
    }

    override fun queueReset(
        region: Region,
        reason: PlotResetReason,
        onComplete: (PlotResetCompletion) -> Unit
    ): Boolean {
        val world = Bukkit.getWorld(region.world) ?: return false
        val config = configStore.loadConfig(region.world)?.sanitized() ?: return false
        if (reason == PlotResetReason.DELETE && !config.resetOnDelete) return false
        if (reason == PlotResetReason.UNCLAIM && !config.resetOnUnclaim) return false

        val jobKey = "paper-reset:${region.world}:${region.id.value}"
        val jobHandle = queueRegistry.tryAcquire(
            key = jobKey,
            world = region.world,
            kind = PlotJobRegistry.JobKind.RESET,
            reason = MutationReason.RESET,
            maxConcurrentPerWorld = config.maxConcurrentResetJobsPerWorld,
            timeoutMillis = config.jobTimeoutMillis
        ) ?: run {
            val reasonText = if (queueRegistry.isActive(jobKey, config.jobTimeoutMillis)) "duplicate" else "world-limit"
            plugin.logger.fine("Skipping $reasonText Paper plot reset for ${region.id.value}")
            return false
        }

        val columns = ArrayDeque(PlotResetPlanner.interiorColumns(region.bounds))
        if (columns.isEmpty()) {
            jobHandle.close()
            return false
        }
        val totalColumns = columns.size
        var completedColumns = 0
        var mutationAttempted = false
        val completionSent = AtomicBoolean(false)
        var scheduledTask: BukkitTask? = null
        fun finish(status: PlotResetCompletionStatus, error: String? = null) {
            if (!completionSent.compareAndSet(false, true)) return
            scheduledTask?.cancel()
            jobHandle.close()
            activeResets.remove(jobKey)
            val result = PlotResetCompletion(
                status = status,
                completedColumns = completedColumns,
                partialMutation = mutationAttempted && (status != PlotResetCompletionStatus.COMPLETED || completedColumns < totalColumns),
                error = error
            )
            runCatching { onComplete(result) }.onFailure { callbackError ->
                plugin.logger.log(java.util.logging.Level.SEVERE, "Reset completion callback failed for ${region.id.value}", callbackError)
            }
        }

        val generator = PlotWorldChunkGenerator(config)
        val minY = config.minGenHeight ?: region.bounds.minY
        val topY = (config.plotHeight - 1).coerceAtLeast(minY)
        val clearUntil = region.bounds.maxY
        val columnsPerTick = config.resetBatchColumnsPerTick.coerceAtLeast(1)

        return runCatching {
            scheduledTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
                if (jobHandle.isCancellationRequested(config.jobTimeoutMillis)) {
                    finish(PlotResetCompletionStatus.CANCELLED, "Reset cancelled after $completedColumns of $totalColumns columns")
                    return@Runnable
                }
                runCatching {
                    var processed = 0
                    while (processed < columnsPerTick && columns.isNotEmpty()) {
                        val batchColumns = ArrayList<Pair<Int, Int>>(columnsPerTick)
                        while (processed < columnsPerTick && columns.isNotEmpty()) {
                            batchColumns += columns.removeFirst()
                            processed++
                        }
                        val batch = PlotMutationPlanFactory.resetBatch(
                            id = jobKey,
                            world = world.name,
                            columns = batchColumns,
                            generator = generator,
                            minY = minY,
                            topY = topY,
                            clearUntil = clearUntil
                        )
                        mutationAttempted = true
                        mutationBackend.submit(batch)
                        completedColumns += batchColumns.size
                    }
                    if (columns.isEmpty()) {
                        finish(PlotResetCompletionStatus.COMPLETED)
                    }
                }.onFailure { error ->
                    plugin.logger.warning("Paper plot reset failed for ${region.id.value}: ${error.message}")
                    finish(
                        PlotResetCompletionStatus.FAILED,
                        "${error.message ?: error::class.simpleName ?: "mutation failed"}; $completedColumns of $totalColumns columns completed"
                    )
                }
            }, 1L, 1L)
            activeResets[jobKey] = {
                finish(PlotResetCompletionStatus.CANCELLED, "Reset cancelled by plugin shutdown after $completedColumns of $totalColumns columns")
            }

            plugin.logger.info("Queued plot reset for ${region.id.value} (${reason.name.lowercase()})")
            true
        }.getOrElse { error ->
            jobHandle.close()
            plugin.logger.warning("Failed to schedule Paper plot reset for ${region.id.value}: ${error.message}")
            false
        }
    }
}
