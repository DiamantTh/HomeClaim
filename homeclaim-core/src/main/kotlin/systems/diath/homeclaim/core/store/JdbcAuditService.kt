package systems.diath.homeclaim.core.store

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import systems.diath.homeclaim.core.policy.ActionEndpoint
import systems.diath.homeclaim.core.service.AuditEntry
import systems.diath.homeclaim.core.service.AuditService
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.sql.Types
import javax.sql.DataSource

/** JDBC implementation of the canonical HomeClaim audit sink. */
class JdbcAuditService(private val dataSource: DataSource) : AuditService {
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
                stmt.setString(1, entry.auditId.toString())
                stmt.setString(2, entry.actorId?.toString())
                stmt.setString(3, entry.targetId?.toString())
                stmt.setString(4, entry.category)
                stmt.setString(5, entry.action)
                stmt.setString(6, entry.correlationId?.toString())
                stmt.setString(7, entry.parentCorrelationId?.toString())
                stmt.setString(8, entry.actorKind)
                stmt.setString(9, entry.actorRef)
                stmt.setString(10, entry.actionKey?.value)
                stmt.setString(11, entry.worldId?.value?.toString())
                stmt.setString(12, entry.areaId?.value?.toString())
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

    private fun setEndpoint(stmt: PreparedStatement, start: Int, endpoint: ActionEndpoint?) {
        stmt.setString(start, endpoint?.worldId?.value?.toString())
        stmt.setString(start + 1, endpoint?.areaId?.value?.toString()
            ?: (endpoint?.resource as? systems.diath.homeclaim.core.policy.ActionResourceRef.Area)?.areaId?.value?.toString())
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
