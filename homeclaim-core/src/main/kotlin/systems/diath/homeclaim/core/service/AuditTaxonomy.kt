package systems.diath.homeclaim.core.service

/**
 * Shared audit category/action taxonomy.
 *
 * Keeps adapters and command/reporting logic consistent.
 */
object AuditTaxonomy {
    object Category {
        const val BLOCK = "BLOCK"
        const val PVP = "PVP"
        const val REDSTONE = "REDSTONE"
        const val MOB = "MOB"
        const val VEHICLE = "VEHICLE"
        const val COMPONENT = "COMPONENT"
        const val IMPORT = "IMPORT"
    }

    object Action {
        const val PLACE_DENIED = "PLACE_DENIED"
        const val PLACE_ALLOWED = "PLACE_ALLOWED"
        const val BREAK_DENIED = "BREAK_DENIED"
        const val BREAK_ALLOWED = "BREAK_ALLOWED"
        const val PVP_DENIED = "PVP_DENIED"
        const val BLOCK_DENIED = "BLOCK_DENIED"
        const val GRIEF_DENIED = "GRIEF_DENIED"
        const val ENTER_DENIED = "ENTER_DENIED"
        const val ELEVATOR_USED = "ELEVATOR_USED"
        const val TELEPORT_USED = "TELEPORT_USED"
        const val PLOT_IMPORTED = "PLOT_IMPORTED"
    }
}
