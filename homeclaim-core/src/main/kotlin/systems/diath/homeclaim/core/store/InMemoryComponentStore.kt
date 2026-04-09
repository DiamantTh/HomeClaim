package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.model.Component
import systems.diath.homeclaim.core.model.ComponentConfig
import systems.diath.homeclaim.core.model.ComponentId
import systems.diath.homeclaim.core.model.ComponentPolicy
import systems.diath.homeclaim.core.model.ComponentState
import systems.diath.homeclaim.core.model.ComponentType
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.service.ComponentService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemoryComponentStore : ComponentService {
    private val components = ConcurrentHashMap<ComponentId, Component>()
    private val byRegion = ConcurrentHashMap<RegionId, MutableSet<ComponentId>>()
    private val chunkIndex = ConcurrentHashMap<WorldId, MutableMap<Pair<Int, Int>, MutableSet<ComponentId>>>()

    override fun createComponent(
        regionId: RegionId,
        type: ComponentType,
        position: Position,
        config: ComponentConfig,
        policy: ComponentPolicy
    ): ComponentId {
        val id = ComponentId(UUID.randomUUID())
        val component = Component(
            id = id,
            regionId = regionId,
            type = type,
            position = position,
            policy = policy,
            config = config,
            createdBy = UUID.randomUUID()
        )
        components[id] = component
        byRegion.computeIfAbsent(regionId) { mutableSetOf() }.add(id)
        indexComponent(component)
        return id
    }

    override fun getComponentAt(position: Position): Component? {
        val chunkX = position.x shr 4
        val chunkZ = position.z shr 4
        val ids = chunkIndex[position.world]?.get(chunkX to chunkZ).orEmpty()
        return ids.mapNotNull { components[it] }.firstOrNull { it.position == position }
    }

    override fun listComponents(regionId: RegionId): List<Component> =
        byRegion[regionId].orEmpty().mapNotNull { components[it] }

    override fun updateComponent(componentId: ComponentId, config: ComponentConfig?, policy: ComponentPolicy?, state: ComponentState?) {
        val current = components[componentId] ?: return
        val updated = current.copy(
            config = config ?: current.config,
            policy = policy ?: current.policy,
            state = state ?: current.state,
            updatedAt = java.time.Instant.now()
        )
        components[componentId] = updated
    }

    override fun deleteComponent(componentId: ComponentId) {
        val removed = components.remove(componentId) ?: return
        byRegion[removed.regionId]?.remove(componentId)
        deindexComponent(removed)
    }

    override fun indexByChunk(world: String, chunkX: Int, chunkZ: Int): List<ComponentId> =
        chunkIndex[world]?.get(chunkX to chunkZ)?.toList().orEmpty()

    private fun indexComponent(component: Component) {
        val chunkX = component.position.x shr 4
        val chunkZ = component.position.z shr 4
        val worldIndex = chunkIndex.computeIfAbsent(component.position.world) { mutableMapOf() }
        worldIndex.computeIfAbsent(chunkX to chunkZ) { mutableSetOf() }.add(component.id)
    }

    private fun deindexComponent(component: Component) {
        val chunkX = component.position.x shr 4
        val chunkZ = component.position.z shr 4
        chunkIndex[component.position.world]?.get(chunkX to chunkZ)?.remove(component.id)
    }
}
