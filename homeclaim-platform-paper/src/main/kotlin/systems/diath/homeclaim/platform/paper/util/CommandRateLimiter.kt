package systems.diath.homeclaim.platform.paper.util

import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Rate limiter for commands to prevent spam and abuse.
 * 
 * Features:
 * - Per-command cooldowns
 * - Global player cooldown
 * - Configurable bypass permission
 * - Memory-efficient cleanup
 */
object CommandRateLimiter {
    
    // Command name -> (Player UUID -> Last execution time)
    private val commandCooldowns = ConcurrentHashMap<String, ConcurrentHashMap<UUID, Long>>()
    
    // Global cooldown (all commands) per player
    private val globalCooldowns = ConcurrentHashMap<UUID, Long>()
    
    // Default cooldowns in milliseconds
    private const val DEFAULT_COMMAND_COOLDOWN_MS = 1000L   // 1 second
    private const val DEFAULT_GLOBAL_COOLDOWN_MS = 250L     // 250ms between any commands
    private const val EXPENSIVE_COMMAND_COOLDOWN_MS = 5000L // 5 seconds for expensive operations
    
    // Commands that should have longer cooldowns
    private val expensiveCommands = setOf(
        "claim", "auto", "buy", "sell", "merge", "create", "delete",
        "setup", "convert", "migrate", "reload"
    )
    
    /**
     * Permission to bypass rate limiting.
     */
    const val BYPASS_PERMISSION = "homeclaim.admin.bypass.ratelimit"
    
    /**
     * Check if a command can be executed (not rate limited).
     * 
     * @param player The player executing the command
     * @param command The command name (without /)
     * @return Pair of (allowed, remainingCooldownMs). If allowed is false, remainingCooldownMs shows time to wait.
     */
    fun checkAndRecord(player: Player, command: String): Pair<Boolean, Long> {
        // Admins bypass rate limiting
        if (player.hasPermission(BYPASS_PERMISSION)) {
            return true to 0L
        }
        
        val now = System.currentTimeMillis()
        val playerId = player.uniqueId
        val commandLower = command.lowercase()
        
        // Check global cooldown first
        val lastGlobal = globalCooldowns[playerId] ?: 0L
        val globalRemaining = (lastGlobal + DEFAULT_GLOBAL_COOLDOWN_MS) - now
        if (globalRemaining > 0) {
            return false to globalRemaining
        }
        
        // Check command-specific cooldown
        val cooldownMs = if (expensiveCommands.contains(commandLower)) {
            EXPENSIVE_COMMAND_COOLDOWN_MS
        } else {
            DEFAULT_COMMAND_COOLDOWN_MS
        }
        
        val commandMap = commandCooldowns.computeIfAbsent(commandLower) { ConcurrentHashMap() }
        val lastCommand = commandMap[playerId] ?: 0L
        val commandRemaining = (lastCommand + cooldownMs) - now
        
        if (commandRemaining > 0) {
            return false to commandRemaining
        }
        
        // Record this execution
        globalCooldowns[playerId] = now
        commandMap[playerId] = now
        
        return true to 0L
    }
    
    /**
     * Get remaining cooldown for a command in human-readable format.
     */
    fun getRemainingFormatted(remainingMs: Long): String {
        return if (remainingMs >= 1000) {
            String.format("%.1fs", remainingMs / 1000.0)
        } else {
            "${remainingMs}ms"
        }
    }
    
    /**
     * Clear cooldowns for a specific player (e.g., on quit).
     */
    fun clearPlayer(playerId: UUID) {
        globalCooldowns.remove(playerId)
        commandCooldowns.values.forEach { it.remove(playerId) }
    }
    
    /**
     * Cleanup expired cooldown entries (memory management).
     * Should be called periodically.
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        val maxAge = TimeUnit.MINUTES.toMillis(5)
        
        globalCooldowns.entries.removeIf { now - it.value > maxAge }
        
        commandCooldowns.values.forEach { commandMap ->
            commandMap.entries.removeIf { now - it.value > maxAge }
        }
        
        // Remove empty command maps
        commandCooldowns.entries.removeIf { it.value.isEmpty() }
    }
    
    /**
     * Set a custom cooldown for a specific command.
     */
    private val customCooldowns = ConcurrentHashMap<String, Long>()
    
    fun setCustomCooldown(command: String, cooldownMs: Long) {
        customCooldowns[command.lowercase()] = cooldownMs
    }
    
    /**
     * Reset all cooldowns (for testing or admin purposes).
     */
    fun resetAll() {
        globalCooldowns.clear()
        commandCooldowns.clear()
    }
}
