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

        val generator = PlotWorldChunkGenerator(config)
        val minY = config.minGenHeight ?: region.bounds.minY
        val topY = (config.plotHeight - 1).coerceAtLeast(minY)
        val clearUntil = region.bounds.maxY
        val columnsPerTick = config.resetBatchColumnsPerTick.coerceAtLeast(1)

        var scheduledTask: BukkitTask? = null
        return runCatching {
            scheduledTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
                if (jobHandle.isCancellationRequested(config.jobTimeoutMillis)) {
                    scheduledTask?.cancel()
                    jobHandle.close()
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
                        mutationBackend.submit(batch)
                    }
                    if (columns.isEmpty()) {
                        scheduledTask?.cancel()
                        jobHandle.close()
                    }
                }.onFailure { error ->
                    scheduledTask?.cancel()
                    jobHandle.close()
                    plugin.logger.warning("Paper plot reset failed for ${region.id.value}: ${error.message}")
                }
            }, 1L, 1L)

            plugin.logger.info("Queued plot reset for ${region.id.value} (${reason.name.lowercase()})")
            true
        }.getOrElse { error ->
            jobHandle.close()
            plugin.logger.warning("Failed to schedule Paper plot reset for ${region.id.value}: ${error.message}")
            false
        }
    }
}
