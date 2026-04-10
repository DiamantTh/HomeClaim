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

    override fun queueReset(region: Region, reason: PlotResetReason): Boolean {
        val world = Bukkit.getWorld(region.world) ?: return false
        val config = configStore.loadConfig(region.world)?.sanitized() ?: return false
        if (reason == PlotResetReason.DELETE && !config.resetOnDelete) return false
        if (reason == PlotResetReason.UNCLAIM && !config.resetOnUnclaim) return false

        val chunkedColumns = PlotChunkPlanner.groupColumnsByChunk(PlotResetPlanner.interiorColumns(region.bounds))
        if (chunkedColumns.isEmpty()) return false

        val generator = PlotWorldChunkGenerator(config)
        val minY = config.minGenHeight ?: region.bounds.minY
        val topY = (config.plotHeight - 1).coerceAtLeast(minY)
        val clearUntil = region.bounds.maxY
        val columnsPerBatch = (config.resetBatchColumnsPerTick / chunkedColumns.size).coerceAtLeast(1)

        chunkedColumns.forEach { (chunk, chunkColumns) ->
            val queue = ArrayDeque(chunkColumns)
            val anchor = Location(world, (chunk.x shl 4).toDouble(), world.minHeight.toDouble(), (chunk.z shl 4).toDouble())
            scheduleChunkBatch(anchor, queue, generator, minY, topY, clearUntil, columnsPerBatch)
        }

        plugin.logger.info("Queued Folia plot reset for ${region.id.value} (${reason.name.lowercase()})")
        return true
    }

    private fun scheduleChunkBatch(
        anchor: Location,
        queue: ArrayDeque<Pair<Int, Int>>,
        generator: PlotWorldChunkGenerator,
        minY: Int,
        topY: Int,
        clearUntil: Int,
        columnsPerBatch: Int
    ) {
        Bukkit.getRegionScheduler().run(plugin, anchor) { _ ->
            val world = anchor.world ?: return@run
            var processed = 0
            while (processed < columnsPerBatch && queue.isNotEmpty()) {
                val (x, z) = queue.removeFirst()
                for (y in minY..topY) {
                    world.getBlockAt(x, y, z).setType(generator.getBlockAt(x, y, z), false)
                }
                for (y in (topY + 1)..clearUntil) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false)
                }
                processed++
            }

            if (queue.isNotEmpty()) {
                Bukkit.getAsyncScheduler().runDelayed(plugin, { _ ->
                    scheduleChunkBatch(anchor, queue, generator, minY, topY, clearUntil, columnsPerBatch)
                }, 50L, TimeUnit.MILLISECONDS)
            }
        }
    }
}
