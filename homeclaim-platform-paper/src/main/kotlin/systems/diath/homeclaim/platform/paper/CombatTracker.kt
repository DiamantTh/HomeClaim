package systems.diath.homeclaim.platform.paper

import org.bukkit.entity.Player
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks player combat status for combat blocking teleports.
 */
object CombatTracker {
    private val combatLog = ConcurrentHashMap<UUID, Instant>()
    private const val COMBAT_DURATION_MS = 10_000L
    
    fun markInCombat(player: Player, durationMs: Long = COMBAT_DURATION_MS) {
        combatLog[player.uniqueId] = Instant.now().plusMillis(durationMs)
    }
    
    fun isInCombat(player: Player): Boolean {
        val until = combatLog[player.uniqueId] ?: return false
        if (Instant.now().isAfter(until)) {
            combatLog.remove(player.uniqueId)
            return false
        }
        return true
    }
    
    fun clearCombat(player: Player) {
        combatLog.remove(player.uniqueId)
    }
    
    fun clearCombat(playerId: UUID) {
        combatLog.remove(playerId)
    }
    
    fun getRemainingCombatMs(player: Player): Long {
        val until = combatLog[player.uniqueId] ?: return 0
        val remaining = until.toEpochMilli() - Instant.now().toEpochMilli()
        return if (remaining > 0) remaining else 0
    }
}
