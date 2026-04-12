package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import systems.diath.homeclaim.core.mutation.MutationReason
import systems.diath.homeclaim.core.mutation.MutationBatch
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.platform.paper.plot.PlotWorldConfig
import systems.diath.homeclaim.platform.paper.plot.PlotWorldConfigStore

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

    override fun applyRegionState(region: Region, reason: MutationReason) {
        val world = Bukkit.getWorld(region.world) ?: return
        val config = configStore.loadConfig(region.world) ?: return
        val visualState = PlotVisualStates.resolve(region)
        val style = PlotMutationSupport.styleFor(region, config, visualState)

        Bukkit.getScheduler().runTask(plugin, Runnable {
            repaintBorder(world, region.bounds, config, style, reason, "paper-mutation:region:${region.id.value}")
        })
    }

    override fun handleRegionDeleted(region: Region, reason: MutationReason) {
        val unclaimedSnapshot = region.copy(owner = PlotVisualStates.UNCLAIMED_OWNER, mergeGroupId = null)
        applyRegionState(unclaimedSnapshot, reason)
    }

    override fun handleRegionsMerged(regions: Collection<Region>, reason: MutationReason) {
        if (regions.isEmpty()) return
        val worldName = regions.first().world
        val world = Bukkit.getWorld(worldName) ?: return
        val config = configStore.loadConfig(worldName) ?: return
        val maxGap = (config.roadWidth + 2).coerceAtLeast(1)

        Bukkit.getScheduler().runTask(plugin, Runnable {
            regions.forEach { region ->
                val siblings = regions.filter { it.id != region.id && it.mergeGroupId == region.mergeGroupId }
                val shared = PlotBorderPlanner.sharedEdges(region.bounds, siblings.map { it.bounds }, maxGap)
                val style = PlotMutationSupport.styleFor(region, config, PlotVisualState.MERGED)
                repaintBorder(
                    world = world,
                    bounds = region.bounds,
                    config = config,
                    style = style,
                    reason = reason,
                    batchId = "paper-mutation:merge-border:${region.id.value}",
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
                repaintCorridors(world, regions.toList(), config, mergedFillStyle, maxGap, reason) { first, second ->
                first.mergeGroupId != null && first.mergeGroupId == second.mergeGroupId
            }
        })
    }

    override fun handleRegionsUnlinked(regions: Collection<Region>, createRoads: Boolean, reason: MutationReason) {
        if (regions.isEmpty()) return
        val worldName = regions.first().world
        val world = Bukkit.getWorld(worldName) ?: return
        val config = configStore.loadConfig(worldName) ?: return
        val maxGap = (config.roadWidth + 2).coerceAtLeast(1)

        Bukkit.getScheduler().runTask(plugin, Runnable {
            regions.forEach { region ->
                val style = PlotMutationSupport.styleFor(region, config, PlotVisualStates.resolve(region))
                repaintBorder(world, region.bounds, config, style, reason, "paper-mutation:unlink-border:${region.id.value}")
            }

            if (createRoads) {
                val roadStyle = PlotBorderStyle(
                    fillMaterial = config.roadBlock,
                    capMaterial = config.roadBlock
                )
                repaintCorridors(world, regions.toList(), config, roadStyle, maxGap, reason) { _, _ -> true }
            }
        })
    }

    private fun repaintBorder(
        world: org.bukkit.World,
        bounds: Bounds,
        config: PlotWorldConfig,
        style: PlotBorderStyle,
        reason: MutationReason,
        batchId: String,
        includeNorth: Boolean = true,
        includeSouth: Boolean = true,
        includeWest: Boolean = true,
        includeEast: Boolean = true
    ) {
        val batch = PlotMutationPlanFactory.borderBatch(
            id = batchId,
            world = world.name,
            bounds = bounds,
            config = config,
            style = style,
            reason = reason,
            includeNorth = includeNorth,
            includeSouth = includeSouth,
            includeWest = includeWest,
            includeEast = includeEast
        )
        applyBatch(world, batch)
    }

    private fun applyBatch(
        world: org.bukkit.World,
        batch: MutationBatch
    ) {
        PlotMutationExecutor.apply(world, batch)
    }

    private fun repaintCorridors(
        world: org.bukkit.World,
        regions: List<Region>,
        config: PlotWorldConfig,
        style: PlotBorderStyle,
        maxGap: Int,
        reason: MutationReason,
        predicate: (Region, Region) -> Boolean
    ) {
        for (i in regions.indices) {
            for (j in i + 1 until regions.size) {
                val first = regions[i]
                val second = regions[j]
                if (predicate(first, second)) {
                    val corridor = PlotBorderPlanner.mergeCorridorColumns(first.bounds, second.bounds, maxGap)
                    val orderedIds = listOf(first.id.value.toString(), second.id.value.toString()).sorted()
                    val batch = PlotMutationPlanFactory.corridorBatch(
                        id = "paper-mutation:corridor:${orderedIds[0]}:${orderedIds[1]}",
                        world = world.name,
                        columns = corridor,
                        config = config,
                        style = style,
                        reason = reason
                    )
                    applyBatch(world, batch)
                }
            }
        }
    }
}
