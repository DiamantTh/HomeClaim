package systems.diath.homeclaim.platform.paper.gui

import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.ComponentType
import systems.diath.homeclaim.core.model.Position
import java.util.UUID

/**
 * Multi-step state for pad creation workflow.
 */
sealed class PadCreatorState {
    data class SelectType(val regionId: RegionId) : PadCreatorState()
    
    data class SelectBlock(
        val regionId: RegionId,
        val padType: ComponentType
    ) : PadCreatorState()
    
    data class Configure(
        val regionId: RegionId,
        val padType: ComponentType,
        val blockPos: Position,
        val mode: String = "NEAREST_PAD",
        val floorName: String = "Ground",
        val linkId: String = ""
    ) : PadCreatorState()
}

/**
 * Stores temporary creator state per player.
 */
object PadCreatorStateManager {
    private val playerStates = mutableMapOf<UUID, PadCreatorState>()
    
    fun getState(playerId: UUID): PadCreatorState? = playerStates[playerId]
    fun setState(playerId: UUID, state: PadCreatorState) { playerStates[playerId] = state }
    fun clearState(playerId: UUID) { playerStates.remove(playerId) }
}
