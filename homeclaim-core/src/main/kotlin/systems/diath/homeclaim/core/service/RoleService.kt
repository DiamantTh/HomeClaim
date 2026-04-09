package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionRole

interface RoleService {
    fun setRole(regionId: RegionId, playerId: PlayerId, role: RegionRole)
    fun removeRole(regionId: RegionId, playerId: PlayerId, role: RegionRole)
}
