package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.plugin.java.JavaPlugin
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.platform.paper.plot.PlotWorldConfig
import systems.diath.homeclaim.platform.paper.plot.PlotWorldConfigStore

/**
 * Folia-aware mutation path.
 *
 * Unlike the Paper service this schedules work chunk-by-chunk through the
 * RegionScheduler so lightweight border and corridor repaints can also happen
 * safely on Folia.
 */
class FoliaPlotMutationService(
    private val plugin: JavaPlugin,
    private val configStore: PlotWorldConfigStore = PlotWorldConfigStore(plugin)
) : PlotMutationService {

    override fun applyRegionState(region: Region) {
        val world = Bukkit.getWorld(region.world) ?: return
        val config = configStore.loadConfig(region.world) ?: return
        val visualState = PlotVisualStates.resolve(region)
        val style = PlotMutationSupport.styleFor(region, config, visualState)

        repaintBorder(world, region.bounds, config, style)
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

        regions.forEach { region ->
            val siblings = regions.filter { it.id != region.id && it.mergeGroupId == region.mergeGroupId }
            val shared = PlotBorderPlanner.sharedEdges(region.bounds, siblings.map { it.bounds }, maxGap)
            val style = PlotMutationSupport.styleFor(region, config, PlotVisualState.MERGED)
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
        repaintCorridors(world, regions.toList(), config, mergedFillStyle, maxGap) { first, second ->
            first.mergeGroupId != null && first.mergeGroupId == second.mergeGroupId
        }
    }

    override fun handleRegionsUnlinked(regions: Collection<Region>, createRoads: Boolean) {
        if (regions.isEmpty()) return
        val worldName = regions.first().world
        val world = Bukkit.getWorld(worldName) ?: return
        val config = configStore.loadConfig(worldName) ?: return
        val maxGap = (config.roadWidth + 2).coerceAtLeast(1)

        regions.forEach { region ->
            val style = PlotMutationSupport.styleFor(region, config, PlotVisualStates.resolve(region))
            repaintBorder(world, region.bounds, config, style)
        }

        if (createRoads) {
            val roadStyle = PlotBorderStyle(
                fillMaterial = config.roadBlock,
                capMaterial = config.roadBlock
            )
            repaintCorridors(world, regions.toList(), config, roadStyle, maxGap) { _, _ -> true }
        }
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
        val batches = PlotChunkPlanner.batchColumnsByChunk(columns, config.mutationBatchColumnsPerTask)
        batches.forEach { batch ->
            val anchor = Location(world, (batch.chunk.x shl 4).toDouble(), world.minHeight.toDouble(), (batch.chunk.z shl 4).toDouble())
            Bukkit.getRegionScheduler().run(plugin, anchor) { _ ->
                runCatching {
                    PlotMutationSupport.repaintColumns(world, batch.columns, config, style)
                }.onFailure { error ->
                    plugin.logger.warning(
                        "Folia plot mutation batch failed in ${world.name} chunk ${batch.chunk.x},${batch.chunk.z}: ${error.message}"
                    )
                }
            }
        }
    }

    private fun repaintCorridors(
        world: org.bukkit.World,
        regions: List<Region>,
        config: PlotWorldConfig,
        style: PlotBorderStyle,
        maxGap: Int,
        predicate: (Region, Region) -> Boolean
    ) {
        for (i in regions.indices) {
            for (j in i + 1 until regions.size) {
                val first = regions[i]
                val second = regions[j]
                if (predicate(first, second)) {
                    val corridor = PlotBorderPlanner.mergeCorridorColumns(first.bounds, second.bounds, maxGap)
                    repaintColumns(world, corridor, config, style)
                }
            }
        }
    }
}
