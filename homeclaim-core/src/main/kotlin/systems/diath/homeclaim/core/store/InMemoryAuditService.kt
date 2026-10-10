package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.service.AuditEntry
import systems.diath.homeclaim.core.service.AuditService
import systems.diath.homeclaim.core.service.AuditReader
import systems.diath.homeclaim.core.service.AuditLogRecord
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory implementation of AuditService for development/testing.
 * Stores entries in a thread-safe list (limited to last 10,000 entries).
 */
class InMemoryAuditService(private val maxEntries: Int = 10_000) : AuditService, AuditReader {
    private val log = CopyOnWriteArrayList<AuditEntry>()

    override fun append(entry: AuditEntry) {
        log.add(entry)
        // Keep only recent entries to avoid memory bloat
        if (log.size > maxEntries) {
            log.removeAt(0)
        }
    }

    fun getEntries(): List<AuditEntry> = log.toList()

    override fun findForTarget(targetId: UUID, since: Instant, limit: Int): List<AuditLogRecord> {
        require(limit in 1..500) { "Audit query limit must be between 1 and 500" }
        return log.asSequence()
            .filter { it.targetId == targetId && !it.createdAt.isBefore(since) }
            .sortedWith(compareByDescending<AuditEntry> { it.createdAt }.thenByDescending { it.auditId })
            .take(limit)
            .map { AuditLogRecord(it.actorId, it.targetId, it.action, it.payload, it.createdAt, it.correlationId) }
            .toList()
    }
    
    fun clear() = log.clear()
}
