package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionRole
import systems.diath.homeclaim.core.model.RegionRoles

/** Canonical role mutation path; updates the same region repository/cache used by policy resolution. */
class RegionRoleService(
    private val regions: RegionService
) : RoleService {
    override fun setRole(regionId: RegionId, playerId: PlayerId, role: RegionRole) {
        val region = regions.getRegionById(regionId)
            ?: throw NoSuchElementException("Region ${regionId.value} does not exist")
        val existing = region.roles
        val cleared = existing.copy(
            trusted = existing.trusted - playerId,
            members = existing.members - playerId,
            banned = existing.banned - playerId
        )
        val roles = when (role) {
            RegionRole.TRUSTED -> cleared.copy(trusted = cleared.trusted + playerId)
            RegionRole.MEMBER -> cleared.copy(members = cleared.members + playerId)
            RegionRole.BANNED -> cleared.copy(banned = cleared.banned + playerId)
            RegionRole.VISITOR -> cleared
            RegionRole.OWNER -> cleared
        }
        val updated = if (role == RegionRole.OWNER) {
            region.copy(owner = playerId, roles = roles)
        } else {
            region.copy(roles = roles)
        }
        regions.updateRegion(updated)
    }

    override fun removeRole(regionId: RegionId, playerId: PlayerId, role: RegionRole) {
        val region = regions.getRegionById(regionId)
            ?: throw NoSuchElementException("Region ${regionId.value} does not exist")
        val roles: RegionRoles = when (role) {
            RegionRole.TRUSTED -> region.roles.copy(trusted = region.roles.trusted - playerId)
            RegionRole.MEMBER -> region.roles.copy(members = region.roles.members - playerId)
            RegionRole.BANNED -> region.roles.copy(banned = region.roles.banned - playerId)
            RegionRole.VISITOR, RegionRole.OWNER -> region.roles
        }
        if (roles != region.roles) regions.updateRegion(region.copy(roles = roles))
    }
}
