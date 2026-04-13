package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import systems.diath.homeclaim.core.mutation.MutationBatch
import systems.diath.homeclaim.core.mutation.MutationJobInfo
import systems.diath.homeclaim.core.mutation.MutationReason
import systems.diath.homeclaim.core.mutation.WorldMutationBackend
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
    private val configStore: PlotWorldConfigStore = PlotWorldConfigStore(plugin),
    private val mutationBackend: WorldMutationBackend = FoliaRegionScheduledWorldMutationBackend(plugin)
) : PlotMutationService {
    override fun cancelPendingJobs(worldName: String?): Int {
        val jobs = activeJobInfo(worldName)
        return jobs.count { mutationBackend.cancel(it.ticketId) }
    }

    override fun activeJobDiagnostics(worldName: String?): List<String> {
        return activeJobInfo(worldName).map { info ->
            "mutation:${info.world}:${info.ticketId}:reason=${info.reason.name}:age=${info.queuedMillis}ms:cancelled=${info.cancelRequested}"
        }
    }

    override fun activeJobs(worldName: String?): List<PlotJobSnapshot> {
        return activeJobInfo(worldName).map { info ->
            PlotJobSnapshot(
                key = info.ticketId,
                world = info.world,
                kind = PlotJobRegistry.JobKind.MUTATION.name,
                reason = info.reason,
                ageMillis = info.queuedMillis,
                cancelRequested = info.cancelRequested
            )
        }
    }

    override fun activeJobInfo(worldName: String?): List<MutationJobInfo> {
        return mutationBackend.activeJobs(worldName).filter { it.reason != MutationReason.RESET }
    }

    override fun applyRegionState(region: Region, reason: MutationReason) {
        val world = Bukkit.getWorld(region.world) ?: return
        val config = configStore.loadConfig(region.world) ?: return
        val visualState = PlotVisualStates.resolve(region)
        val style = PlotMutationSupport.styleFor(region, config, visualState)

        repaintBorder(
            world,
            region.bounds,
            config,
            style,
            reason,
            jobKey = "folia-mutation:region:${region.id.value}"
        )
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
                includeNorth = !shared.north,
                includeSouth = !shared.south,
                includeWest = !shared.west,
                includeEast = !shared.east,
                jobKey = "folia-mutation:merge-border:${region.id.value}"
            )
        }

        val mergedFillStyle = PlotBorderStyle(
            fillMaterial = config.plotBlock,
            capMaterial = config.plotBlock
        )
        repaintCorridors(
            world = world,
            regions = regions.toList(),
            config = config,
            style = mergedFillStyle,
            maxGap = maxGap,
            jobKeyPrefix = "folia-mutation:merge-corridor",
            predicate = { first, second ->
                first.mergeGroupId != null && first.mergeGroupId == second.mergeGroupId
            },
            reason = reason
        )
    }

    override fun handleRegionsUnlinked(regions: Collection<Region>, createRoads: Boolean, reason: MutationReason) {
        if (regions.isEmpty()) return
        val worldName = regions.first().world
        val world = Bukkit.getWorld(worldName) ?: return
        val config = configStore.loadConfig(worldName) ?: return
        val maxGap = (config.roadWidth + 2).coerceAtLeast(1)

        regions.forEach { region ->
            val style = PlotMutationSupport.styleFor(region, config, PlotVisualStates.resolve(region))
            repaintBorder(world, region.bounds, config, style, reason, jobKey = "folia-mutation:unlink-border:${region.id.value}")
        }

        if (createRoads) {
            val roadStyle = PlotBorderStyle(
                fillMaterial = config.roadBlock,
                capMaterial = config.roadBlock
            )
            repaintCorridors(
                world = world,
                regions = regions.toList(),
                config = config,
                style = roadStyle,
                maxGap = maxGap,
                jobKeyPrefix = "folia-mutation:unlink-corridor",
                predicate = { _, _ -> true },
                reason = reason
            )
        }
    }

    private fun repaintBorder(
        world: org.bukkit.World,
        bounds: Bounds,
        config: PlotWorldConfig,
        style: PlotBorderStyle,
        reason: MutationReason,
        includeNorth: Boolean = true,
        includeSouth: Boolean = true,
        includeWest: Boolean = true,
        includeEast: Boolean = true,
        jobKey: String
    ) {
        val batch = PlotMutationPlanFactory.borderBatch(
            id = jobKey,
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
        repaintBatch(world, batch, config)
    }

    private fun repaintBatch(
        world: org.bukkit.World,
        batch: MutationBatch,
        @Suppress("UNUSED_PARAMETER") config: PlotWorldConfig,
    ) {
        runCatching {
            mutationBackend.submit(batch)
        }.onFailure { error ->
            plugin.logger.fine("Folia plot mutation submit rejected for ${batch.id}: ${error.message}")
        }
    }

    private fun repaintCorridors(
        world: org.bukkit.World,
        regions: List<Region>,
        config: PlotWorldConfig,
        style: PlotBorderStyle,
        maxGap: Int,
        jobKeyPrefix: String,
        predicate: (Region, Region) -> Boolean,
        reason: MutationReason
    ) {
        for (i in regions.indices) {
            for (j in i + 1 until regions.size) {
                val first = regions[i]
                val second = regions[j]
                if (predicate(first, second)) {
                    val corridor = PlotBorderPlanner.mergeCorridorColumns(first.bounds, second.bounds, maxGap)
                    val orderedIds = listOf(first.id.value.toString(), second.id.value.toString()).sorted()
                    val batch = PlotMutationPlanFactory.corridorBatch(
                        id = "$jobKeyPrefix:${orderedIds[0]}:${orderedIds[1]}",
                        world = world.name,
                        columns = corridor,
                        config = config,
                        style = style,
                        reason = reason
                    )
                    repaintBatch(world, batch, config)
                }
            }
        }
    }
}
