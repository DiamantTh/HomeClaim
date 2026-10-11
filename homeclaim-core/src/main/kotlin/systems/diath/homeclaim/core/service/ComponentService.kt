package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.Component
import systems.diath.homeclaim.core.model.ComponentConfig
import systems.diath.homeclaim.core.model.ComponentId
import systems.diath.homeclaim.core.model.ComponentPolicy
import systems.diath.homeclaim.core.model.ComponentState
import systems.diath.homeclaim.core.model.ComponentType
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.RegionId

interface ComponentService {
    fun createComponent(
        regionId: RegionId,
        type: ComponentType,
        position: Position,
        config: ComponentConfig,
        policy: ComponentPolicy
    ): ComponentId

    /** Actor-aware creation; legacy implementations may temporarily delegate to the old method. */
    fun createComponent(
        regionId: RegionId,
        type: ComponentType,
        position: Position,
        config: ComponentConfig,
        policy: ComponentPolicy,
        createdBy: PlayerId
    ): ComponentId = createComponent(regionId, type, position, config, policy)

    fun getComponentAt(position: Position): Component?
    fun getComponent(id: ComponentId): Component? = null
    fun listComponents(regionId: RegionId): List<Component>
    fun updateComponent(componentId: ComponentId, config: ComponentConfig? = null, policy: ComponentPolicy? = null, state: ComponentState? = null)
    fun deleteComponent(componentId: ComponentId)
    fun indexByChunk(world: String, chunkX: Int, chunkZ: Int): List<ComponentId>
}
