package systems.diath.homeclaim.platform.paper.effect

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionShape
import kotlin.math.abs

/**
 * Region visualization using particles.
 * Draws the boundaries of a region with particle effects.
 * 
 * Features:
 * - Efficient chunk-based rendering
 * - Configurable particle type and density
 * - Vertical line rendering for 3D visualization
 * - Auto-updates when player moves
 */
class RegionVisualizerImpl(
    private val plugin: Plugin
) {
    
    private val activeVisualizations = mutableMapOf<String, RegionVisualization>()
    
    /**
     * Start visualizing a region for a player
     * @param player Player to show visualization for
     * @param region Region to visualize
     * @param particleType Type of particle to use (default: DUST or REDSTONE)
     * @param density Particle density per block (default: 1)
     */
    fun startVisualization(
        player: Player,
        region: Region,
        particleType: Particle = Particle.DUST,
        density: Double = 0.5
    ) {
        val key = "${player.uniqueId}_${region.id}"
        
        // Stop existing visualization
        activeVisualizations[key]?.stop()
        
        // Create and start new visualization
        val viz = RegionVisualization(plugin, player, region, particleType, density)
        viz.start()
        activeVisualizations[key] = viz
    }
    
    /**
     * Stop visualizing a region for a player
     */
    fun stopVisualization(playerId: java.util.UUID, regionId: String) {
        val key = "${playerId}_$regionId"
        activeVisualizations.remove(key)?.stop()
    }
    
    /**
     * Stop all visualizations for a player
     */
    fun stopAllVisualizations(playerId: java.util.UUID) {
        activeVisualizations.keys.filter { it.startsWith("$playerId") }
            .forEach { key ->
                activeVisualizations.remove(key)?.stop()
            }
    }
    
    /**
     * Stop all active visualizations
     */
    fun stopAll() {
        activeVisualizations.values.forEach { it.stop() }
        activeVisualizations.clear()
    }
}

/**
 * Single region visualization
 */
class RegionVisualization(
    private val plugin: Plugin,
    private val player: Player,
    private val region: Region,
    private val particleType: Particle,
    private val density: Double
) {
    
    private var task: Any? = null  // BukkitTask or ScheduledTask
    private var lastPlayerPos: Location? = null
    
    private fun isFolia(): Boolean {
        return try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
    
    fun start() {
        // Update visualization every 20 ticks (1 second)
        if (isFolia()) {
            // Folia: Use EntityScheduler for player-specific tasks
            task = player.scheduler.runAtFixedRate(plugin, { _ ->
                // Only render if player is in the same world and within render distance
                if (player.world.name != region.world) return@runAtFixedRate
                
                val currentPos = player.location
                val lastPos = lastPlayerPos
                
                // Only update if player moved significantly (more than 5 blocks)
                if (lastPos == null || currentPos.distance(lastPos) > 5.0) {
                    renderRegionBoundary()
                    lastPlayerPos = currentPos
                }
            }, null, 1, 20)  // 1 tick initial, 20 ticks between updates
        } else {
            // Paper/Spigot: Use BukkitScheduler
            task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
                // Only render if player is in the same world and within render distance
                if (player.world.name != region.world) return@Runnable
                
                val currentPos = player.location
                val lastPos = lastPlayerPos
                
                // Only update if player moved significantly (more than 5 blocks)
                if (lastPos == null || currentPos.distance(lastPos) > 5.0) {
                    renderRegionBoundary()
                    lastPlayerPos = currentPos
                }
            }, 0, 20)  // 0 delay, 20 ticks between updates
        }
    }
    
    fun stop() {
        when (task) {
            is BukkitTask -> (task as BukkitTask).cancel()
            is io.papermc.paper.threadedregions.scheduler.ScheduledTask -> (task as io.papermc.paper.threadedregions.scheduler.ScheduledTask).cancel()
        }
        task = null
    }
    
    private fun renderRegionBoundary() {
        val world = Bukkit.getWorld(region.world) ?: return
        
        // Get region bounds
        var minX = region.bounds.minX
        var minZ = region.bounds.minZ
        var maxX = region.bounds.maxX
        var maxZ = region.bounds.maxZ
        val minY = region.bounds.minY
        val maxY = region.bounds.maxY

        // PLOT_GRID: render symmetric borders like P2 (one block outside plot area)
        if (region.shape == RegionShape.PLOT_GRID) {
            minX -= 1
            minZ -= 1
            maxX += 1
            maxZ += 1
        }
        
        val playerY = player.location.y
        
        // Render corners and edges
        // Southwest corner (min-min)
        drawVerticalLine(minX, minZ, minY, maxY, world)
        // Northwest corner (min-max)
        drawVerticalLine(minX, maxZ, minY, maxY, world)
        // Southeast corner (max-min)
        drawVerticalLine(maxX, minZ, minY, maxY, world)
        // Northeast corner (max-max)
        drawVerticalLine(maxX, maxZ, minY, maxY, world)
        
        // Draw horizontal lines along edges (at player height for visibility)
        val lineY = playerY.toInt().coerceIn(minY, maxY)
        
        // North edge (min-Z to max-Z, min-X)
        drawHorizontalLine(minX, minZ, minX, maxZ, lineY, world)
        // South edge (min-Z to max-Z, max-X)
        drawHorizontalLine(maxX, minZ, maxX, maxZ, lineY, world)
        // East edge (min-X to max-X, min-Z)
        drawHorizontalLine(minX, minZ, maxX, minZ, lineY, world)
        // West edge (min-X to max-X, max-Z)
        drawHorizontalLine(minX, maxZ, maxX, maxZ, lineY, world)
    }
    
    private fun drawVerticalLine(x: Int, z: Int, minY: Int, maxY: Int, world: org.bukkit.World) {
        for (y in minY..maxY step (1.0 / density).toInt().coerceAtLeast(1)) {
            val loc = Location(world, x + 0.5, y.toDouble(), z + 0.5)
            
            // Only show particles near player
            if (loc.distance(player.location) <= 64.0) {
                world.spawnParticle(
                    Particle.DUST, loc, 1, 
                    org.bukkit.Particle.DustOptions(org.bukkit.Color.RED, 1f)
                )
            }
        }
    }
    
    private fun drawHorizontalLine(x1: Int, z1: Int, x2: Int, z2: Int, y: Int, world: org.bukkit.World) {
        val steps = (abs(x2 - x1) + abs(z2 - z1)).coerceAtLeast(1)
        val stepCount = (steps * density).toInt().coerceAtLeast(1)
        
        for (i in 0..stepCount) {
            val t = if (stepCount > 0) i.toDouble() / stepCount else 0.0
            val x = x1 + (x2 - x1) * t
            val z = z1 + (z2 - z1) * t
            
            val loc = Location(world, x + 0.5, y.toDouble() + 0.5, z + 0.5)
            
            // Only show particles near player
            if (loc.distance(player.location) <= 64.0) {
                world.spawnParticle(
                    Particle.DUST, loc, 1,
                    org.bukkit.Particle.DustOptions(org.bukkit.Color.GREEN, 1f)
                )
            }
        }
    }
}
