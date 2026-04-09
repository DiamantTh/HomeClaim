package systems.diath.homeclaim.client.event

/**
 * Base class for all HomeClaim events
 */
abstract class HomeclaimEvent {
    var cancelled: Boolean = false
        private set
    
    fun setCancelled(cancel: Boolean) {
        this.cancelled = cancel
    }
}

/**
 * Result of event processing
 */
data class EventResult(
    val success: Boolean,
    val message: String = "",
    val data: Map<String, Any> = emptyMap()
)
