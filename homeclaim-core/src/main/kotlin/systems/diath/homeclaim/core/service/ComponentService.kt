package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.Component
import systems.diath.homeclaim.core.model.ComponentConfig
import systems.diath.homeclaim.core.model.ComponentId
import systems.diath.homeclaim.core.model.ComponentPolicy
import systems.diath.homeclaim.core.model.ComponentState
import systems.diath.homeclaim.core.model.ComponentType
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.RegionId

interface ComponentService {
    fun createComponent(
        regionId: RegionId,
        type: ComponentType,
        position: Position,
        config: ComponentConfig,
        policy: ComponentPolicy
    ): ComponentId

    fun getComponentAt(position: Position): Component?
    fun listComponents(regionId: RegionId): List<Component>
    fun updateComponent(componentId: ComponentId, config: ComponentConfig? = null, policy: ComponentPolicy? = null, state: ComponentState? = null)
    fun deleteComponent(componentId: ComponentId)
    fun indexByChunk(world: String, chunkX: Int, chunkZ: Int): List<ComponentId>
}
