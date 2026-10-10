package systems.diath.homeclaim.platform.paper.plot

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.plugin.Plugin
import systems.diath.homeclaim.platform.paper.scheduler.BukkitTaskScheduler
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.Area
import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.AreaScope
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionShape
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.core.service.WorldAreaService
import java.util.UUID

/**
 * Initializes plot regions in the database for a plot world.
 * This must be called after world creation to register all plots in the database.
 */
class PlotWorldInitializer(
    private val regionService: RegionService,
    private val plugin: Plugin,
    private val worldAreaService: WorldAreaService? = null
) {
    private val taskScheduler = BukkitTaskScheduler(plugin)
    companion object {
        // UUID for unclaimed plots (all zeros)
        private val UNCLAIMED_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
    }
    
    /**
     * Initialize all plot regions for a world asynchronously.
     * This creates Region entries in the database for each plot.
     * 
     * @param world The world to initialize
     * @param config Plot world configuration
     * @param progressCallback Optional callback for progress updates (0.0 to 1.0)
     * @return CompletableFuture with number of plots created
     */
    fun initializePlots(
        world: World,
        config: PlotWorldConfig,
        progressCallback: ((Double) -> Unit)? = null
    ): java.util.concurrent.CompletableFuture<Int> {
        val areaId = getOrCreatePlotArea(world, config.sanitized())
        return initializePlots(areaId, world.name, config, progressCallback)
    }

    fun initializePlots(
        areaId: AreaId,
        world: World,
        config: PlotWorldConfig,
        progressCallback: ((Double) -> Unit)? = null
    ): java.util.concurrent.CompletableFuture<Int> = initializePlots(areaId, world.name, config, progressCallback)

    fun initializePlots(
        worldName: String,
        config: PlotWorldConfig,
        progressCallback: ((Double) -> Unit)? = null
    ): java.util.concurrent.CompletableFuture<Int> {
        val paperWorld = Bukkit.getWorld(worldName)
        val areaId = if (paperWorld != null) getOrCreatePlotArea(paperWorld, config.sanitized())
        else AreaId.legacyDefault(WorldId.fromLegacyName(worldName))
        return initializePlots(areaId, worldName, config, progressCallback)
    }

    fun initializePlots(
        areaId: AreaId,
        worldName: String,
        config: PlotWorldConfig,
        progressCallback: ((Double) -> Unit)? = null
    ): java.util.concurrent.CompletableFuture<Int> {
        val future = java.util.concurrent.CompletableFuture<Int>()

        // Build database records asynchronously; world mutations remain on Paper's main thread.
        taskScheduler.runAsyncTask(0) {
            try {
                val count = initializePlotsSync(areaId, worldName, config, progressCallback)
                future.complete(count)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }

        return future
    }

    fun initializePlotsSync(
        worldName: String,
        config: PlotWorldConfig,
        progressCallback: ((Double) -> Unit)? = null
    ): Int = initializePlotsSync(
        AreaId.legacyDefault(WorldId.fromLegacyName(worldName)), worldName, config, progressCallback
    )

    fun initializePlotsSync(
        areaId: AreaId,
        worldName: String,
        config: PlotWorldConfig,
        progressCallback: ((Double) -> Unit)? = null
    ): Int = createPlotRegions(areaId, worldName, config, progressCallback)
    
    /**
     * Create all plot regions for a world.
     * This is the synchronous implementation.
     */
    private fun createPlotRegions(
        areaId: AreaId,
        worldName: String,
        config: PlotWorldConfig,
        progressCallback: ((Double) -> Unit)? = null
    ): Int {
        val cfg = config.sanitized()
        val grid = cfg.gridSize()
        val plotSize = cfg.plotSize
        val roadWidth = cfg.roadWidth
        
        // Calculate plot bounds based on road configuration
        val pathLower: Int
        val pathUpper: Int
        if (roadWidth == 0) {
            pathLower = -1
            pathUpper = -1
        } else if (roadWidth % 2 == 0) {
            pathLower = (roadWidth / 2) - 1
            pathUpper = pathLower + plotSize + 1
        } else {
            pathLower = roadWidth / 2
            pathUpper = pathLower + plotSize + 1
        }
        
        val plotsPerSide = cfg.plotsPerSide
        val startIndex = -(plotsPerSide / 2)
        val endExclusive = startIndex + plotsPerSide
        
        // Determine Y bounds (full height coverage)
        val minY = cfg.minGenHeight ?: -64
        val maxY = 319
        
        var count = 0
        val totalPlots = plotsPerSide * plotsPerSide
        var lastProgress = 0.0
        val existingBounds = regionService.listAllRegions()
            .asSequence()
            .filter { it.areaId == areaId }
            .map { boundsKey(it.bounds) }
            .toMutableSet()
        
        // Create plots in a grid pattern
        for (pz in startIndex until endExclusive) {
            for (px in startIndex until endExclusive) {
                // Calculate world coordinates for this plot
                val worldX = px * grid
                val worldZ = pz * grid
                
                // Calculate plot bounds (excluding roads)
                val minX = worldX + pathLower
                val maxX = worldX + pathUpper
                val minZ = worldZ + pathLower
                val maxZ = worldZ + pathUpper
                
                // Skip if this would be a road (no plot here)
                if (roadWidth > 0 && (minX >= maxX || minZ >= maxZ)) {
                    continue
                }
                
                val bounds = Bounds(
                    minX = minX,
                    maxX = maxX,
                    minY = minY,
                    maxY = maxY,
                    minZ = minZ,
                    maxZ = maxZ
                )
                
                val signature = boundsKey(bounds)
                if (existingBounds.contains(signature)) {
                    continue
                }

                // Create region for this plot
                val region = Region(
                    id = RegionId(UUID.randomUUID()),
                    world = worldName,
                    areaId = areaId,
                    shape = RegionShape.CUBOID,
                    bounds = bounds,
                    owner = UNCLAIMED_UUID,  // Unclaimed by default
                    roles = systems.diath.homeclaim.core.model.RegionRoles(),
                    flags = emptyMap(),
                    limits = emptyMap(),
                    metadata = mapOf("plotWorld" to "true"),
                    mergeGroupId = null,
                    price = 0.0  // Free to claim initially
                )

                regionService.createRegion(region, bounds)
                existingBounds.add(signature)
                count++
                
                // Report progress every 5%
                val progress = count.toDouble() / totalPlots
                if (progressCallback != null && progress - lastProgress >= 0.05) {
                    progressCallback(progress)
                    lastProgress = progress
                }
            }
        }
        
        // Final progress update
        progressCallback?.invoke(1.0)
        
        return count
    }

    private fun boundsKey(bounds: Bounds): String {
        return listOf(
            bounds.minX,
            bounds.maxX,
            bounds.minY,
            bounds.maxY,
            bounds.minZ,
            bounds.maxZ
        ).joinToString(":")
    }

    fun getOrCreatePlotArea(world: World, config: PlotWorldConfig): AreaId {
        val store = worldAreaService ?: return AreaId.legacyDefault(WorldId.fromLegacyName(world.name))
        val registeredWorld = store.getWorldByPlatformId(world.uid)
            ?: store.getWorldByName(world.name)?.let { store.bindPlatformWorld(it.id, world.uid, world.name) }
            ?: store.createWorld(world.name, world.uid)

        val existing = store.listAreas(registeredWorld.id)
        existing.firstOrNull { it.key == "legacy-default" }?.let { return it.id }
        existing.firstOrNull { it.key == "plots" }?.let { return it.id }

        val cfg = config.sanitized()
        val bounds = Bounds(
            minX = cfg.plotAreaMinCoordinate(),
            maxX = cfg.plotAreaMaxExclusive() - 1,
            minY = cfg.minGenHeight ?: -64,
            maxY = 319,
            minZ = cfg.plotAreaMinCoordinate(),
            maxZ = cfg.plotAreaMaxExclusive() - 1
        )
        if (cfg.plotsPerSide <= 0) {
            return store.createArea(Area(AreaId.newId(), registeredWorld.id, "plots", AreaScope.WholeWorld)).id
        }
        return store.createArea(
            Area(AreaId.newId(), registeredWorld.id, "plots", AreaScope.Finite(bounds))
        ).id
    }
}
