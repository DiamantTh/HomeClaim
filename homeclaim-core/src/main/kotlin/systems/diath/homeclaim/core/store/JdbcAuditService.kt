package systems.diath.homeclaim.core.store

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import systems.diath.homeclaim.core.policy.ActionEndpoint
import systems.diath.homeclaim.core.service.AuditEntry
import systems.diath.homeclaim.core.service.AuditLogRecord
import systems.diath.homeclaim.core.service.AuditReader
import systems.diath.homeclaim.core.service.AuditService
import com.fasterxml.jackson.core.type.TypeReference
import java.time.Instant
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.sql.Types
import javax.sql.DataSource

/** JDBC implementation of the canonical HomeClaim audit sink. */
class JdbcAuditService(private val dataSource: DataSource) : AuditService, AuditReader {
    private val mapper = jacksonObjectMapper()

    override fun append(entry: AuditEntry) {
        val sql = """
            INSERT INTO audit_log (
                audit_id, actor_id, target_id, category, action, correlation_id,
                parent_correlation_id, actor_kind, actor_ref, action_key, world_id, area_id,
                source_world_id, source_area_id, source_x, source_y, source_z, source_resource_type, source_resource_id,
                target_world_id, target_area_id, target_x, target_y, target_z, target_resource_type, target_resource_id,
                role, policy_result, permission_result, bypass, reason, status, target_count, error,
                payload, created_at
            ) VALUES (${List(36) { "?" }.joinToString(", ")})
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, entry.auditId)
                stmt.setObject(2, entry.actorId)
                stmt.setObject(3, entry.targetId)
                stmt.setString(4, entry.category)
                stmt.setString(5, entry.action)
                stmt.setObject(6, entry.correlationId?.value)
                stmt.setObject(7, entry.parentCorrelationId?.value)
                stmt.setString(8, entry.actorKind)
                stmt.setString(9, entry.actorRef)
                stmt.setString(10, entry.actionKey?.value)
                stmt.setObject(11, entry.worldId?.value)
                stmt.setObject(12, entry.areaId?.value)
                setEndpoint(stmt, 13, entry.source)
                setEndpoint(stmt, 20, entry.target)
                stmt.setString(27, entry.role?.name)
                stmt.setString(28, entry.policyResult?.name)
                stmt.setString(29, entry.permissionResult?.name)
                stmt.setString(30, entry.bypass)
                stmt.setString(31, entry.reason)
                stmt.setString(32, entry.status.name)
                if (entry.targetCount == null) stmt.setNull(33, Types.INTEGER)
                else stmt.setInt(33, entry.targetCount)
                stmt.setString(34, entry.error)
                stmt.setString(35, mapper.writeValueAsString(entry.payload))
                stmt.setTimestamp(36, Timestamp.from(entry.createdAt))
                stmt.executeUpdate()
            }
        }
    }

    override fun findForTarget(targetId: java.util.UUID, since: Instant, limit: Int): List<AuditLogRecord> {
        require(limit in 1..500) { "Audit query limit must be between 1 and 500" }
        val sql = """
            SELECT actor_id, target_id, action, payload, created_at
            FROM audit_log
            WHERE target_id = ? AND created_at >= ?
            ORDER BY created_at DESC, audit_id DESC
            LIMIT ?
        """.trimIndent()
        return dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, targetId)
                statement.setTimestamp(2, Timestamp.from(since))
                statement.setInt(3, limit)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            val payload = runCatching {
                                mapper.readValue<Map<String, Any?>>(result.getString("payload"), object : TypeReference<Map<String, Any?>>() {})
                            }.getOrDefault(emptyMap())
                            add(
                                AuditLogRecord(
                                    actorId = result.getString("actor_id")?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() },
                                    targetId = result.getString("target_id")?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() },
                                    action = result.getString("action"),
                                    payload = payload,
                                    createdAt = result.getTimestamp("created_at").toInstant()
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun setEndpoint(stmt: PreparedStatement, start: Int, endpoint: ActionEndpoint?) {
        stmt.setObject(start, endpoint?.worldId?.value)
        stmt.setObject(start + 1, endpoint?.areaId?.value
            ?: (endpoint?.resource as? systems.diath.homeclaim.core.policy.ActionResourceRef.Area)?.areaId?.value)
        val position = endpoint?.position
        if (position == null) {
            stmt.setNull(start + 2, Types.INTEGER)
            stmt.setNull(start + 3, Types.INTEGER)
            stmt.setNull(start + 4, Types.INTEGER)
        } else {
            stmt.setInt(start + 2, position.x)
            stmt.setInt(start + 3, position.y)
            stmt.setInt(start + 4, position.z)
        }
        stmt.setString(start + 5, endpoint?.resource?.type)
        stmt.setString(start + 6, endpoint?.resource?.id)
    }
}
