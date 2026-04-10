package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.scheduler.BukkitTask
import org.bukkit.plugin.java.JavaPlugin
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
    private val configStore: PlotWorldConfigStore = PlotWorldConfigStore(plugin)
) : PlotResetService {

    override fun queueReset(region: Region, reason: PlotResetReason): Boolean {
        val world = Bukkit.getWorld(region.world) ?: return false
        val config = configStore.loadConfig(region.world)?.sanitized() ?: return false
        if (reason == PlotResetReason.DELETE && !config.resetOnDelete) return false
        if (reason == PlotResetReason.UNCLAIM && !config.resetOnUnclaim) return false

        val columns = ArrayDeque(PlotResetPlanner.interiorColumns(region.bounds))
        if (columns.isEmpty()) return false

        val generator = PlotWorldChunkGenerator(config)
        val minY = config.minGenHeight ?: region.bounds.minY
        val topY = (config.plotHeight - 1).coerceAtLeast(minY)
        val clearUntil = region.bounds.maxY
        val columnsPerTick = config.resetBatchColumnsPerTick.coerceAtLeast(1)

        var scheduledTask: BukkitTask? = null
        scheduledTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            var processed = 0
            while (processed < columnsPerTick && columns.isNotEmpty()) {
                val (x, z) = columns.removeFirst()
                for (y in minY..topY) {
                    world.getBlockAt(x, y, z).setType(generator.getBlockAt(x, y, z), false)
                }
                for (y in (topY + 1)..clearUntil) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false)
                }
                processed++
            }
            if (columns.isEmpty()) {
                scheduledTask?.cancel()
            }
        }, 1L, 1L)

        plugin.logger.info("Queued plot reset for ${region.id.value} (${reason.name.lowercase()})")
        return true
    }
}
