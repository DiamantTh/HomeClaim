package systems.diath.homeclaim.core.event

import java.util.UUID

/**
 * Events related to lift/elevator systems.
 * Platform-agnostic for multi-platform support.
 */

/**
 * Event result for lift events (can be cancelled, modified, or forced).
 */
enum class LiftEventResult {
    ALLOW,      // Allow lift operation
    DENY,       // Block lift operation
    FORCE       // Force lift operation regardless of checks
}

/**
 * Base class for all lift events.
 */
abstract class LiftEvent(
    val playerId: UUID,
    val componentId: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    var eventResult: LiftEventResult = LiftEventResult.ALLOW
}

/**
 * Fired when a player attempts to use a lift/elevator.
 */
data class LiftUsageEvent(
    val userId: UUID,
    val liftId: String,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int
) : LiftEvent(userId, liftId)

/**
 * Fired after lift usage is confirmed.
 */
data class PostLiftUsageEvent(
    val userId: UUID,
    val liftId: String,
    val destinationFloor: String,
    val success: Boolean,
    val errorMessage: String? = null
) : LiftEvent(userId, liftId)

/**
 * Fired when a lift/elevator is created or updated.
 */
data class LiftUpdateEvent(
    val liftId: String,
    val regionId: String,
    val componentType: String,
    val action: String  // "create", "update", "delete"
) : LiftEvent(UUID(0, 0), liftId)

/**
 * Fired when a player tries to teleport via lift.
 */
data class LiftTeleportEvent(
    val userId: UUID,
    val liftId: String,
    val fromFloor: String,
    val toFloor: String,
    val destinationX: Int,
    val destinationY: Int,
    val destinationZ: Int
) : LiftEvent(userId, liftId) {
    var isCancelled: Boolean = false
    var teleportX: Int = destinationX
    var teleportY: Int = destinationY
    var teleportZ: Int = destinationZ
}

/**
 * Service for dispatching lift events.
 */
interface LiftEventDispatcher {
    
    /**
     * Register a lift event listener.
     */
    fun registerListener(listener: LiftEventListener)
    
    /**
     * Dispatch a lift usage event (can be cancelled).
     */
    fun dispatchUsage(event: LiftUsageEvent): LiftEventResult
    
    /**
     * Dispatch a lift teleport event (can be modified).
     */
    fun dispatchTeleport(event: LiftTeleportEvent): Boolean  // true = allowed
    
    /**
     * Dispatch a lift update event (audit trail).
     */
    fun dispatchUpdate(event: LiftUpdateEvent)
    
    /**
     * Dispatch post-lift usage event (audit trail).
     */
    fun dispatchPostUsage(event: PostLiftUsageEvent)
}

/**
 * Listener interface for lift events.
 */
interface LiftEventListener {
    fun onLiftUsage(event: LiftUsageEvent) {}
    fun onLiftTeleport(event: LiftTeleportEvent) {}
    fun onLiftUpdate(event: LiftUpdateEvent) {}
    fun onPostLiftUsage(event: PostLiftUsageEvent) {}
}

/**
 * Reflection-based lift event dispatcher (mirrors EventDispatcher).
 */
class SimpleLiftEventDispatcher : LiftEventDispatcher {
    private val listeners = mutableListOf<LiftEventListener>()
    
    override fun registerListener(listener: LiftEventListener) {
        listeners.add(listener)
    }
    
    override fun dispatchUsage(event: LiftUsageEvent): LiftEventResult {
        listeners.forEach { it.onLiftUsage(event) }
        return event.eventResult
    }
    
    override fun dispatchTeleport(event: LiftTeleportEvent): Boolean {
        listeners.forEach { it.onLiftTeleport(event) }
        return !event.isCancelled
    }
    
    override fun dispatchUpdate(event: LiftUpdateEvent) {
        listeners.forEach { it.onLiftUpdate(event) }
    }
    
    override fun dispatchPostUsage(event: PostLiftUsageEvent) {
        listeners.forEach { it.onPostLiftUsage(event) }
    }
}
