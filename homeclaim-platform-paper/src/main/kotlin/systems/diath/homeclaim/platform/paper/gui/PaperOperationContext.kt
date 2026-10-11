package systems.diath.homeclaim.platform.paper.gui

import org.bukkit.entity.Player
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.service.RegionMutationContext

internal fun Player.operationContext(eventName: String) = RegionMutationContext(
    actor = ActionActor.Player(uniqueId),
    platform = "paper",
    eventName = eventName
)

internal fun Player.showOperationFailure(detail: String?) {
    sendMessage("HomeClaim: ${detail ?: "Operation failed"}")
}
