package systems.diath.homeclaim.core.service

/**
 * Typed audit category and action enums with stable wire values.
 *
 * AuditEntry still stores strings for backward compatibility.
 */
enum class AuditCategory(val wire: String) {
    BLOCK("BLOCK"),
    PVP("PVP"),
    REDSTONE("REDSTONE"),
    MOB("MOB"),
    VEHICLE("VEHICLE"),
    COMPONENT("COMPONENT"),
    IMPORT("IMPORT"),
    REGION("REGION"),
    FLAG("FLAG"),
    LIMIT("LIMIT"),
    PROFILE("PROFILE")
}

enum class AuditAction(val wire: String) {
    PLACE_DENIED("PLACE_DENIED"),
    PLACE_ALLOWED("PLACE_ALLOWED"),
    BREAK_DENIED("BREAK_DENIED"),
    BREAK_ALLOWED("BREAK_ALLOWED"),
    PVP_DENIED("PVP_DENIED"),
    BLOCK_DENIED("BLOCK_DENIED"),
    GRIEF_DENIED("GRIEF_DENIED"),
    ENTER_DENIED("ENTER_DENIED"),
    ELEVATOR_USED("ELEVATOR_USED"),
    TELEPORT_USED("TELEPORT_USED"),
    PLOT_IMPORTED("PLOT_IMPORTED"),
    CREATED("CREATED"),
    UPDATED("UPDATED"),
    DELETED("DELETED"),
    UPSERT("UPSERT"),
    APPLIED("APPLIED")

    ;

    fun summaryLabel(): String = when (this) {
        PLACE_DENIED, PLACE_ALLOWED -> "PLACE"
        BREAK_DENIED, BREAK_ALLOWED -> "BREAK"
        PVP_DENIED -> "PVP"
        BLOCK_DENIED -> "BLOCK"
        GRIEF_DENIED -> "GRIEF"
        ENTER_DENIED -> "ENTER"
        ELEVATOR_USED -> "ELEVATOR"
        TELEPORT_USED -> "TELEPORT"
        PLOT_IMPORTED -> "PLOT_IMPORT"
        CREATED -> "CREATE"
        UPDATED -> "UPDATE"
        DELETED -> "DELETE"
        UPSERT -> "UPSERT"
        APPLIED -> "APPLY"
    }

    companion object {
        private val byWire = entries.associateBy(AuditAction::wire)

        fun fromWire(wire: String?): AuditAction? = wire?.let(byWire::get)
    }
}
