package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.plugin.java.JavaPlugin
import systems.diath.homeclaim.core.mutation.MutationBatch
import systems.diath.homeclaim.core.mutation.MutationReason
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.platform.paper.plot.PlotWorldConfig
import systems.diath.homeclaim.platform.paper.plot.PlotWorldConfigStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    private val jobRegistry = PlotJobRegistry()

    override fun cancelPendingJobs(worldName: String?): Int {
        return jobRegistry.requestCancelAll(world = worldName, kind = PlotJobRegistry.JobKind.MUTATION)
    }

    override fun activeJobDiagnostics(worldName: String?): List<String> {
        return jobRegistry.snapshot(world = worldName, kind = PlotJobRegistry.JobKind.MUTATION).map { snapshot ->
            "mutation:${snapshot.world}:${snapshot.key}:reason=${snapshot.reason?.name ?: MutationReason.ADMIN.name}:age=${snapshot.ageMillis}ms:cancelled=${snapshot.cancelRequested}"
        }
    }

    override fun activeJobs(worldName: String?): List<PlotJobSnapshot> {
        return jobRegistry.snapshot(world = worldName, kind = PlotJobRegistry.JobKind.MUTATION).map { snapshot ->
            PlotJobSnapshot(
                key = snapshot.key,
                world = snapshot.world,
                kind = snapshot.kind.name,
                reason = snapshot.reason,
                ageMillis = snapshot.ageMillis,
                cancelRequested = snapshot.cancelRequested
            )
        }
    }

    override fun activeJobInfo(worldName: String?) =
        jobRegistry.mutationJobInfo(world = worldName, kind = PlotJobRegistry.JobKind.MUTATION, defaultReason = MutationReason.ADMIN)

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
        repaintBatch(world, batch, config, reason)
    }

    private fun repaintBatch(
        world: org.bukkit.World,
        batch: MutationBatch,
        config: PlotWorldConfig,
        reason: MutationReason,
    ) {
        val jobHandle = jobRegistry.tryAcquire(
            key = batch.id,
            world = world.name,
            kind = PlotJobRegistry.JobKind.MUTATION,
            reason = reason,
            maxConcurrentPerWorld = config.maxConcurrentMutationJobsPerWorld,
            timeoutMillis = config.jobTimeoutMillis
        ) ?: run {
            val reasonText = if (jobRegistry.isActive(batch.id, config.jobTimeoutMillis)) "duplicate" else "world-limit"
            plugin.logger.fine("Skipping $reasonText Folia plot mutation job ${batch.id}")
            return
        }

        if (batch.operations.isEmpty()) {
            jobHandle.close()
            return
        }

        val operationsByChunk = batch.operations.groupBy { (it.x shr 4) to (it.z shr 4) }
        val remainingBatches = AtomicInteger(operationsByChunk.size)
        operationsByChunk.forEach { (chunk, operations) ->
            val chunkBatch = batch.copy(id = "${batch.id}:chunk:${chunk.first}:${chunk.second}", operations = operations)
            val anchor = Location(world, (chunk.first shl 4).toDouble(), world.minHeight.toDouble(), (chunk.second shl 4).toDouble())
            scheduleMutationBatch(anchor, chunkBatch, world, config, jobHandle, remainingBatches)
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
                    repaintBatch(world, batch, config, reason)
                }
            }
        }
    }

    private fun scheduleMutationBatch(
        anchor: Location,
        batch: MutationBatch,
        world: org.bukkit.World,
        config: PlotWorldConfig,
        jobHandle: PlotJobRegistry.JobHandle,
        remainingBatches: AtomicInteger,
        attempt: Int = 0
    ) {
        Bukkit.getRegionScheduler().run(plugin, anchor) { _ ->
            if (jobHandle.isCancellationRequested(config.jobTimeoutMillis)) {
                if (remainingBatches.decrementAndGet() == 0) {
                    jobHandle.close()
                }
                return@run
            }

            val failure = runCatching {
                PlotMutationExecutor.apply(world, batch)
            }.exceptionOrNull()

            if (failure != null && attempt < MAX_BATCH_RETRIES) {
                plugin.logger.warning(
                    "Folia plot mutation batch retry ${attempt + 1}/${MAX_BATCH_RETRIES} in ${world.name} for ${batch.id}: ${failure.message}"
                )
                Bukkit.getAsyncScheduler().runDelayed(plugin, { _ ->
                    scheduleMutationBatch(anchor, batch, world, config, jobHandle, remainingBatches, attempt + 1)
                }, RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
                return@run
            }

            if (failure != null) {
                plugin.logger.warning(
                    "Folia plot mutation batch failed in ${world.name} for ${batch.id}: ${failure.message}"
                )
            }
            if (remainingBatches.decrementAndGet() == 0) {
                jobHandle.close()
            }
        }
    }

    private companion object {
        const val MAX_BATCH_RETRIES = 1
        const val RETRY_DELAY_MS = 50L
    }
}
