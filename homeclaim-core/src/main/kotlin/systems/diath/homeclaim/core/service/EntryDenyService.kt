package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.EntryDenyId
import systems.diath.homeclaim.core.model.EntryDenyRule
import systems.diath.homeclaim.core.model.EntryDenyTargetType
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.RegionId
import java.time.Instant

interface EntryDenyService {
    fun createRule(
        regionId: RegionId,
        targetType: EntryDenyTargetType,
        targetValue: String,
        reason: String,
        createdBy: PlayerId,
        expiresAt: Instant? = null
    ): EntryDenyRule

    fun getRule(id: EntryDenyId): EntryDenyRule?
    fun listRules(regionId: RegionId, includeInactive: Boolean = false): List<EntryDenyRule>
    fun findActiveDeny(regionId: RegionId, playerId: PlayerId, playerName: String? = null): EntryDenyRule?
    fun reportRule(id: EntryDenyId, reportedBy: PlayerId, reason: String): Boolean
    fun revokeRule(id: EntryDenyId, revokedBy: PlayerId, reason: String): Boolean
}
