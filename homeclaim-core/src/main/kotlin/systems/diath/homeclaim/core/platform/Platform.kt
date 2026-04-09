package systems.diath.homeclaim.core.platform

import java.util.UUID

/**
 * Platform-agnostic player abstraction.
 */
interface Player {
    val id: UUID
    val name: String
    val location: Location
    val isOnline: Boolean
    
    /**
     * Send message to player.
     */
    fun sendMessage(message: String)
    
    /**
     * Check if player has permission.
     */
    fun hasPermission(permission: String): Boolean
    
    /**
     * Get player's balance (economy integration).
     */
    fun getBalance(): Long?
    
    /**
     * Teleport player to location.
     */
    fun teleport(location: Location): Boolean
    
    /**
     * Get player's current world.
     */
    fun getWorld(): String
    
    /**
     * Check if player is in specific region.
     */
    fun isInRegion(regionId: String): Boolean
}

/**
 * World/Location abstraction.
 */
data class Location(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val yaw: Float = 0f,
    val pitch: Float = 0f
) {
    /**
     * Distance to another location (same world only).
     */
    fun distanceTo(other: Location): Double {
        if (this.world != other.world) return Double.POSITIVE_INFINITY
        
        val dx = (this.x - other.x).toDouble()
        val dy = (this.y - other.y).toDouble()
        val dz = (this.z - other.z).toDouble()
        
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }
    
    /**
     * Check if in same world.
     */
    fun isSameWorld(other: Location): Boolean = this.world == other.world
    
    /**
     * Block coordinates as string.
     */
    override fun toString(): String = "$world:$x,$y,$z"
}

/**
 * Block abstraction.
 */
data class Block(
    val location: Location,
    val type: String,  // "STONE", "REDSTONE_LAMP", etc.
    val data: Map<String, Any> = emptyMap()  // power level, orientation, etc.
) {
    /**
     * Is block solid/passable.
     */
    fun isPassable(): Boolean {
        return type.uppercase() in listOf(
            "AIR", "CAVE_AIR", "VOID_AIR", "WATER", "LAVA"
        )
    }
}

/**
 * World abstraction.
 */
interface World {
    val name: String
    
    /**
     * Get block at location.
     */
    fun getBlock(x: Int, y: Int, z: Int): Block?
    
    /**
     * Set block at location.
     */
    fun setBlock(x: Int, y: Int, z: Int, type: String): Boolean
    
    /**
     * Get all players in world.
     */
    fun getPlayers(): List<Player>
    
    /**
     * Get player by UUID.
     */
    fun getPlayer(id: UUID): Player?
    
    /**
     * Broadcast message to all players in world.
     */
    fun broadcastMessage(message: String)
}

/**
 * Provider interface for platform-specific implementations.
 */
interface PlatformProvider {
    
    /**
     * Get player by UUID.
     */
    fun getPlayer(id: UUID): Player?
    
    /**
     * Get all online players.
     */
    fun getOnlinePlayers(): List<Player>
    
    /**
     * Get world by name.
     */
    fun getWorld(name: String): World?
    
    /**
     * Get all worlds.
     */
    fun getWorlds(): List<World>
    
    /**
     * Schedule async task (for DB operations, etc).
     */
    fun runAsyncTask(task: () -> Unit)
    
    /**
     * Schedule sync task (for block updates, etc).
     */
    fun runSyncTask(task: () -> Unit)
}

/**
 * Null provider for testing/headless operation.
 */
class NullPlatformProvider : PlatformProvider {
    override fun getPlayer(id: UUID): Player? = null
    override fun getOnlinePlayers(): List<Player> = emptyList()
    override fun getWorld(name: String): World? = null
    override fun getWorlds(): List<World> = emptyList()
    override fun runAsyncTask(task: () -> Unit) { task() }
    override fun runSyncTask(task: () -> Unit) { task() }
}
