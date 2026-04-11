package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.plugin.java.JavaPlugin
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.platform.paper.plot.PlotWorldChunkGenerator
import systems.diath.homeclaim.platform.paper.plot.PlotWorldConfigStore
import systems.diath.homeclaim.platform.paper.plot.sanitized
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Folia-safe reset path.
 *
 * Work is split by chunk and each batch is executed through the RegionScheduler,
 * while a lightweight async delay is only used to enqueue the next region-local batch.
 */
class FoliaPlotResetService(
    private val plugin: JavaPlugin,
    private val configStore: PlotWorldConfigStore = PlotWorldConfigStore(plugin)
) : PlotResetService {
    private val jobRegistry = PlotJobRegistry()

    override fun cancelPendingJobs(worldName: String?): Int {
        return jobRegistry.requestCancelAll(world = worldName, kind = PlotJobRegistry.JobKind.RESET)
    }

    override fun activeJobDiagnostics(worldName: String?): List<String> {
        return jobRegistry.snapshot(world = worldName, kind = PlotJobRegistry.JobKind.RESET).map { snapshot ->
            "reset:${snapshot.world}:${snapshot.key}:age=${snapshot.ageMillis}ms:cancelled=${snapshot.cancelRequested}"
        }
    }

    override fun queueReset(region: Region, reason: PlotResetReason): Boolean {
        val world = Bukkit.getWorld(region.world) ?: return false
        val config = configStore.loadConfig(region.world)?.sanitized() ?: return false
        if (reason == PlotResetReason.DELETE && !config.resetOnDelete) return false
        if (reason == PlotResetReason.UNCLAIM && !config.resetOnUnclaim) return false

        val jobKey = "folia-reset:${region.world}:${region.id.value}"
        val jobHandle = jobRegistry.tryAcquire(
            key = jobKey,
            world = region.world,
            kind = PlotJobRegistry.JobKind.RESET,
            maxConcurrentPerWorld = config.maxConcurrentResetJobsPerWorld,
            timeoutMillis = config.jobTimeoutMillis
        ) ?: run {
            val reasonText = if (jobRegistry.isActive(jobKey, config.jobTimeoutMillis)) "duplicate" else "world-limit"
            plugin.logger.fine("Skipping $reasonText Folia plot reset for ${region.id.value}")
            return false
        }

        val chunkBatches = PlotChunkPlanner.batchColumnsByChunk(
            PlotResetPlanner.interiorColumns(region.bounds),
            config.resetBatchColumnsPerTick
        )
        if (chunkBatches.isEmpty()) {
            jobHandle.close()
            return false
        }

        val generator = PlotWorldChunkGenerator(config)
        val minY = config.minGenHeight ?: region.bounds.minY
        val topY = (config.plotHeight - 1).coerceAtLeast(minY)
        val clearUntil = region.bounds.maxY
        val remainingChunkQueues = AtomicInteger(chunkBatches.groupBy { it.chunk }.size)

        chunkBatches.groupBy { it.chunk }.forEach { (chunk, batches) ->
            val queue = ArrayDeque<List<Pair<Int, Int>>>(batches.map { it.columns })
            val anchor = Location(world, (chunk.x shl 4).toDouble(), world.minHeight.toDouble(), (chunk.z shl 4).toDouble())
            scheduleChunkBatch(anchor, queue, generator, minY, topY, clearUntil, jobHandle, remainingChunkQueues, config.jobTimeoutMillis)
        }

        plugin.logger.info("Queued Folia plot reset for ${region.id.value} (${reason.name.lowercase()})")
        return true
    }

    private fun scheduleChunkBatch(
        anchor: Location,
        queue: ArrayDeque<List<Pair<Int, Int>>>,
        generator: PlotWorldChunkGenerator,
        minY: Int,
        topY: Int,
        clearUntil: Int,
        jobHandle: PlotJobRegistry.JobHandle,
        remainingChunkQueues: AtomicInteger,
        timeoutMillis: Long,
        attempt: Int = 0
    ) {
        Bukkit.getRegionScheduler().run(plugin, anchor) { _ ->
            if (jobHandle.isCancellationRequested(timeoutMillis)) {
                finishChunkQueue(remainingChunkQueues, jobHandle)
                return@run
            }
            val world = anchor.world
            if (world == null) {
                finishChunkQueue(remainingChunkQueues, jobHandle)
                return@run
            }
            val batch = queue.pollFirst()
            if (batch == null) {
                finishChunkQueue(remainingChunkQueues, jobHandle)
                return@run
            }

            val failure = runCatching {
                for ((x, z) in batch) {
                    for (y in minY..topY) {
                        world.getBlockAt(x, y, z).setType(generator.getBlockAt(x, y, z), false)
                    }
                    for (y in (topY + 1)..clearUntil) {
                        world.getBlockAt(x, y, z).setType(Material.AIR, false)
                    }
                }
            }.exceptionOrNull()

            if (failure != null) {
                if (attempt < MAX_BATCH_RETRIES) {
                    queue.addFirst(batch)
                    plugin.logger.warning(
                        "Folia plot reset batch retry ${attempt + 1}/${MAX_BATCH_RETRIES} in ${world.name} at ${anchor.blockX shr 4},${anchor.blockZ shr 4}: ${failure.message}"
                    )
                    Bukkit.getAsyncScheduler().runDelayed(plugin, { _ ->
                        scheduleChunkBatch(anchor, queue, generator, minY, topY, clearUntil, jobHandle, remainingChunkQueues, timeoutMillis, attempt + 1)
                    }, RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
                    return@run
                }
                plugin.logger.warning(
                    "Folia plot reset batch failed in ${world.name} at ${anchor.blockX shr 4},${anchor.blockZ shr 4}: ${failure.message}"
                )
            }

            if (queue.isNotEmpty()) {
                Bukkit.getAsyncScheduler().runDelayed(plugin, { _ ->
                    scheduleChunkBatch(anchor, queue, generator, minY, topY, clearUntil, jobHandle, remainingChunkQueues, timeoutMillis)
                }, RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
            } else {
                finishChunkQueue(remainingChunkQueues, jobHandle)
            }
        }
    }

    private fun finishChunkQueue(remainingChunkQueues: AtomicInteger, jobHandle: PlotJobRegistry.JobHandle) {
        if (remainingChunkQueues.decrementAndGet() == 0) {
            jobHandle.close()
        }
    }

    private companion object {
        const val MAX_BATCH_RETRIES = 1
        const val RETRY_DELAY_MS = 50L
    }
}
