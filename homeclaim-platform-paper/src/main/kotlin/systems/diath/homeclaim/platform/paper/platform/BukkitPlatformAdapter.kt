package systems.diath.homeclaim.platform.paper.platform

import org.bukkit.Bukkit
import org.bukkit.entity.Player as BukkitPlayer
import systems.diath.homeclaim.core.platform.Player
import systems.diath.homeclaim.core.platform.Location
import systems.diath.homeclaim.core.platform.World
import systems.diath.homeclaim.core.platform.Block
import systems.diath.homeclaim.core.platform.PlatformProvider
import java.util.UUID

/**
 * Bukkit implementation of Player interface.
 */
class BukkitPlayerAdapter(private val player: BukkitPlayer) : Player {
    override val id: UUID = player.uniqueId
    override val name: String = player.name
    override val location: Location
        get() {
            val loc = player.location
            return Location(
                world = loc.world?.name ?: "unknown",
                x = loc.blockX,
                y = loc.blockY,
                z = loc.blockZ,
                yaw = loc.yaw,
                pitch = loc.pitch
            )
        }
    override val isOnline: Boolean
        get() = player.isOnline
    
    override fun sendMessage(message: String) {
        player.sendMessage(message)
    }
    
    override fun hasPermission(permission: String): Boolean {
        return player.hasPermission(permission)
    }
    
    override fun getBalance(): Long? {
        // Could integrate with economy plugin
        return null
    }
    
    override fun teleport(location: Location): Boolean {
        val world = Bukkit.getWorld(location.world) ?: return false
        val bukkitLoc = org.bukkit.Location(
            world,
            location.x.toDouble() + 0.5,
            location.y.toDouble(),
            location.z.toDouble() + 0.5,
            location.yaw,
            location.pitch
        )
        player.teleportAsync(bukkitLoc)
        return true
    }
    
    override fun getWorld(): String {
        return player.world.name
    }
    
    override fun isInRegion(regionId: String): Boolean {
        // Would check against region service
        return false
    }
}

/**
 * Bukkit implementation of World interface.
 */
class BukkitWorldAdapter(private val world: org.bukkit.World) : World {
    override val name: String = world.name
    
    override fun getBlock(x: Int, y: Int, z: Int): Block? {
        try {
            val bukkitBlock = world.getBlockAt(x, y, z)
            return Block(
                location = Location(world.name, x, y, z),
                type = bukkitBlock.type.name,
                data = mapOf("power" to (bukkitBlock.blockData.toString()))
            )
        } catch (e: Exception) {
            return null
        }
    }
    
    override fun setBlock(x: Int, y: Int, z: Int, type: String): Boolean {
        try {
            val material = org.bukkit.Material.valueOf(type.uppercase())
            world.getBlockAt(x, y, z).type = material
            return true
        } catch (e: Exception) {
            return false
        }
    }
    
    override fun getPlayers(): List<Player> {
        return world.players.map { BukkitPlayerAdapter(it) }
    }
    
    override fun getPlayer(id: UUID): Player? {
        val player = Bukkit.getPlayer(id) ?: return null
        if (player.world.name != world.name) return null
        return BukkitPlayerAdapter(player)
    }
    
    override fun broadcastMessage(message: String) {
        world.players.forEach { it.sendMessage(message) }
    }
}

/**
 * Bukkit implementation of PlatformProvider.
 */
class BukkitPlatformProvider(private val plugin: org.bukkit.plugin.Plugin) : PlatformProvider {
    override fun getPlayer(id: UUID): Player? {
        val player = Bukkit.getPlayer(id) ?: return null
        return BukkitPlayerAdapter(player)
    }
    
    override fun getOnlinePlayers(): List<Player> {
        return Bukkit.getOnlinePlayers().map { BukkitPlayerAdapter(it) }
    }
    
    override fun getWorld(name: String): World? {
        val world = Bukkit.getWorld(name) ?: return null
        return BukkitWorldAdapter(world)
    }
    
    override fun getWorlds(): List<World> {
        return Bukkit.getWorlds().map { BukkitWorldAdapter(it) }
    }
    
    override fun runAsyncTask(task: () -> Unit) {
        if (isFolia()) {
            Bukkit.getAsyncScheduler().runNow(plugin) { _ -> task() }
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable { task() })
        }
    }
    
    override fun runSyncTask(task: () -> Unit) {
        if (isFolia()) {
            Bukkit.getGlobalRegionScheduler().run(plugin) { _ -> task() }
        } else {
            Bukkit.getScheduler().runTask(plugin, Runnable { task() })
        }
    }
    
    private fun isFolia(): Boolean {
        return try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
}
