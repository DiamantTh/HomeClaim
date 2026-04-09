package systems.diath.homeclaim.liftlink

import systems.diath.homeclaim.core.model.Component
import systems.diath.homeclaim.core.model.ComponentConfig
import systems.diath.homeclaim.core.model.ComponentId
import systems.diath.homeclaim.core.model.ComponentPolicy
import systems.diath.homeclaim.core.model.ComponentState
import systems.diath.homeclaim.core.model.ComponentType
import systems.diath.homeclaim.core.model.ElevatorConfig
import systems.diath.homeclaim.core.model.ElevatorMode
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.service.ComponentService
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class LiftLinkPlannerTest {
    private val regionId = RegionId(UUID.randomUUID())
    private val origin = Position("world", 0, 64, 0)
    private val components = mutableListOf<Component>()
    private val componentService = MemoryComponentService(components)
    private val planner = LiftLinkPlanner(componentService)

    @Test
    fun `filters pads by type, state, and range`() {
        val inRangePad = component(ComponentType.ELEVATOR_PAD, Position("world", 0, 68, 0))
        val farPad = component(ComponentType.ELEVATOR_PAD, Position("world", 0, 90, 0))
        val disabled = component(ComponentType.ELEVATOR_PAD, Position("world", 0, 60, 0), state = ComponentState.DISABLED)
        val otherType = component(ComponentType.TELEPORT_PAD, Position("world", 0, 65, 0))

        components += listOf(inRangePad, farPad, disabled, otherType)

        val result = planner.findNearbyPads(regionId, origin, ElevatorConfig(mode = ElevatorMode.VERTICAL, rangeBlocks = 12))
        assertEquals(listOf(inRangePad), result)
    }

    private fun component(
        type: ComponentType,
        position: Position,
        state: ComponentState = ComponentState.ENABLED,
        config: ComponentConfig = ElevatorConfig()
    ) = Component(
        id = ComponentId(UUID.randomUUID()),
        regionId = regionId,
        type = type,
        position = position,
        state = state,
        policy = ComponentPolicy(),
        config = config,
        createdBy = UUID.randomUUID(),
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )
}

private class MemoryComponentService(
    private val storage: MutableList<Component>
) : ComponentService {
    override fun createComponent(
        regionId: RegionId,
        type: ComponentType,
        position: Position,
        config: ComponentConfig,
        policy: ComponentPolicy
    ) = ComponentId(UUID.randomUUID()).also { id ->
        storage += Component(
            id = id,
            regionId = regionId,
            type = type,
            position = position,
            state = ComponentState.ENABLED,
            policy = policy,
            config = config,
            createdBy = UUID.randomUUID(),
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
    }

    override fun getComponentAt(position: Position) = storage.find { it.position == position }
    override fun listComponents(regionId: RegionId) = storage.filter { it.regionId == regionId }
    override fun updateComponent(componentId: ComponentId, config: ComponentConfig?, policy: ComponentPolicy?, state: ComponentState?) {}
    override fun deleteComponent(componentId: ComponentId) {}
    override fun indexByChunk(world: WorldId, chunkX: Int, chunkZ: Int): List<ComponentId> = emptyList()
}
