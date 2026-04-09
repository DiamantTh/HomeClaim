package systems.diath.homeclaim.platform.paper.plot

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.plugin.Plugin
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionShape
import systems.diath.homeclaim.core.service.RegionService
import java.util.UUID

/**
 * Initializes plot regions in the database for a plot world.
 * This must be called after world creation to register all plots in the database.
 */
class PlotWorldInitializer(
    private val regionService: RegionService,
    private val plugin: Plugin
) {
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
        val future = java.util.concurrent.CompletableFuture<Int>()
        
        // Run async to avoid blocking main thread
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                val count = createPlotRegions(world.name, config, progressCallback)
                future.complete(count)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        })
        
        return future
    }
    
    /**
     * Create all plot regions for a world.
     * This is the synchronous implementation.
     */
    private fun createPlotRegions(
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
        val halfPlots = plotsPerSide / 2
        
        // Determine Y bounds (full height coverage)
        val minY = cfg.minGenHeight ?: -64
        val maxY = 319
        
        var count = 0
        val totalPlots = plotsPerSide * plotsPerSide
        var lastProgress = 0.0
        
        // Create plots in a grid pattern
        for (pz in -halfPlots until halfPlots) {
            for (px in -halfPlots until halfPlots) {
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
                
                // Create region for this plot
                val region = Region(
                    id = RegionId(UUID.randomUUID()),
                    world = worldName,
                    shape = RegionShape.CUBOID,
                    bounds = bounds,
                    owner = UNCLAIMED_UUID,  // Unclaimed by default
                    roles = systems.diath.homeclaim.core.model.RegionRoles(),
                    flags = emptyMap(),
                    limits = emptyMap(),
                    metadata = emptyMap(),
                    mergeGroupId = null,
                    price = 0.0  // Free to claim initially
                )
                
                regionService.createRegion(region, bounds)
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
}
