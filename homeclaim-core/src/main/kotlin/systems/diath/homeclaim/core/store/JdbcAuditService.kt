package systems.diath.homeclaim.core.store

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import systems.diath.homeclaim.core.service.AuditEntry
import systems.diath.homeclaim.core.service.AuditService
import javax.sql.DataSource
import java.sql.Timestamp

/**
 * JDBC implementation of AuditService that persists audit entries to the audit_log table.
 */
class JdbcAuditService(private val dataSource: DataSource) : AuditService {
    private val mapper = jacksonObjectMapper()

    override fun append(entry: AuditEntry) {
        val sql = """
            INSERT INTO audit_log (actor_id, target_id, category, action, payload, created_at)
            VALUES (?, ?, ?, ?, ?::json, ?)
        """.trimIndent()
        
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, entry.actorId)
                stmt.setObject(2, entry.targetId)
                stmt.setString(3, entry.category)
                stmt.setString(4, entry.action)
                stmt.setString(5, mapper.writeValueAsString(entry.payload))
                stmt.setTimestamp(6, Timestamp.from(entry.createdAt))
                stmt.executeUpdate()
            }
        }
    }
}
