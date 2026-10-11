package systems.diath.homeclaim.ux

import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
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

    fun listComponents(regionId: RegionId) = componentService.listComponents(regionId)
}
