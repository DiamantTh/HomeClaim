package systems.diath.homeclaim.core.policy

import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.WorldId

enum class Action {
    REGION_BUILD,
    REGION_BREAK,
    REGION_INTERACT_BLOCK,
    REGION_INTERACT_CONTAINER,
    REGION_REDSTONE,
    REGION_PVP,
    REGION_FIRE,
    REGION_EXPLOSION,
    REGION_MOB_GRIEF,
    REGION_ENTITY_DAMAGE,
    REGION_VEHICLE_USE,
    COMPONENT_USE,
    COMPONENT_CONFIGURE,
    COMPONENT_CREATE,
    COMPONENT_REMOVE,
    SPAWNER_USE,
    SPAWNER_TAKE_DROPS
}

data class DecisionContext(
    val playerId: PlayerId,
    val regionId: RegionId?,
    val action: Action,
    val world: WorldId? = null,
    val extra: Map<String, Any?> = emptyMap()
)

data class Decision(
    val allowed: Boolean,
    val reason: String,
    val detail: String? = null,
    val context: DecisionContext
)
