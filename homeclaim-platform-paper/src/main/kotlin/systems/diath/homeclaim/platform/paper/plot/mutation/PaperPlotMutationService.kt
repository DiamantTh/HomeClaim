package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.plugin.java.JavaPlugin
import systems.diath.homeclaim.core.mutation.MutationJobInfo
import systems.diath.homeclaim.core.mutation.MutationReason
import systems.diath.homeclaim.core.mutation.MutationBatch
import systems.diath.homeclaim.core.mutation.WorldMutationBackend
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionOwnership
import systems.diath.homeclaim.core.service.WorldAreaService
import systems.diath.homeclaim.platform.paper.plot.PlotWorldConfig
import systems.diath.homeclaim.platform.paper.plot.PlotWorldConfigStore
import java.util.concurrent.CompletableFuture

/**
 * Initial Paper-first implementation.
 *
 * It only repaints the existing border columns for a plot state change and keeps
 * the operation intentionally small. Full plot resets / merge-road rebuilding can
 * be layered on later without touching the core claim logic.
 */
class PaperPlotMutationService(
    private val plugin: JavaPlugin,
    private val configStore: PlotWorldConfigStore = PlotWorldConfigStore(plugin),
    private val mutationBackend: WorldMutationBackend = PaperSynchronousWorldMutationBackend(),
    private val worldAreaService: WorldAreaService? = null,
    private val faultInjector: PlotMutationFaultInjector = PlotMutationFaultInjector.NONE
) : PlotMutationService, OperationScopedPlotMutationService {

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
        return mutationBackend.activeJobs(worldName)
    }

    override fun applyRegionState(region: Region, reason: MutationReason) {
        val resolved = resolveWorld(region) ?: return
        val world = resolved.world
        val config = configStore.loadConfig(resolved.configName) ?: return
        val visualState = PlotVisualStates.resolve(region)
        val style = PlotMutationSupport.styleFor(region, config, visualState)

        Bukkit.getScheduler().runTask(plugin, Runnable {
            repaintBorder(world, region.bounds, config, style, reason, "paper-mutation:region:${region.id.value}")
        })
    }

    override fun handleRegionDeleted(region: Region, reason: MutationReason) {
        val unclaimedSnapshot = region.copy(owner = RegionOwnership.UNCLAIMED_OWNER_ID, mergeGroupId = null)
        applyRegionState(unclaimedSnapshot, reason)
    }

    override fun handleRegionsMerged(regions: Collection<Region>, reason: MutationReason): CompletableFuture<Unit> {
        val plan = runCatching { prepareMergeMutation(regions, reason) }.getOrElse { return CompletableFuture.failedFuture(it) }
        return applyPreparedMutation(plan, "merge")
    }

    override fun prepareMergeMutation(regions: Collection<Region>, reason: MutationReason): PreparedPlotWorldMutation {
        require(regions.isNotEmpty()) { "Merge requires regions" }
        val merged = regions.distinctBy { it.id }
        val resolved = resolveWorld(merged.first()) ?: error("World binding unavailable for merge mutation: ${merged.first().areaId.value}")
        require(merged.all { resolveWorld(it)?.world?.uid == resolved.world.uid }) { "Merge regions must belong to one Paper world" }
        val config = configStore.loadConfig(resolved.configName) ?: error("Plot-world config unavailable for merge mutation: ${resolved.configName}")
        val maxGap = (config.roadWidth + 2).coerceAtLeast(1)
        val batches = mutableListOf<MutationBatch>()
        merged.forEach { region ->
            val siblings = merged.filter { it.id != region.id && it.mergeGroupId == region.mergeGroupId }
            val shared = PlotBorderPlanner.sharedEdges(region.bounds, siblings.map { it.bounds }, maxGap)
            batches += PlotMutationPlanFactory.borderBatch(
                id = "paper-mutation:merge-border:${region.id.value}", world = resolved.world.name,
                bounds = region.bounds, config = config,
                style = PlotMutationSupport.styleFor(region, config, PlotVisualState.MERGED), reason = reason,
                includeNorth = !shared.north, includeSouth = !shared.south,
                includeWest = !shared.west, includeEast = !shared.east
            )
        }
        batches += corridorBatches(
            resolved.world, merged, config, PlotBorderStyle(config.plotBlock, config.plotBlock), maxGap, reason
        ) { first, second -> first.mergeGroupId != null && first.mergeGroupId == second.mergeGroupId }
        return PreparedPlotWorldMutation(resolved.world.name, resolved.world.uid, batches)
    }

    override fun prepareUnlinkMutation(
        regions: Collection<Region>, createRoads: Boolean, reason: MutationReason
    ): PreparedPlotWorldMutation {
        require(regions.isNotEmpty()) { "Unlink requires regions" }
        val grouped = regions.distinctBy { it.id }
        val resolved = resolveWorld(grouped.first()) ?: error("World binding unavailable for unlink mutation: ${grouped.first().areaId.value}")
        require(grouped.all { resolveWorld(it)?.world?.uid == resolved.world.uid }) { "Unlink regions must belong to one Paper world" }
        val config = configStore.loadConfig(resolved.configName) ?: error("Plot-world config unavailable for unlink mutation: ${resolved.configName}")
        val maxGap = (config.roadWidth + 2).coerceAtLeast(1)
        val batches = grouped.map { region ->
            PlotMutationPlanFactory.borderBatch(
                id = "paper-mutation:unlink-border:${region.id.value}", world = resolved.world.name,
                bounds = region.bounds, config = config,
                style = PlotMutationSupport.styleFor(region, config, PlotVisualStates.resolve(region)), reason = reason
            )
        }.toMutableList()
        if (createRoads) {
            batches += corridorBatches(
                resolved.world, grouped, config,
                PlotBorderStyle(config.roadBlock, config.roadBlock), maxGap, reason
            ) { _, _ -> true }
        }
        return PreparedPlotWorldMutation(resolved.world.name, resolved.world.uid, batches)
    }

    override fun applyPreparedMutation(plan: PreparedPlotWorldMutation, label: String): CompletableFuture<Unit> {
        val result = CompletableFuture<Unit>()
        try {
            Bukkit.getScheduler().runTask(plugin, Runnable {
                try {
                    require(Bukkit.getWorld(plan.worldId)?.name == plan.worldName) { "Prepared mutation world binding changed" }
                    val world = requireNotNull(Bukkit.getWorld(plan.worldId))
                    plan.batches.forEachIndexed { index, batch ->
                        applyBatch(world, batch, true)
                        check(batch.operations.all { op ->
                            world.getBlockAt(op.x, op.y, op.z).type == org.bukkit.Material.matchMaterial(op.blockStateId)
                        }) { "World mutation verification failed for batch ${batch.id}" }
                        faultInjector.afterBatchApplied(label, index + 1)
                    }
                    result.complete(Unit)
                } catch (failure: Throwable) {
                    plugin.logger.log(java.util.logging.Level.SEVERE, "Paper $label mutation failed for ${plan.worldName}", failure)
                    result.completeExceptionally(failure)
                }
            })
        } catch (failure: Throwable) {
            result.completeExceptionally(failure)
        }
        return result
    }

    override fun restoreRegionVisualStates(regions: Collection<Region>): CompletableFuture<Unit> {
        if (regions.isEmpty()) return CompletableFuture.completedFuture(Unit)
        val resolved = resolveWorld(regions.first())
            ?: return CompletableFuture.failedFuture(IllegalStateException("World binding unavailable for merge compensation: ${regions.first().areaId.value}"))
        val worldName = resolved.configName
        if (regions.any { resolveWorld(it)?.world?.uid != resolved.world.uid }) {
            return CompletableFuture.failedFuture(IllegalArgumentException("Restored regions must belong to one Paper world"))
        }
        val world = resolved.world
        val config = configStore.loadConfig(worldName)
            ?: return CompletableFuture.failedFuture(IllegalStateException("Plot-world config unavailable for merge compensation: $worldName"))
        val maxGap = (config.roadWidth + 2).coerceAtLeast(1)
        val result = CompletableFuture<Unit>()

        try {
            Bukkit.getScheduler().runTask(plugin, Runnable {
                try {
                    val snapshots = regions.toList()
                    snapshots.forEach { region ->
                        val siblings = snapshots.filter {
                            it.id != region.id && region.mergeGroupId != null && it.mergeGroupId == region.mergeGroupId
                        }
                        val shared = PlotBorderPlanner.sharedEdges(region.bounds, siblings.map { it.bounds }, maxGap)
                        repaintBorder(
                            world = world,
                            bounds = region.bounds,
                            config = config,
                            style = PlotMutationSupport.styleFor(region, config, PlotVisualStates.resolve(region)),
                            reason = MutationReason.ADMIN,
                            batchId = "paper-mutation:merge-compensation:${region.id.value}",
                            includeNorth = !shared.north,
                            includeSouth = !shared.south,
                            includeWest = !shared.west,
                            includeEast = !shared.east,
                            throwOnMutationFailure = true
                        )
                    }

                    repaintCorridors(
                        world, snapshots, config, PlotBorderStyle(config.plotBlock, config.plotBlock), maxGap,
                        MutationReason.ADMIN,
                        predicate = { first, second -> first.mergeGroupId != null && first.mergeGroupId == second.mergeGroupId },
                        throwOnMutationFailure = true
                    )
                    repaintCorridors(
                        world, snapshots, config, PlotBorderStyle(config.roadBlock, config.roadBlock), maxGap,
                        MutationReason.ADMIN,
                        predicate = { first, second -> first.mergeGroupId != second.mergeGroupId },
                        throwOnMutationFailure = true
                    )
                    result.complete(Unit)
                } catch (failure: Throwable) {
                    plugin.logger.log(java.util.logging.Level.SEVERE, "Paper merge compensation failed for $worldName", failure)
                    result.completeExceptionally(failure)
                }
            })
        } catch (failure: Throwable) {
            result.completeExceptionally(failure)
        }
        return result
    }

    override fun handleRegionsUnlinked(
        regions: Collection<Region>,
        createRoads: Boolean,
        reason: MutationReason
    ): CompletableFuture<Unit> {
        val plan = runCatching { prepareUnlinkMutation(regions, createRoads, reason) }.getOrElse { return CompletableFuture.failedFuture(it) }
        return applyPreparedMutation(plan, "unlink")
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
        includeEast: Boolean = true,
        throwOnMutationFailure: Boolean = false
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
        applyBatch(world, batch, throwOnMutationFailure)
    }

    private fun applyBatch(
        @Suppress("UNUSED_PARAMETER") world: org.bukkit.World,
        batch: MutationBatch,
        throwOnMutationFailure: Boolean = false
    ) {
        runCatching {
            mutationBackend.submit(batch)
        }.onFailure { error ->
            plugin.logger.warning("Paper plot mutation batch failed for ${batch.id}: ${error.message}")
            if (throwOnMutationFailure) throw error
        }
    }

    private fun repaintCorridors(
        world: org.bukkit.World,
        regions: List<Region>,
        config: PlotWorldConfig,
        style: PlotBorderStyle,
        maxGap: Int,
        reason: MutationReason,
        predicate: (Region, Region) -> Boolean,
        throwOnMutationFailure: Boolean = false
    ) {
        corridorBatches(world, regions, config, style, maxGap, reason, predicate).forEach { batch ->
            applyBatch(world, batch, throwOnMutationFailure)
        }
    }

    private fun corridorBatches(
        world: World,
        regions: List<Region>,
        config: PlotWorldConfig,
        style: PlotBorderStyle,
        maxGap: Int,
        reason: MutationReason,
        predicate: (Region, Region) -> Boolean
    ): List<MutationBatch> {
        val batches = mutableListOf<MutationBatch>()
        for (i in regions.indices) {
            for (j in i + 1 until regions.size) {
                val first = regions[i]
                val second = regions[j]
                if (predicate(first, second)) {
                    val corridor = PlotBorderPlanner.mergeCorridorColumns(first.bounds, second.bounds, maxGap)
                    val orderedIds = listOf(first.id.value.toString(), second.id.value.toString()).sorted()
                    batches += PlotMutationPlanFactory.corridorBatch(
                        id = "paper-mutation:corridor:${orderedIds[0]}:${orderedIds[1]}",
                        world = world.name,
                        columns = corridor,
                        config = config,
                        style = style,
                        reason = reason
                    )
                }
            }
        }
        return batches
    }

    private data class ResolvedWorld(val world: World, val configName: String)

    private fun resolveWorld(region: Region): ResolvedWorld? {
        val service = worldAreaService
        if (service == null) {
            val legacyWorld = Bukkit.getWorld(region.world) ?: return null
            return ResolvedWorld(legacyWorld, region.world)
        }
        val area = service.getArea(region.areaId) ?: return null
        val homeWorld = service.getWorld(area.worldId) ?: return null
        val platformId = homeWorld.platformWorldId
        val paperWorld = (if (platformId != null) Bukkit.getWorld(platformId) else Bukkit.getWorld(homeWorld.name))
            ?: return null
        return ResolvedWorld(paperWorld, homeWorld.name)
    }
}

/** Dependency-injected test hook; there is no production config or command to activate it. */
fun interface PlotMutationFaultInjector {
    fun afterBatchApplied(operation: String, appliedBatches: Int)

    companion object { val NONE = PlotMutationFaultInjector { _, _ -> } }
}
