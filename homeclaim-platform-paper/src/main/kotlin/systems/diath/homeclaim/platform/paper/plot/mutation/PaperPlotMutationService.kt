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
        val maxGap = (config.roadWidth + 2).coerceAtLeast(1)

        Bukkit.getScheduler().runTask(plugin, Runnable {
            regions.forEach { region ->
                val siblings = regions.filter { it.id != region.id && it.mergeGroupId == region.mergeGroupId }
                val shared = PlotBorderPlanner.sharedEdges(region.bounds, siblings.map { it.bounds }, maxGap)
                val style = styleFor(region, config, PlotVisualState.MERGED)
                repaintBorder(
                    world = world,
                    bounds = region.bounds,
                    config = config,
                    style = style,
                    includeNorth = !shared.north,
                    includeSouth = !shared.south,
                    includeWest = !shared.west,
                    includeEast = !shared.east
                )
            }

            val mergedFillStyle = PlotBorderStyle(
                fillMaterial = config.plotBlock,
                capMaterial = config.plotBlock
            )
            val regionList = regions.toList()
            for (i in regionList.indices) {
                for (j in i + 1 until regionList.size) {
                    val first = regionList[i]
                    val second = regionList[j]
                    if (first.mergeGroupId != null && first.mergeGroupId == second.mergeGroupId) {
                        val corridor = PlotBorderPlanner.mergeCorridorColumns(first.bounds, second.bounds, maxGap)
                        repaintColumns(world, corridor, config, mergedFillStyle)
                    }
                }
            }
        })
    }

    private fun styleFor(region: Region, config: PlotWorldConfig, state: PlotVisualState): PlotBorderStyle {
        val explicitCap = region.metadata["plot.visual.border.material"]
            ?.let(Material::matchMaterial)
            ?.takeIf { it != Material.AIR }

        val capMaterial = explicitCap ?: when (state) {
            PlotVisualState.UNCLAIMED -> config.unclaimedBorderBlock
            PlotVisualState.CLAIMED -> config.claimedBorderBlock
            PlotVisualState.MERGED -> config.mergedBorderBlock
            PlotVisualState.SALE -> config.saleBorderBlock
            PlotVisualState.ADMIN -> config.adminBorderBlock
            PlotVisualState.RESERVED -> config.reservedBorderBlock
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
        style: PlotBorderStyle,
        includeNorth: Boolean = true,
        includeSouth: Boolean = true,
        includeWest: Boolean = true,
        includeEast: Boolean = true
    ) {
        val borderColumns = PlotBorderPlanner.borderColumns(
            bounds,
            includeNorth = includeNorth,
            includeSouth = includeSouth,
            includeWest = includeWest,
            includeEast = includeEast
        )
        repaintColumns(world, borderColumns, config, style)
    }

    private fun repaintColumns(
        world: org.bukkit.World,
        columns: Collection<Pair<Int, Int>>,
        config: PlotWorldConfig,
        style: PlotBorderStyle
    ) {
        if (columns.isEmpty()) return
        val minY = config.minGenHeight ?: -64
        val topY = (config.plotHeight - 1).coerceAtLeast(minY)

        for ((x, z) in columns) {
            for (y in minY until topY) {
                world.getBlockAt(x, y, z).setType(style.fillMaterial, false)
            }
            world.getBlockAt(x, topY, z).setType(style.capMaterial, false)
        }
    }
}
