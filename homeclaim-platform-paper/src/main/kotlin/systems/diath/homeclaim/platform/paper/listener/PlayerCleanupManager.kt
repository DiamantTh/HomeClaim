package systems.diath.homeclaim.platform.paper.listener

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerKickEvent
import systems.diath.homeclaim.platform.paper.CombatTracker
import systems.diath.homeclaim.platform.paper.util.CommandRateLimiter
import systems.diath.homeclaim.platform.paper.util.SafeEventHandler
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/**
 * Central player cleanup manager to prevent memory leaks.
 * 
 * Registers cleanup handlers that are called when a player quits.
 * This ensures all player-related data is properly cleaned up.
 */
object PlayerCleanupManager : Listener {
    
    private val cleanupHandlers = ConcurrentHashMap<String, Consumer<UUID>>()
    
    /**
     * Register a cleanup handler for player data.
     * 
     * @param name Unique name for the handler (for logging)
     * @param handler Function that cleans up data for the given player UUID
     */
    fun registerCleanup(name: String, handler: Consumer<UUID>) {
        cleanupHandlers[name] = handler
    }
    
    /**
     * Unregister a cleanup handler.
     */
    fun unregisterCleanup(name: String) {
        cleanupHandlers.remove(name)
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerQuit(event: PlayerQuitEvent) = SafeEventHandler.handle(event, "PlayerCleanup.onQuit", failSafe = false) {
        cleanupPlayer(event.player)
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerKick(event: PlayerKickEvent) = SafeEventHandler.handle(event, "PlayerCleanup.onKick", failSafe = false) {
        cleanupPlayer(event.player)
    }
    
    private fun cleanupPlayer(player: Player) {
        val uuid = player.uniqueId
        
        // Built-in cleanups
        SafeEventHandler.runSafe("CombatTracker.cleanup") {
            CombatTracker.clearCombat(uuid)
        }
        
        SafeEventHandler.runSafe("CommandRateLimiter.cleanup") {
            CommandRateLimiter.clearPlayer(uuid)
        }
        
        // Custom registered cleanups
        cleanupHandlers.forEach { (name, handler) ->
            SafeEventHandler.runSafe("PlayerCleanup.$name") {
                handler.accept(uuid)
            }
        }
    }
    
    /**
     * Force cleanup for all registered handlers (e.g., on plugin disable).
     */
    fun cleanupAll(onlinePlayers: Collection<Player>) {
        onlinePlayers.forEach { player ->
            SafeEventHandler.runSafe("PlayerCleanup.all") {
                cleanupPlayer(player)
            }
        }
    }
}
