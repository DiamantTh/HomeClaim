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
import java.util.concurrent.TimeUnit

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
    private val queueRegistry = PlotJobRegistry()

    override fun cancelPendingJobs(worldName: String?): Int {
        val logicalCancelled = queueRegistry.requestCancelAll(world = worldName, kind = PlotJobRegistry.JobKind.RESET)
        val backendCancelled = mutationBackend.activeJobs(worldName)
            .filter { it.reason == MutationReason.RESET }
            .count { mutationBackend.cancel(it.ticketId) }
        return maxOf(logicalCancelled, backendCancelled)
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

        val jobKey = "folia-reset:${region.world}:${region.id.value}"
        val jobHandle = queueRegistry.tryAcquire(
            key = jobKey,
            world = region.world,
            kind = PlotJobRegistry.JobKind.RESET,
            reason = MutationReason.RESET,
            maxConcurrentPerWorld = config.maxConcurrentResetJobsPerWorld,
            timeoutMillis = config.jobTimeoutMillis
        ) ?: run {
            val reasonText = if (queueRegistry.isActive(jobKey, config.jobTimeoutMillis)) "duplicate" else "world-limit"
            plugin.logger.fine("Skipping $reasonText Folia plot reset for ${region.id.value}")
            return false
        }

        val columns = PlotResetPlanner.interiorColumns(region.bounds)
        val chunkBatches = PlotChunkPlanner.batchColumnsByChunk(columns, config.resetBatchColumnsPerTick)
        if (chunkBatches.isEmpty()) {
            jobHandle.close()
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
                queueRegistry.markFailed(region.world, PlotJobRegistry.JobKind.RESET)
                plugin.logger.warning("Failed to queue Folia reset batch ${batch.id}: ${failure.message}")
            }
        }

        if (submitted > 0) {
            monitorLogicalJob(jobKey, region.world, jobHandle, config.jobTimeoutMillis)
            plugin.logger.info("Queued Folia plot reset for ${region.id.value} (${reason.name.lowercase()})")
            return true
        }

        queueRegistry.markFailed(region.world, PlotJobRegistry.JobKind.RESET)
        jobHandle.close()
        return false
    }

    private fun monitorLogicalJob(jobKey: String, worldName: String, jobHandle: PlotJobRegistry.JobHandle, timeoutMillis: Long) {
        Bukkit.getAsyncScheduler().runDelayed(plugin, { _ ->
            if (jobHandle.isCancellationRequested(timeoutMillis)) {
                mutationBackend.activeJobs(worldName)
                    .filter { it.reason == MutationReason.RESET && it.ticketId.startsWith(jobKey) }
                    .forEach { mutationBackend.cancel(it.ticketId) }
            }

            val stillActive = mutationBackend.activeJobs(worldName)
                .any { it.reason == MutationReason.RESET && it.ticketId.startsWith(jobKey) }

            if (stillActive) {
                monitorLogicalJob(jobKey, worldName, jobHandle, timeoutMillis)
            } else {
                jobHandle.close()
            }
        }, LOGICAL_JOB_POLL_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private companion object {
        const val LOGICAL_JOB_POLL_DELAY_MS = 50L
    }
}
