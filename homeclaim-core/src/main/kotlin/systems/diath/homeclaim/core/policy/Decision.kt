package systems.diath.homeclaim.core.policy

import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.WorldId

enum class Action(
    val key: ActionKey,
    val requiresTarget: Boolean = true,
    val mutating: Boolean = true,
    val bulkCapable: Boolean = false
) {
    REGION_ENTER(ActionKey("homeclaim:region_enter"), mutating = false),
    REGION_TELEPORT_ENTER(ActionKey("homeclaim:region_teleport_enter"), mutating = false),
    REGION_BUILD(ActionKey("homeclaim:region_build"), bulkCapable = true),
    REGION_BREAK(ActionKey("homeclaim:region_break"), bulkCapable = true),
    REGION_INTERACT_BLOCK(ActionKey("homeclaim:region_interact_block"), mutating = false),
    REGION_INTERACT_CONTAINER(ActionKey("homeclaim:region_interact_container"), mutating = false),
    REGION_REDSTONE(ActionKey("homeclaim:region_redstone"), bulkCapable = true),
    REGION_PVP(ActionKey("homeclaim:region_pvp")),
    REGION_FIRE(ActionKey("homeclaim:region_fire"), bulkCapable = true),
    REGION_EXPLOSION(ActionKey("homeclaim:region_explosion"), bulkCapable = true),
    PISTON_MOVE(ActionKey("minecraft:piston_move"), bulkCapable = true),
    LIQUID_FLOW(ActionKey("minecraft:liquid_flow"), bulkCapable = true),
    HOPPER_TRANSFER(ActionKey("minecraft:hopper_transfer")),
    DISPENSER_USE(ActionKey("minecraft:dispenser_use")),
    DISPENSER_ENTITY_USE(ActionKey("minecraft:dispenser_entity_use")),
    BLOCK_GROW(ActionKey("minecraft:block_grow"), bulkCapable = true),
    FALLING_BLOCK(ActionKey("minecraft:falling_block")),
    ENTITY_PLACE(ActionKey("minecraft:entity_place")),
    PROJECTILE_LAUNCH(ActionKey("minecraft:projectile_launch")),
    HANGING_ENTITY(ActionKey("minecraft:hanging_entity")),
    CAULDRON_CHANGE(ActionKey("minecraft:cauldron_change")),
    ITEM_DROP(ActionKey("minecraft:item_drop")),
    ITEM_PICKUP(ActionKey("minecraft:item_pickup")),
    VEHICLE_MOVE(ActionKey("minecraft:vehicle_move"), mutating = false),
    REGION_MOB_GRIEF(ActionKey("homeclaim:region_mob_grief"), bulkCapable = true),
    REGION_ENTITY_DAMAGE(ActionKey("homeclaim:region_entity_damage")),
    REGION_VEHICLE_USE(ActionKey("homeclaim:region_vehicle_use"), mutating = false),
    COMPONENT_USE(ActionKey("homeclaim:component_use")),
    COMPONENT_CONFIGURE(ActionKey("homeclaim:component_configure")),
    COMPONENT_CREATE(ActionKey("homeclaim:component_create")),
    COMPONENT_REMOVE(ActionKey("homeclaim:component_remove")),
    SPAWNER_USE(ActionKey("homeclaim:spawner_use")),
    SPAWNER_TAKE_DROPS(ActionKey("homeclaim:spawner_take_drops"))
}

enum class DecisionEffect { ALLOW, DENY, INHERIT, NOT_APPLICABLE }

enum class DecisionSource { SCOPE, POLICY, ROLE, PERMISSION, BYPASS, LEGACY }

data class DecisionContext(
    /** Null for entity, automation, and system actors; never synthesize a player UUID. */
    val playerId: PlayerId?,
    val regionId: RegionId?,
    val action: Action,
    val world: WorldId? = null,
    val extra: Map<String, Any?> = emptyMap(),
    val areaId: AreaId? = null,
    val actionKey: ActionKey = action.key,
    val actionRequest: ActionRequest? = null
)

/** Minimal explanation data retained with a decision for audit and future diagnostics. */
data class DecisionTrace(
    val role: String? = null,
    val policyFlag: String? = null,
    val lockedPolicy: Boolean = false,
    val permission: String? = null,
    val bypass: String? = null,
    val permissionResult: systems.diath.homeclaim.core.permission.PermissionResult? = null
)

data class Decision(
    val effect: DecisionEffect,
    val reason: String,
    val detail: String? = null,
    val context: DecisionContext,
    val source: DecisionSource = DecisionSource.POLICY,
    val trace: DecisionTrace = DecisionTrace()
)
