package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.plugin.java.JavaPlugin
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.platform.paper.plot.PlotWorldConfig
import systems.diath.homeclaim.platform.paper.plot.PlotWorldConfigStore

private data class PlotBorderStyle(
    val fillMaterial: Material,
    val capMaterial: Material
)

/**
 * Initial Paper-first implementation.
 *
 * It only repaints the existing border columns for a plot state change and keeps
 * the operation intentionally small. Full plot resets / merge-road rebuilding can
 * be layered on later without touching the core claim logic.
 */
class PaperPlotMutationService(
    private val plugin: JavaPlugin,
    private val configStore: PlotWorldConfigStore = PlotWorldConfigStore(plugin)
) : PlotMutationService {

    override fun applyRegionState(region: Region) {
        val world = Bukkit.getWorld(region.world) ?: return
        val config = configStore.loadConfig(region.world) ?: return
        val visualState = PlotVisualStates.resolve(region)
        val style = styleFor(region, config, visualState)

        Bukkit.getScheduler().runTask(plugin, Runnable {
            repaintBorder(world, region.bounds, config, style)
        })
    }

    override fun handleRegionDeleted(region: Region) {
        val unclaimedSnapshot = region.copy(owner = PlotVisualStates.UNCLAIMED_OWNER, mergeGroupId = null)
        applyRegionState(unclaimedSnapshot)
    }

    override fun handleRegionsMerged(regions: Collection<Region>) {
        if (regions.isEmpty()) return
        val worldName = regions.first().world
        val world = Bukkit.getWorld(worldName) ?: return
        val config = configStore.loadConfig(worldName) ?: return

        Bukkit.getScheduler().runTask(plugin, Runnable {
            regions.forEach { region ->
                val style = styleFor(region, config, PlotVisualState.MERGED)
                repaintBorder(world, region.bounds, config, style)
            }
        })
    }

    private fun styleFor(region: Region, config: PlotWorldConfig, state: PlotVisualState): PlotBorderStyle {
        val explicitCap = region.metadata["plot.visual.border.material"]
            ?.let(Material::matchMaterial)
            ?.takeIf { it != Material.AIR }

        val capMaterial = explicitCap ?: when (state) {
            PlotVisualState.UNCLAIMED -> config.wallBlock
            PlotVisualState.CLAIMED,
            PlotVisualState.MERGED -> config.accentBlock ?: config.wallBlock
            PlotVisualState.SALE -> Material.GOLD_BLOCK
            PlotVisualState.ADMIN -> Material.EMERALD_BLOCK
            PlotVisualState.RESERVED -> Material.REDSTONE_BLOCK
        }

        return PlotBorderStyle(
            fillMaterial = config.wallBlock,
            capMaterial = capMaterial
        )
    }

    private fun repaintBorder(
        world: org.bukkit.World,
        bounds: Bounds,
        config: PlotWorldConfig,
        style: PlotBorderStyle
    ) {
        val minY = config.minGenHeight ?: bounds.minY
        val topY = (config.plotHeight - 1).coerceAtLeast(minY)
        val borderColumns = PlotBorderPlanner.borderColumns(bounds)

        for ((x, z) in borderColumns) {
            for (y in minY until topY) {
                world.getBlockAt(x, y, z).setType(style.fillMaterial, false)
            }
            world.getBlockAt(x, topY, z).setType(style.capMaterial, false)
        }
    }
}
