package systems.diath.homeclaim.platform.paper.plot

import com.fastasyncworldedit.core.FaweAPI
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.plugin.Plugin
import systems.diath.homeclaim.platform.paper.I18n
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/**
 * Bulk block setter using FastAsyncWorldEdit (FAWE).
 * FAWE is a required dependency for HomeClaim.
 */
@Suppress("DEPRECATION")
class BulkBlockSetter(private val plugin: Plugin) {
    
    private val i18n = I18n()
    
    /**
     * Check if FAWE is available.
     */
    fun isFaweAvailable(): Boolean {
        return try {
            val fawePlugin = plugin.server.pluginManager.getPlugin("FastAsyncWorldEdit")
            fawePlugin != null && fawePlugin.isEnabled
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get the FAWE version info.
     */
    fun getFaweVersion(): String {
        return try {
            val fawePlugin = plugin.server.pluginManager.getPlugin("FastAsyncWorldEdit")
            fawePlugin?.description?.version ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }
    
    /**
     * Set blocks in a region using FAWE's fast async operations.
     * 
     * @param world Target world
     * @param minX Minimum X coordinate
     * @param minY Minimum Y coordinate  
     * @param minZ Minimum Z coordinate
     * @param maxX Maximum X coordinate
     * @param maxY Maximum Y coordinate
     * @param maxZ Maximum Z coordinate
     * @param blockProvider Function that returns the Material for each position
     * @param progressCallback Optional callback for progress updates (0.0 - 1.0)
     * @return CompletableFuture that completes when all blocks are set
     */
    fun setBlocksAsync(
        world: World,
        minX: Int, minY: Int, minZ: Int,
        maxX: Int, maxY: Int, maxZ: Int,
        blockProvider: (x: Int, y: Int, z: Int) -> Material,
        progressCallback: Consumer<Double>? = null
    ): CompletableFuture<Int> {
        val future = CompletableFuture<Int>()
        
        CompletableFuture.runAsync {
            try {
                val faweWorld = FaweAPI.getWorld(world.name)
                    ?: throw IllegalStateException("FAWE world not found: ${world.name}")
                
                val editSession = WorldEdit.getInstance()
                    .newEditSessionBuilder()
                    .world(faweWorld)
                    .fastMode(true)
                    .build()
                
                var count = 0
                val totalBlocks = ((maxX - minX + 1).toLong() * (maxY - minY + 1) * (maxZ - minZ + 1))
                var processed = 0L
                
                editSession.use { session ->
                    for (x in minX..maxX) {
                        for (z in minZ..maxZ) {
                            for (y in minY..maxY) {
                                val material = blockProvider(x, y, z)
                                if (material != Material.AIR) {
                                    val blockType = BukkitAdapter.asBlockType(material)
                                    if (blockType != null) {
                                        session.setBlock(x, y, z, blockType.defaultState)
                                        count++
                                    }
                                }
                                processed++
                            }
                        }
                        // Progress update per X column
                        progressCallback?.accept(processed.toDouble() / totalBlocks)
                    }
                }
                
                future.complete(count)
            } catch (e: Exception) {
                plugin.logger.severe(i18n.msg("fawe.operation.failed", e.message ?: "unknown"))
                e.printStackTrace()
                future.completeExceptionally(e)
            }
        }
        
        return future
    }
    
    /**
     * Convert a plot world region using the plot generator logic.
     */
    fun convertRegionToPlots(
        world: World,
        config: PlotWorldConfig,
        centerX: Int = 0,
        centerZ: Int = 0,
        radiusInPlots: Int,
        progressCallback: Consumer<Double>? = null
    ): CompletableFuture<Int> {
        val gridSize = config.gridSize()
        val halfRadius = radiusInPlots * gridSize / 2
        
        val minX = centerX - halfRadius
        val maxX = centerX + halfRadius
        val minZ = centerZ - halfRadius
        val maxZ = centerZ + halfRadius
        val minY = world.minHeight
        val maxY = config.plotHeight + 1
        
        val generator = PlotWorldChunkGenerator(config)
        
        return setBlocksAsync(world, minX, minY, minZ, maxX, maxY, maxZ, { x, y, z ->
            generator.getBlockAt(x, y, z)
        }, progressCallback)
    }
}
