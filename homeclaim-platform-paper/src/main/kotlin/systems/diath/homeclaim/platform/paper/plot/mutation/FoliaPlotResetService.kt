package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import systems.diath.homeclaim.core.mutation.MutationJobInfo
import systems.diath.homeclaim.core.mutation.MutationReason
import systems.diath.homeclaim.core.mutation.WorldMutationBackend
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.platform.paper.plot.PlotWorldChunkGenerator
import systems.diath.homeclaim.platform.paper.plot.PlotWorldConfigStore
import systems.diath.homeclaim.platform.paper.plot.sanitized

/**
 * Folia-safe reset path.
 *
 * Work is split by chunk and each batch is executed through the RegionScheduler,
 * while a lightweight async delay is only used to enqueue the next region-local batch.
 */
class FoliaPlotResetService(
    private val plugin: JavaPlugin,
    private val configStore: PlotWorldConfigStore = PlotWorldConfigStore(plugin),
    private val mutationBackend: WorldMutationBackend = FoliaRegionScheduledWorldMutationBackend(plugin)
) : PlotResetService {
    override fun cancelPendingJobs(worldName: String?): Int {
        val jobs = activeJobInfo(worldName)
        return jobs.count { mutationBackend.cancel(it.ticketId) }
    }

    override fun activeJobDiagnostics(worldName: String?): List<String> {
        return activeJobInfo(worldName).map { info ->
            "reset:${info.world}:${info.ticketId}:reason=${info.reason.name}:age=${info.queuedMillis}ms:cancelled=${info.cancelRequested}"
        }
    }

    override fun activeJobs(worldName: String?): List<PlotJobSnapshot> {
        return activeJobInfo(worldName).map { info ->
            PlotJobSnapshot(
                key = info.ticketId,
                world = info.world,
                kind = PlotJobRegistry.JobKind.RESET.name,
                reason = info.reason,
                ageMillis = info.queuedMillis,
                cancelRequested = info.cancelRequested
            )
        }
    }

    override fun activeJobInfo(worldName: String?): List<MutationJobInfo> {
        return mutationBackend.activeJobs(worldName).filter { it.reason == MutationReason.RESET }
    }

    override fun queueReset(region: Region, reason: PlotResetReason): Boolean {
        val world = Bukkit.getWorld(region.world) ?: return false
        val config = configStore.loadConfig(region.world)?.sanitized() ?: return false
        if (reason == PlotResetReason.DELETE && !config.resetOnDelete) return false
        if (reason == PlotResetReason.UNCLAIM && !config.resetOnUnclaim) return false

        val jobKey = "folia-reset:${region.world}:${region.id.value}"
        val activeResets = activeJobInfo(region.world)
        val isDuplicate = activeResets.any { it.ticketId.startsWith(jobKey) }
        if (isDuplicate) {
            plugin.logger.fine("Skipping duplicate Folia plot reset for ${region.id.value}")
            return false
        }
        if (activeResets.size >= config.maxConcurrentResetJobsPerWorld) {
            plugin.logger.fine("Skipping world-limit Folia plot reset for ${region.id.value}")
            return false
        }

        val columns = PlotResetPlanner.interiorColumns(region.bounds)
        val chunkBatches = PlotChunkPlanner.batchColumnsByChunk(columns, config.resetBatchColumnsPerTick)
        if (chunkBatches.isEmpty()) {
            return false
        }

        val generator = PlotWorldChunkGenerator(config)
        val minY = config.minGenHeight ?: region.bounds.minY
        val topY = (config.plotHeight - 1).coerceAtLeast(minY)
        val clearUntil = region.bounds.maxY

        var submitted = 0
        chunkBatches.forEach { chunkBatch ->
            val batch = PlotMutationPlanFactory.resetBatch(
                id = "$jobKey:chunk:${chunkBatch.chunk.x}:${chunkBatch.chunk.z}",
                world = world.name,
                columns = chunkBatch.columns,
                generator = generator,
                minY = minY,
                topY = topY,
                clearUntil = clearUntil
            )
            runCatching {
                mutationBackend.submit(batch)
                submitted++
            }.onFailure { failure ->
                plugin.logger.warning("Failed to queue Folia reset batch ${batch.id}: ${failure.message}")
            }
        }

        if (submitted > 0) {
            plugin.logger.info("Queued Folia plot reset for ${region.id.value} (${reason.name.lowercase()})")
            return true
        }

        return false
    }
}
