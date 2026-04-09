package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionRole
import systems.diath.homeclaim.core.policy.Decision
import systems.diath.homeclaim.core.policy.Action

interface PolicyService {
    fun evaluate(
        playerId: PlayerId,
        action: Action,
        position: Position?,
        extraContext: Map<String, Any?> = emptyMap()
    ): Decision

    fun resolveRole(regionId: RegionId, playerId: PlayerId): RegionRole
}
