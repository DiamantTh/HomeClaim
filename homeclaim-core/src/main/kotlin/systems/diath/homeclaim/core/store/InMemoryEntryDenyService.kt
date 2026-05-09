package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.model.EntryDenyId
import systems.diath.homeclaim.core.model.EntryDenyRule
import systems.diath.homeclaim.core.model.EntryDenyStatus
import systems.diath.homeclaim.core.model.EntryDenyTargetType
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.service.EntryDenyService
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemoryEntryDenyService : EntryDenyService {
    private val rules = ConcurrentHashMap<EntryDenyId, EntryDenyRule>()

    override fun createRule(
        regionId: RegionId,
        targetType: EntryDenyTargetType,
        targetValue: String,
        reason: String,
        createdBy: PlayerId,
        expiresAt: Instant?
    ): EntryDenyRule {
        val rule = EntryDenyRule(
            id = EntryDenyId(UUID.randomUUID()),
            regionId = regionId,
            targetType = targetType,
            targetValue = targetValue,
            reason = reason,
            createdBy = createdBy,
            createdAt = Instant.now(),
            expiresAt = expiresAt
        )
        rules[rule.id] = rule
        return rule
    }

    override fun getRule(id: EntryDenyId): EntryDenyRule? = rules[id]

    override fun listRules(regionId: RegionId, includeInactive: Boolean): List<EntryDenyRule> {
        return rules.values
            .filter { it.regionId == regionId && (includeInactive || it.isEffective()) }
            .sortedByDescending { it.createdAt }
    }

    override fun findActiveDeny(regionId: RegionId, playerId: PlayerId, playerName: String?): EntryDenyRule? {
        return listRules(regionId).firstOrNull { it.matches(playerId, playerName) }
    }

    override fun reportRule(id: EntryDenyId, reportedBy: PlayerId, reason: String): Boolean {
        val existing = rules[id] ?: return false
        rules[id] = existing.copy(
            status = EntryDenyStatus.REPORTED,
            reportedBy = reportedBy,
            reportedAt = Instant.now(),
            reportReason = reason
        )
        return true
    }

    override fun revokeRule(id: EntryDenyId, revokedBy: PlayerId, reason: String): Boolean {
        val existing = rules[id] ?: return false
        rules[id] = existing.copy(
            status = EntryDenyStatus.REVOKED,
            revokedBy = revokedBy,
            revokedAt = Instant.now(),
            revokeReason = reason
        )
        return true
    }
}
