package systems.diath.homeclaim.core.service

/**
 * Shared audit category/action taxonomy.
 *
 * Keeps adapters and command/reporting logic consistent.
 */
object AuditTaxonomy {
    object Category {
        val BLOCK = AuditCategory.BLOCK.wire
        val PVP = AuditCategory.PVP.wire
        val REDSTONE = AuditCategory.REDSTONE.wire
        val MOB = AuditCategory.MOB.wire
        val VEHICLE = AuditCategory.VEHICLE.wire
        val COMPONENT = AuditCategory.COMPONENT.wire
        val IMPORT = AuditCategory.IMPORT.wire
        val REGION = AuditCategory.REGION.wire
        val FLAG = AuditCategory.FLAG.wire
        val LIMIT = AuditCategory.LIMIT.wire
        val PROFILE = AuditCategory.PROFILE.wire
    }

    object Action {
        val PLACE_DENIED = AuditAction.PLACE_DENIED.wire
        val PLACE_ALLOWED = AuditAction.PLACE_ALLOWED.wire
        val BREAK_DENIED = AuditAction.BREAK_DENIED.wire
        val BREAK_ALLOWED = AuditAction.BREAK_ALLOWED.wire
        val PVP_DENIED = AuditAction.PVP_DENIED.wire
        val BLOCK_DENIED = AuditAction.BLOCK_DENIED.wire
        val GRIEF_DENIED = AuditAction.GRIEF_DENIED.wire
        val ENTER_DENIED = AuditAction.ENTER_DENIED.wire
        val ELEVATOR_USED = AuditAction.ELEVATOR_USED.wire
        val TELEPORT_USED = AuditAction.TELEPORT_USED.wire
        val PLOT_IMPORTED = AuditAction.PLOT_IMPORTED.wire
        val CREATED = AuditAction.CREATED.wire
        val UPDATED = AuditAction.UPDATED.wire
        val DELETED = AuditAction.DELETED.wire
        val UPSERT = AuditAction.UPSERT.wire
        val APPLIED = AuditAction.APPLIED.wire
    }
}
