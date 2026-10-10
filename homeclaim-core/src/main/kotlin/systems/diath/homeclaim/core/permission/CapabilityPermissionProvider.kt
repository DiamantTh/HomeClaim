package systems.diath.homeclaim.core.permission

import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.policy.PolicyActorContext

enum class PermissionResult { GRANTED, DENIED, UNDEFINED }

/** Context dimensions for general capabilities; plot-specific rules stay in HomeClaim policy. */
data class PermissionContext(
    val worldId: WorldId? = null,
    val areaId: AreaId? = null,
    val resourceType: String? = null
)

/** Read-only capability checks used by the policy resolver. */
fun interface CapabilityPermissionProvider {
    fun check(actor: PolicyActorContext, permission: String, context: PermissionContext): PermissionResult
}

object PolicyPermissions {
    const val PROTECTION_BYPASS = "homeclaim.bypass.protection"
    const val ENTRY_BYPASS = "homeclaim.bypass.entry"
    const val LOCKED_POLICY_BYPASS = "homeclaim.bypass.locked-policy"

    fun forAction(action: systems.diath.homeclaim.core.policy.Action): String {
        val capability = when (action) {
            systems.diath.homeclaim.core.policy.Action.PISTON_MOVE,
            systems.diath.homeclaim.core.policy.Action.LIQUID_FLOW,
            systems.diath.homeclaim.core.policy.Action.DISPENSER_USE,
            systems.diath.homeclaim.core.policy.Action.BLOCK_GROW,
            systems.diath.homeclaim.core.policy.Action.FALLING_BLOCK,
            systems.diath.homeclaim.core.policy.Action.ENTITY_PLACE,
            systems.diath.homeclaim.core.policy.Action.HANGING_ENTITY ->
                systems.diath.homeclaim.core.policy.Action.REGION_BUILD
            systems.diath.homeclaim.core.policy.Action.HOPPER_TRANSFER ->
                systems.diath.homeclaim.core.policy.Action.REGION_INTERACT_CONTAINER
            systems.diath.homeclaim.core.policy.Action.CAULDRON_CHANGE ->
                systems.diath.homeclaim.core.policy.Action.REGION_INTERACT_BLOCK
            systems.diath.homeclaim.core.policy.Action.ITEM_DROP,
            systems.diath.homeclaim.core.policy.Action.ITEM_PICKUP ->
                systems.diath.homeclaim.core.policy.Action.REGION_INTERACT_CONTAINER
            systems.diath.homeclaim.core.policy.Action.PROJECTILE_LAUNCH ->
                systems.diath.homeclaim.core.policy.Action.REGION_ENTITY_DAMAGE
            systems.diath.homeclaim.core.policy.Action.VEHICLE_MOVE ->
                systems.diath.homeclaim.core.policy.Action.REGION_VEHICLE_USE
            else -> action
        }
        return "homeclaim.action.${capability.name.lowercase()}"
    }
}
