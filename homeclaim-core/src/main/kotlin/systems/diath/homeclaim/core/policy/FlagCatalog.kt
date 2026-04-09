package systems.diath.homeclaim.core.policy

import systems.diath.homeclaim.core.model.FlagKey
import systems.diath.homeclaim.core.model.LimitKey

object FlagCatalog {
    val BUILD = FlagKey("BUILD")
    val BREAK = FlagKey("BREAK")
    val INTERACT_BLOCK = FlagKey("INTERACT_BLOCK")
    val INTERACT_CONTAINER = FlagKey("INTERACT_CONTAINER")
    val REDSTONE = FlagKey("REDSTONE")
    val COMPONENT_USE = FlagKey("COMPONENT_USE")
    val FIRE_SPREAD = FlagKey("FIRE_SPREAD")
    val EXPLOSION_DAMAGE = FlagKey("EXPLOSION_DAMAGE")
    val PVP = FlagKey("PVP")
    val MOB_GRIEF = FlagKey("MOB_GRIEF")
    val ENTITY_DAMAGE = FlagKey("ENTITY_DAMAGE")
    val VEHICLE_USE = FlagKey("VEHICLE_USE")

    val flags = setOf(
        BUILD,
        BREAK,
        INTERACT_BLOCK,
        INTERACT_CONTAINER,
        REDSTONE,
        COMPONENT_USE,
        FIRE_SPREAD,
        EXPLOSION_DAMAGE,
        PVP,
        MOB_GRIEF,
        ENTITY_DAMAGE,
        VEHICLE_USE
    )
}

object LimitCatalog {
    val COMPONENT_COOLDOWN_MS = LimitKey("COMPONENT_COOLDOWN_MS")
    val ELEVATOR_RANGE_BLOCKS = LimitKey("ELEVATOR_RANGE_BLOCKS")

    val limits = setOf(COMPONENT_COOLDOWN_MS, ELEVATOR_RANGE_BLOCKS)
}
