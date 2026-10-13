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
    /** Controls piston source-to-target movements handled by the Paper automation listener. */
    val PISTON = FlagKey("PISTON")
    /** Controls natural liquid flow, buckets, and sponge absorption handled by the Paper automation listener. */
    val LIQUID_FLOW = FlagKey("LIQUID_FLOW")
    /** Controls inventory-to-inventory automation, including cross-boundary hopper transfers. */
    val HOPPER_TRANSFER = FlagKey("HOPPER_TRANSFER")
    /** Controls dispenser and dropper effects aimed at blocks; entity effects retain ENTITY_DAMAGE. */
    val DISPENSER = FlagKey("DISPENSER")
    /** Controls projectile launch authorization; hit effects remain covered by their later damage events. */
    val PROJECTILE = FlagKey("PROJECTILE")
    /** Controls hanging entities and their related placement, break, leash, and shear interactions. */
    val HANGING_ENTITY = FlagKey("HANGING_ENTITY")
    val ITEM_DROP = FlagKey("ITEM_DROP")
    val ITEM_PICKUP = FlagKey("ITEM_PICKUP")

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
        VEHICLE_USE,
        PISTON,
        LIQUID_FLOW,
        HOPPER_TRANSFER,
        DISPENSER,
        PROJECTILE,
        HANGING_ENTITY,
        ITEM_DROP,
        ITEM_PICKUP
    )
}

object LimitCatalog {
    val COMPONENT_COOLDOWN_MS = LimitKey("COMPONENT_COOLDOWN_MS")
    val ELEVATOR_RANGE_BLOCKS = LimitKey("ELEVATOR_RANGE_BLOCKS")

    val limits = setOf(COMPONENT_COOLDOWN_MS, ELEVATOR_RANGE_BLOCKS)
}
