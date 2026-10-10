package systems.diath.homeclaim.core.service

import java.time.Instant
import java.util.UUID

/** A bounded read model for administrative audit views. */
data class AuditLogRecord(
    val actorId: UUID?,
    val targetId: UUID?,
    val action: String,
    val payload: Map<String, Any?>,
    val createdAt: Instant
)

interface AuditReader {
    /** Return newest records for one HomeClaim resource, no older than [since]. */
    fun findForTarget(targetId: UUID, since: Instant, limit: Int = 50): List<AuditLogRecord>
}
