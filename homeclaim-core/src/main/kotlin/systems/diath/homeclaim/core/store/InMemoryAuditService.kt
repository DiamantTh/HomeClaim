package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.service.AuditEntry
import systems.diath.homeclaim.core.service.AuditService
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory implementation of AuditService for development/testing.
 * Stores entries in a thread-safe list (limited to last 10,000 entries).
 */
class InMemoryAuditService(private val maxEntries: Int = 10_000) : AuditService {
    private val log = CopyOnWriteArrayList<AuditEntry>()

    override fun append(entry: AuditEntry) {
        log.add(entry)
        // Keep only recent entries to avoid memory bloat
        if (log.size > maxEntries) {
            log.removeAt(0)
        }
    }

    fun getEntries(): List<AuditEntry> = log.toList()
    
    fun clear() = log.clear()
}
