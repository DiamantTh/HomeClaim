package systems.diath.homeclaim.core.platform

import systems.diath.homeclaim.core.model.Component
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.policy.Action
import systems.diath.homeclaim.core.policy.Decision
import systems.diath.homeclaim.core.service.ComponentService
import systems.diath.homeclaim.core.service.PolicyService

data class ComponentTriggerResult(
    val component: Component?,
    val decision: Decision?
)

class ComponentTriggerHandler(
    private val componentService: ComponentService,
    private val policyService: PolicyService
) {
    fun onTrigger(playerId: PlayerId, position: Position): ComponentTriggerResult {
        val component = componentService.getComponentAt(position) ?: return ComponentTriggerResult(null, null)
        val decision = policyService.evaluate(
            playerId = playerId,
            action = Action.COMPONENT_USE,
            position = position,
            extraContext = mapOf(
                "componentId" to component.id,
                "regionId" to component.regionId
            )
        )
        return ComponentTriggerResult(component, decision)
    }
}
