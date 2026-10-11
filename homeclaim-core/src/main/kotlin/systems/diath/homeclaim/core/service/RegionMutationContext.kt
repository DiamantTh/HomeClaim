package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.ActionContext
import systems.diath.homeclaim.core.policy.ActionEndpoint
import systems.diath.homeclaim.core.policy.ActionKey
import systems.diath.homeclaim.core.policy.ActionResourceRef
import systems.diath.homeclaim.core.policy.ActionRequest
import systems.diath.homeclaim.core.policy.CorrelationId

enum class RegionMutationCapability {
    ROLE_CHANGE,
    ENTRY_DENY_CHANGE,
    FLAG_CHANGE,
    LIMIT_CHANGE,
    PROFILE_CHANGE,
    COMPONENT_CHANGE,
    PRICE_CHANGE,
    METADATA_CHANGE,
    GLOBAL_PROFILE_CHANGE
}

/**
 * Platform-neutral authorization boundary for administrative domain mutations.
 * Platform adapters supply their capability lookup; the core owns the operation flow.
 */
fun interface RegionMutationAuthorizer {
    fun isAuthorized(actor: ActionActor, region: Region?, capability: RegionMutationCapability): Boolean

    companion object {
        /** Safe default for embedders: only explicit server actors may use operations. */
        val SYSTEM_ONLY = RegionMutationAuthorizer { actor, _, _ -> actor is ActionActor.System }
    }
}

data class RegionMutationContext(
    val actor: ActionActor,
    val platform: String,
    val eventName: String,
    val correlationId: CorrelationId = CorrelationId.newId(),
    val parentCorrelationId: CorrelationId? = null,
    val attributes: Map<String, String> = emptyMap()
) {
    fun actionRequest(
        action: ActionKey,
        regionId: RegionId?,
        region: Region? = null,
        attributes: Map<String, String> = emptyMap(),
        target: ActionEndpoint? = null
    ): ActionRequest = ActionRequest(
        actor = actor,
        actionKey = action,
        target = target ?: regionId?.let {
            ActionEndpoint(
                resource = ActionResourceRef.Region(it),
                areaId = region?.areaId
            )
        },
        regionId = regionId,
        areaId = region?.areaId,
        correlationId = correlationId,
        parentCorrelationId = parentCorrelationId,
        context = ActionContext(
            platform = platform,
            eventName = eventName,
            attributes = this.attributes + attributes
        )
    )
}
