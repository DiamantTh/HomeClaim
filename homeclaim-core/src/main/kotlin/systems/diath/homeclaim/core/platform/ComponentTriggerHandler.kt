package systems.diath.homeclaim.core.platform

import systems.diath.homeclaim.core.model.Component
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.policy.Action
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.ActionContext
import systems.diath.homeclaim.core.policy.ActionEndpoint
import systems.diath.homeclaim.core.policy.ActionRequest
import systems.diath.homeclaim.core.policy.ActionResourceRef
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
    private val policyService: PolicyService,
    private val policyGuard: PolicyGuard? = null
) {
    fun onTrigger(request: ComponentTriggerRequest): ComponentTriggerResult = onTrigger(request, component = null)

    /** Reuses an already resolved component from the platform adapter when available. */
    fun onTrigger(request: ComponentTriggerRequest, component: Component?): ComponentTriggerResult {
        val resolvedComponent = component ?: componentService.getComponentAt(request.position)
            ?: return ComponentTriggerResult(null, null)
        val decision = if (policyGuard != null) {
            policyGuard.check(
                ActionRequest(
                    actor = request.actor.toActionActor(request.playerId),
                    actionKey = Action.COMPONENT_USE.key,
                    target = ActionEndpoint(request.position, ActionResourceRef.Component(resolvedComponent.id)),
                    regionId = resolvedComponent.regionId,
                    context = ActionContext(
                        platform = request.platform,
                        componentId = resolvedComponent.id,
                        legacyValues = request.extra
                    )
                )
            )
        } else {
            // Compatibility path for embedders that have not supplied a spatial PolicyGuard.
            policyService.evaluateWithActor(
                playerId = request.playerId,
                action = Action.COMPONENT_USE,
                position = request.position,
                actor = request.actor,
                extraContext = mapOf(
                    "componentId" to resolvedComponent.id,
                    "regionId" to resolvedComponent.regionId,
                    "platform" to request.platform,
                    PolicyActorContext.EXTRA_KIND to request.actor.kind.name,
                    PolicyActorContext.EXTRA_ID to request.actor.actorId,
                    PolicyActorContext.EXTRA_SOURCE_MOD to request.actor.sourceMod
                ) + request.extra
            )
        }
        return ComponentTriggerResult(resolvedComponent, decision)
    }

    fun onTrigger(playerId: PlayerId, position: Position): ComponentTriggerResult {
        return onTrigger(ComponentTriggerRequest(playerId = playerId, position = position))
    }
}

private fun PolicyActorContext.toActionActor(playerId: PlayerId): ActionActor = when (kind) {
    ActorKind.PLAYER -> ActionActor.Player(playerId)
    ActorKind.FAKE_PLAYER -> ActionActor.Automation(actorId ?: "legacy-fake-player", sourceMod)
    ActorKind.ENTITY -> actorId?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
        ?.let { ActionActor.Entity(it, "unknown") } ?: ActionActor.System("legacy-entity")
    ActorKind.AUTOMATION -> ActionActor.Automation(actorId ?: "automation", sourceMod)
    ActorKind.SERVER_TASK -> ActionActor.System(actorId ?: "server-task")
}
