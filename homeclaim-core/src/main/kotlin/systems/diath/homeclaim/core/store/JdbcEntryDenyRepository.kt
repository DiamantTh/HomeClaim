package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.model.EntryDenyId
import systems.diath.homeclaim.core.model.EntryDenyRule
import systems.diath.homeclaim.core.model.EntryDenyStatus
import systems.diath.homeclaim.core.model.EntryDenyTargetType
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.service.EntryDenyService
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

class JdbcEntryDenyRepository(
    private val dataSource: DataSource
) : EntryDenyService {
    private val runtimeRules = AtomicReference<Map<RegionId, List<EntryDenyRule>>>(emptyMap())

    init {
        reloadRuntimeRules()
    }

    override fun createRule(
        regionId: RegionId,
        targetType: EntryDenyTargetType,
        targetValue: String,
        reason: String,
        createdBy: PlayerId,
        expiresAt: Instant?
    ): EntryDenyRule {
        require(reason.isNotBlank()) { "Entry deny reason is required" }
        val normalizedTarget = normalizeTarget(targetType, targetValue)
        val rule = EntryDenyRule(
            id = EntryDenyId(UUID.randomUUID()),
            regionId = regionId,
            targetType = targetType,
            targetValue = normalizedTarget,
            reason = reason.trim(),
            createdBy = createdBy,
            createdAt = Instant.now(),
            expiresAt = expiresAt
        )

        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO region_entry_denies (
                    id, region_id, target_type, target_value, reason, created_by,
                    created_at, expires_at, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setObject(1, rule.id.value)
                stmt.setObject(2, rule.regionId.value)
                stmt.setString(3, rule.targetType.name)
                stmt.setString(4, rule.targetValue)
                stmt.setString(5, rule.reason)
                stmt.setObject(6, rule.createdBy)
                stmt.setTimestamp(7, Timestamp.from(rule.createdAt))
                stmt.setTimestamp(8, rule.expiresAt?.let(Timestamp::from))
                stmt.setString(9, rule.status.name)
                stmt.executeUpdate()
            }
        }
        replaceCachedRule(rule)
        return rule
    }

    override fun getRule(id: EntryDenyId): EntryDenyRule? = runtimeRules.get().values.asSequence()
        .flatten().firstOrNull { it.id == id }

    override fun listRules(regionId: RegionId, includeInactive: Boolean): List<EntryDenyRule> {
        val rules = runtimeRules.get()[regionId].orEmpty()
        if (includeInactive) return rules
        val now = Instant.now()
        return rules.filter { it.status != EntryDenyStatus.REVOKED && (it.expiresAt == null || it.expiresAt.isAfter(now)) }
    }

    override fun findActiveDeny(regionId: RegionId, playerId: PlayerId, playerName: String?): EntryDenyRule? {
        return listRules(regionId).firstOrNull { it.matches(playerId, playerName) }
    }

    override fun reportRule(id: EntryDenyId, reportedBy: PlayerId, reason: String): Boolean {
        require(reason.isNotBlank()) { "Report reason is required" }
        val updated = dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                UPDATE region_entry_denies
                SET status = ?, reported_by = ?, reported_at = ?, report_reason = ?
                WHERE id = ? AND status <> ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, EntryDenyStatus.REPORTED.name)
                stmt.setObject(2, reportedBy)
                stmt.setTimestamp(3, Timestamp.from(Instant.now()))
                stmt.setString(4, reason.trim())
                stmt.setObject(5, id.value)
                stmt.setString(6, EntryDenyStatus.REVOKED.name)
                stmt.executeUpdate() > 0
            }
        }
        if (updated) refreshCachedRule(id)
        return updated
    }

    override fun revokeRule(id: EntryDenyId, revokedBy: PlayerId, reason: String): Boolean {
        val updated = dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                UPDATE region_entry_denies
                SET status = ?, revoked_by = ?, revoked_at = ?, revoke_reason = ?
                WHERE id = ? AND status <> ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, EntryDenyStatus.REVOKED.name)
                stmt.setObject(2, revokedBy)
                stmt.setTimestamp(3, Timestamp.from(Instant.now()))
                stmt.setString(4, reason.trim())
                stmt.setObject(5, id.value)
                stmt.setString(6, EntryDenyStatus.REVOKED.name)
                stmt.executeUpdate() > 0
            }
        }
        if (updated) refreshCachedRule(id)
        return updated
    }

    private fun normalizeTarget(type: EntryDenyTargetType, value: String): String {
        val trimmed = value.trim()
        return when (type) {
            EntryDenyTargetType.PLAYER -> UUID.fromString(trimmed).toString()
            EntryDenyTargetType.EVERYONE -> "*"
            EntryDenyTargetType.NAME_PATTERN -> {
                require(trimmed.length in 1..64) { "Name pattern must be 1-64 characters" }
                require(trimmed.all { it.isLetterOrDigit() || it == '_' || it == '*' || it == '?' }) {
                    "Name pattern may only contain letters, digits, underscore, * and ?"
                }
                trimmed
            }
        }
    }

    private fun reloadRuntimeRules() {
        val loaded = dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM region_entry_denies ORDER BY created_at DESC").use { statement ->
                statement.executeQuery().use { rs ->
                    buildMap<RegionId, MutableList<EntryDenyRule>> {
                        while (rs.next()) {
                            val rule = rs.toEntryDenyRule()
                            getOrPut(rule.regionId, ::mutableListOf).add(rule)
                        }
                    }.mapValues { (_, rules) -> rules.toList() }
                }
            }
        }
        runtimeRules.set(loaded)
    }

    private fun replaceCachedRule(rule: EntryDenyRule) {
        runtimeRules.updateAndGet { current ->
            val rules = current[rule.regionId].orEmpty().filterNot { it.id == rule.id }
            current + (rule.regionId to (listOf(rule) + rules).sortedByDescending { it.createdAt })
        }
    }

    private fun refreshCachedRule(id: EntryDenyId) {
        val refreshed = dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM region_entry_denies WHERE id = ?").use { statement ->
                statement.setObject(1, id.value)
                statement.executeQuery().use { rs -> if (rs.next()) rs.toEntryDenyRule() else null }
            }
        }
        runtimeRules.updateAndGet { current ->
            val reduced = current.mapValues { (_, rules) -> rules.filterNot { it.id == id } }
                .filterValues { it.isNotEmpty() }
            if (refreshed == null) reduced else reduced + (refreshed.regionId to
                (listOf(refreshed) + reduced[refreshed.regionId].orEmpty()).sortedByDescending { it.createdAt })
        }
    }

    private fun ResultSet.toEntryDenyRule(): EntryDenyRule {
        return EntryDenyRule(
            id = EntryDenyId(getRequiredUuid("id")),
            regionId = RegionId(getRequiredUuid("region_id")),
            targetType = EntryDenyTargetType.valueOf(getString("target_type")),
            targetValue = getString("target_value"),
            reason = getString("reason"),
            createdBy = getRequiredUuid("created_by"),
            createdAt = getTimestamp("created_at").toInstant(),
            expiresAt = getTimestamp("expires_at")?.toInstant(),
            status = EntryDenyStatus.valueOf(getString("status")),
            reportedBy = getUuid("reported_by"),
            reportedAt = getTimestamp("reported_at")?.toInstant(),
            reportReason = getString("report_reason"),
            revokedBy = getUuid("revoked_by"),
            revokedAt = getTimestamp("revoked_at")?.toInstant(),
            revokeReason = getString("revoke_reason")
        )
    }
}
