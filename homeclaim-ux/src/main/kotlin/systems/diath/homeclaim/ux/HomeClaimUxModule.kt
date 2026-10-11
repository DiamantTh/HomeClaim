package systems.diath.homeclaim.ux

import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionRole
import systems.diath.homeclaim.core.service.ComponentService
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.core.service.RoleService
import systems.diath.homeclaim.core.service.RoleMutationRequest
import systems.diath.homeclaim.core.service.OperationResult

/**
 * Reference wiring point for in-game menus or a future web UI.
 * Keeps logic thin by delegating to core services.
 */
class HomeClaimUxModule(
    private val regionService: RegionService,
    private val roleService: RoleService,
    private val componentService: ComponentService
) {
    fun listPlayerRegions(playerId: PlayerId): List<Region> = regionService.listRegionsByOwner(playerId)

    fun mutateRole(request: RoleMutationRequest): OperationResult<Region> = roleService.mutateRole(request)

    @Deprecated("Pass the initiating actor to mutateRole")
    fun updateRole(regionId: RegionId, playerId: PlayerId, role: RegionRole) {
        roleService.setRole(regionId, playerId, role)
    }

    @Deprecated("Pass the initiating actor to mutateRole")
    fun removeRole(regionId: RegionId, playerId: PlayerId, role: RegionRole) {
        roleService.removeRole(regionId, playerId, role)
    }

    fun listComponents(regionId: RegionId) = componentService.listComponents(regionId)
}
