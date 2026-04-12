package systems.diath.homeclaim.core.service

import java.util.UUID
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.policy.Decision

/**
 * Factory helpers for canonical audit entries.
 */
object AuditEntries {
    fun denied(
        actorId: PlayerId?,
        targetId: UUID? = null,
        category: String,
        action: String,
        position: Position,
        decision: Decision,
        extra: Map<String, Any?> = emptyMap()
    ): AuditEntry {
        return AuditEntry(
            actorId = actorId,
            targetId = targetId,
            category = category,
            action = action,
            payload = AuditPayloads.deniedPolicyPayload(position, decision, extra)
        )
    }
}
