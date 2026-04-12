package systems.diath.homeclaim.core.platform

import systems.diath.homeclaim.core.model.Component
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.policy.Action
import systems.diath.homeclaim.core.policy.ActorKind
import systems.diath.homeclaim.core.policy.Decision
import systems.diath.homeclaim.core.policy.PolicyActorContext
import systems.diath.homeclaim.core.service.ComponentService
import systems.diath.homeclaim.core.service.PolicyService

data class ComponentTriggerResult(
    val component: Component?,
    val decision: Decision?
)

data class ComponentTriggerRequest(
    val playerId: PlayerId,
    val position: Position,
    val actor: PolicyActorContext = PolicyActorContext(kind = ActorKind.PLAYER),
    val platform: String = "unknown",
    val extra: Map<String, Any?> = emptyMap()
)

class ComponentTriggerHandler(
    private val componentService: ComponentService,
    private val policyService: PolicyService
) {
    fun onTrigger(request: ComponentTriggerRequest): ComponentTriggerResult {
        val component = componentService.getComponentAt(request.position) ?: return ComponentTriggerResult(null, null)
        val decision = policyService.evaluateWithActor(
            playerId = request.playerId,
            action = Action.COMPONENT_USE,
            position = request.position,
            actor = request.actor,
            extraContext = mapOf(
                "componentId" to component.id,
                "regionId" to component.regionId,
                "platform" to request.platform,
                PolicyActorContext.EXTRA_KIND to request.actor.kind.name,
                PolicyActorContext.EXTRA_ID to request.actor.actorId,
                PolicyActorContext.EXTRA_SOURCE_MOD to request.actor.sourceMod
            ) + request.extra
        )
        return ComponentTriggerResult(component, decision)
    }

    fun onTrigger(playerId: PlayerId, position: Position): ComponentTriggerResult {
        return onTrigger(ComponentTriggerRequest(playerId = playerId, position = position))
    }
}
