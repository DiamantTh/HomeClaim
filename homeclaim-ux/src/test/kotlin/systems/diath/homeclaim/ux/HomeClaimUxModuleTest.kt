package systems.diath.homeclaim.ux

import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.Component
import systems.diath.homeclaim.core.model.ComponentConfig
import systems.diath.homeclaim.core.model.ComponentId
import systems.diath.homeclaim.core.model.ComponentPolicy
import systems.diath.homeclaim.core.model.ComponentState
import systems.diath.homeclaim.core.model.ComponentType
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionRole
import systems.diath.homeclaim.core.model.RegionShape
import systems.diath.homeclaim.core.model.RegionRoles
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.service.ComponentService
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.core.service.RoleService
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeClaimUxModuleTest {
    private val owner: PlayerId = UUID.randomUUID()
    private val regionId = RegionId(UUID.randomUUID())
    private val region = Region(
        id = regionId,
        world = "world",
        shape = RegionShape.CUBOID,
        bounds = Bounds(0, 10, 0, 10, 0, 10),
        owner = owner,
        roles = RegionRoles()
    )

    private val regionService = InMemoryRegionService(mapOf(owner to listOf(region)))
    private val roleService = RecordingRoleService()
    private val componentService = InMemoryComponentService()
    private val module = HomeClaimUxModule(regionService, roleService, componentService)

    @Test
    fun `delegates region listing`() {
        val result = module.listPlayerRegions(owner)
        assertEquals(listOf(region), result)
    }

    @Test
    fun `delegates role updates`() {
        module.updateRole(regionId, owner, RegionRole.TRUSTED)
        assertEquals(listOf(Triple(regionId, owner, RegionRole.TRUSTED)), roleService.setCalls)
    }
}

private class InMemoryRegionService(
    private val regionsByOwner: Map<PlayerId, List<Region>>
) : RegionService {
    override fun getRegionAt(world: WorldId, x: Int, y: Int, z: Int): RegionId? =
        regionsByOwner.values.flatten().firstOrNull { it.world == world && it.bounds.contains(x, y, z) }?.id
    override fun getRegionAt2D(world: WorldId, x: Int, z: Int): RegionId? =
        regionsByOwner.values.flatten().firstOrNull { it.world == world && it.bounds.contains2D(x, z) }?.id
    override fun listRegionsByOwner(ownerId: PlayerId): List<Region> = regionsByOwner[ownerId].orEmpty()
    override fun listAllRegions(): List<Region> = regionsByOwner.values.flatten()
    override fun getRegionById(regionId: RegionId): Region? = regionsByOwner.values.flatten().find { it.id == regionId }
    override fun mergeRegions(regionIds: Collection<RegionId>) = error("not implemented")
    override fun createRegion(region: Region, bounds: Bounds) = region.id
    override fun updateRegion(region: Region) {}
    override fun deleteRegion(regionId: RegionId) {}
    override fun buyRegion(regionId: RegionId, buyerId: PlayerId, econService: systems.diath.homeclaim.core.economy.EconService) = false
    override fun claimRegion(regionId: RegionId, claimerId: PlayerId, cost: Double, econService: systems.diath.homeclaim.core.economy.EconService) = false
    override fun getEventDispatcher(): systems.diath.homeclaim.core.event.EventDispatcher? = null
}

private class RecordingRoleService : RoleService {
    val setCalls = mutableListOf<Triple<RegionId, PlayerId, RegionRole>>()
    override fun setRole(regionId: RegionId, playerId: PlayerId, role: RegionRole) {
        setCalls += Triple(regionId, playerId, role)
    }

    override fun removeRole(regionId: RegionId, playerId: PlayerId, role: RegionRole) {}
}

private class InMemoryComponentService : ComponentService {
    private val components = mutableListOf<Component>()

    override fun createComponent(
        regionId: RegionId,
        type: ComponentType,
        position: Position,
        config: ComponentConfig,
        policy: ComponentPolicy
    ): ComponentId {
        val component = Component(
            id = ComponentId(UUID.randomUUID()),
            regionId = regionId,
            type = type,
            position = position,
            policy = policy,
            config = config,
            createdBy = UUID.randomUUID(),
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        components += component
        return component.id
    }

    override fun getComponentAt(position: Position) = components.find { it.position == position }
    override fun listComponents(regionId: RegionId) = components.filter { it.regionId == regionId }
    override fun updateComponent(componentId: ComponentId, config: ComponentConfig?, policy: ComponentPolicy?, state: ComponentState?) {}
    override fun deleteComponent(componentId: ComponentId) {}
    override fun indexByChunk(world: String, chunkX: Int, chunkZ: Int): List<ComponentId> = emptyList()
}
