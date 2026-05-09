package systems.diath.homeclaim.platform.paper

import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.RegionId
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class EntryForceGrant(
    val id: UUID,
    val playerId: PlayerId,
    val regionId: RegionId?,
    val grantedBy: PlayerId,
    val reason: String,
    val expiresAt: Instant
)

object EntryForceRegistry {
    private val grants = ConcurrentHashMap<PlayerId, EntryForceGrant>()

    fun grant(
        playerId: PlayerId,
        regionId: RegionId?,
        grantedBy: PlayerId,
        reason: String,
        ttlSeconds: Long = 30
    ): EntryForceGrant {
        val grant = EntryForceGrant(
            id = UUID.randomUUID(),
            playerId = playerId,
            regionId = regionId,
            grantedBy = grantedBy,
            reason = reason,
            expiresAt = Instant.now().plusSeconds(ttlSeconds.coerceIn(1, 300))
        )
        grants[playerId] = grant
        return grant
    }

    fun consume(playerId: PlayerId, regionId: RegionId?): EntryForceGrant? {
        val grant = grants[playerId] ?: return null
        if (grant.expiresAt.isBefore(Instant.now())) {
            grants.remove(playerId, grant)
            return null
        }
        if (grant.regionId != null && grant.regionId != regionId) {
            return null
        }
        grants.remove(playerId, grant)
        return grant
    }

    fun revoke(playerId: PlayerId): Boolean {
        return grants.remove(playerId) != null
    }
}
