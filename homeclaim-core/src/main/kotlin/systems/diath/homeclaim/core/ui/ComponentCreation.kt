package systems.diath.homeclaim.core.ui

import systems.diath.homeclaim.core.model.ComponentType
import java.util.UUID

/**
 * State machine for component (pad/elevator) creation.
 * Platform-agnostic - works with any UI implementation.
 */
sealed class ComponentCreationState {
    abstract val creatorId: UUID
    abstract val regionId: String
    abstract val startTime: Long
    
    /**
     * Select component type (elevator, teleport, etc.)
     */
    data class SelectType(
        override val creatorId: UUID,
        override val regionId: String,
        override val startTime: Long = System.currentTimeMillis()
    ) : ComponentCreationState()
    
    /**
     * Select block location for component.
     */
    data class SelectBlock(
        override val creatorId: UUID,
        override val regionId: String,
        val componentType: ComponentType,
        override val startTime: Long = System.currentTimeMillis()
    ) : ComponentCreationState()
    
    /**
     * Configure component details.
     */
    data class Configure(
        override val creatorId: UUID,
        override val regionId: String,
        val componentType: ComponentType,
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
        val floorName: String = "",
        val linkId: String? = null,
        override val startTime: Long = System.currentTimeMillis()
    ) : ComponentCreationState()
    
    /**
     * Confirm and save component.
     */
    data class Confirm(
        override val creatorId: UUID,
        override val regionId: String,
        val componentType: ComponentType,
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
        val floorName: String,
        val linkId: String? = null,
        override val startTime: Long = System.currentTimeMillis()
    ) : ComponentCreationState()
}

/**
 * Component creation event for event-driven state transitions.
 */
sealed class ComponentCreationEvent {
    abstract val creatorId: UUID
    
    data class TypeSelected(
        override val creatorId: UUID,
        val componentType: ComponentType
    ) : ComponentCreationEvent()
    
    data class BlockSelected(
        override val creatorId: UUID,
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int
    ) : ComponentCreationEvent()
    
    data class FloorNameSet(
        override val creatorId: UUID,
        val floorName: String
    ) : ComponentCreationEvent()
    
    data class LinkIdSet(
        override val creatorId: UUID,
        val linkId: String?
    ) : ComponentCreationEvent()
    
    data class ConfirmRequested(
        override val creatorId: UUID
    ) : ComponentCreationEvent()
    
    data class CancelRequested(
        override val creatorId: UUID
    ) : ComponentCreationEvent()
}

/**
 * Component creation service - manages state machine transitions.
 */
interface ComponentCreationService {
    
    /**
     * Start component creation for a player in a region.
     */
    fun startCreation(creatorId: UUID, regionId: String): ComponentCreationState?
    
    /**
     * Get current creation state for player.
     */
    fun getState(creatorId: UUID): ComponentCreationState?
    
    /**
     * Handle creation event - transitions state based on event.
     */
    fun handleEvent(event: ComponentCreationEvent): ComponentCreationState?
    
    /**
     * Cancel creation for player.
     */
    fun cancelCreation(creatorId: UUID)
    
    /**
     * Get timeout in milliseconds for creation (default 5 minutes).
     */
    fun getCreationTimeout(): Long = 5 * 60 * 1000
}

/**
 * In-memory component creation service implementation.
 */
class InMemoryComponentCreationService : ComponentCreationService {
    private val states = mutableMapOf<UUID, ComponentCreationState>()
    
    override fun startCreation(creatorId: UUID, regionId: String): ComponentCreationState? {
        val state = ComponentCreationState.SelectType(creatorId, regionId)
        states[creatorId] = state
        return state
    }
    
    override fun getState(creatorId: UUID): ComponentCreationState? {
        val state = states[creatorId]
        
        // Check timeout
        if (state != null && System.currentTimeMillis() - state.startTime > getCreationTimeout()) {
            states.remove(creatorId)
            return null
        }
        
        return state
    }
    
    override fun handleEvent(event: ComponentCreationEvent): ComponentCreationState? {
        val creatorId = event.creatorId
        val currentState = getState(creatorId) ?: return null
        
        val newState = when {
            event is ComponentCreationEvent.TypeSelected && currentState is ComponentCreationState.SelectType -> {
                ComponentCreationState.SelectBlock(creatorId, currentState.regionId, event.componentType)
            }
            event is ComponentCreationEvent.BlockSelected && currentState is ComponentCreationState.SelectBlock -> {
                ComponentCreationState.Configure(
                    creatorId = creatorId,
                    regionId = currentState.regionId,
                    componentType = currentState.componentType,
                    world = event.world,
                    x = event.x,
                    y = event.y,
                    z = event.z
                )
            }
            event is ComponentCreationEvent.FloorNameSet && currentState is ComponentCreationState.Configure -> {
                currentState.copy(floorName = event.floorName)
            }
            event is ComponentCreationEvent.LinkIdSet && currentState is ComponentCreationState.Configure -> {
                currentState.copy(linkId = event.linkId)
            }
            event is ComponentCreationEvent.ConfirmRequested && currentState is ComponentCreationState.Configure -> {
                ComponentCreationState.Confirm(
                    creatorId = creatorId,
                    regionId = currentState.regionId,
                    componentType = currentState.componentType,
                    world = currentState.world,
                    x = currentState.x,
                    y = currentState.y,
                    z = currentState.z,
                    floorName = currentState.floorName,
                    linkId = currentState.linkId
                )
            }
            event is ComponentCreationEvent.CancelRequested -> {
                states.remove(creatorId)
                return null
            }
            else -> currentState
        }
        
        states[creatorId] = newState
        return newState
    }
    
    override fun cancelCreation(creatorId: UUID) {
        states.remove(creatorId)
    }
}
