package systems.diath.homeclaim.core.service

interface AuditService {
    fun append(entry: AuditEntry)
}

data class AuditEntry(
    val actorId: systems.diath.homeclaim.core.model.PlayerId?,
    val targetId: java.util.UUID?,
    val category: String,
    val action: String,
    val payload: Map<String, Any?> = emptyMap(),
    val createdAt: java.time.Instant = java.time.Instant.now()
)
