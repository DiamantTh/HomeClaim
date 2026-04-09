package systems.diath.homeclaim.core.platform

import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Position

data class BlockEventContext(
    val playerId: PlayerId,
    val position: Position,
    val blockType: String
)

data class InteractEventContext(
    val playerId: PlayerId,
    val position: Position,
    val targetType: String?
)
