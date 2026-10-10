package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.Area
import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.AreaResolver
import systems.diath.homeclaim.core.model.AreaScope
import systems.diath.homeclaim.core.model.HomeClaimWorld
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.WorldId
import java.util.UUID

interface HomeClaimWorldService {
    fun getWorld(id: WorldId): HomeClaimWorld?
    fun getWorldByName(name: String): HomeClaimWorld?
    fun getWorldByPlatformId(platformWorldId: UUID): HomeClaimWorld?
    fun listWorlds(): List<HomeClaimWorld>
    fun createWorld(name: String, platformWorldId: UUID? = null): HomeClaimWorld
    fun bindPlatformWorld(id: WorldId, platformWorldId: UUID, currentName: String? = null): HomeClaimWorld
    fun renameWorld(id: WorldId, newName: String): HomeClaimWorld
}

interface AreaService {
    fun getArea(id: AreaId): Area?
    fun listAreas(worldId: WorldId): List<Area>
    fun resolveArea(worldId: WorldId, x: Int, y: Int, z: Int): Area?

    /** Name adapter for older Paper/config/API callers. */
    fun resolveArea(legacyWorldName: String, x: Int, y: Int, z: Int): Area?

    fun createArea(area: Area): Area
    fun updateArea(area: Area): Area
    fun deleteArea(id: AreaId)
}

interface WorldAreaService : HomeClaimWorldService, AreaService

/** In-memory implementation used by core tests and lightweight integrations. */
class InMemoryWorldAreaService : WorldAreaService {
    private val worlds = linkedMapOf<WorldId, HomeClaimWorld>()
    private val areas = linkedMapOf<AreaId, Area>()

    @Synchronized
    override fun getWorld(id: WorldId): HomeClaimWorld? = worlds[id]

    @Synchronized
    override fun getWorldByName(name: String): HomeClaimWorld? = worlds.values.singleOrNull { it.name == name }

    @Synchronized
    override fun getWorldByPlatformId(platformWorldId: UUID): HomeClaimWorld? =
        worlds.values.singleOrNull { it.platformWorldId == platformWorldId }

    @Synchronized
    override fun listWorlds(): List<HomeClaimWorld> = worlds.values.toList()

    @Synchronized
    override fun createWorld(name: String, platformWorldId: UUID?): HomeClaimWorld {
        require(worlds.values.none { it.name == name }) { "World name '$name' is already registered" }
        if (platformWorldId != null) {
            require(worlds.values.none { it.platformWorldId == platformWorldId }) { "Platform world is already bound" }
        }
        val world = HomeClaimWorld(WorldId.newId(), platformWorldId, name)
        worlds[world.id] = world
        return world
    }

    @Synchronized
    override fun bindPlatformWorld(id: WorldId, platformWorldId: UUID, currentName: String?): HomeClaimWorld {
        val existing = worlds[id] ?: throw NoSuchElementException("HomeClaim world ${id.value} not found")
        val other = getWorldByPlatformId(platformWorldId)
        require(other == null || other.id == id) { "Platform world is already bound to ${other?.id?.value}" }
        val updated = existing.bindPlatformWorld(platformWorldId).let { bound ->
            if (currentName != null) bound.renamed(currentName) else bound
        }
        worlds[id] = updated
        return updated
    }

    @Synchronized
    override fun renameWorld(id: WorldId, newName: String): HomeClaimWorld {
        require(worlds.values.none { it.id != id && it.name == newName }) { "World name '$newName' is already registered" }
        val renamed = (worlds[id] ?: throw NoSuchElementException("HomeClaim world ${id.value} not found")).renamed(newName)
        worlds[id] = renamed
        return renamed
    }

    @Synchronized
    override fun getArea(id: AreaId): Area? = areas[id]

    @Synchronized
    override fun listAreas(worldId: WorldId): List<Area> = areas.values.filter { it.worldId == worldId }

    @Synchronized
    override fun resolveArea(worldId: WorldId, x: Int, y: Int, z: Int): Area? =
        AreaResolver(listAreas(worldId)).resolveArea(worldId, x, y, z)

    @Synchronized
    override fun resolveArea(legacyWorldName: String, x: Int, y: Int, z: Int): Area? =
        getWorldByName(legacyWorldName)?.let { resolveArea(it.id, x, y, z) }
            ?: run {
                val legacyId = WorldId.fromLegacyName(legacyWorldName)
                resolveArea(legacyId, x, y, z)
            }

    @Synchronized
    override fun createArea(area: Area): Area {
        require(areas[area.id] == null) { "Area id ${area.id.value} already exists" }
        require(worlds.containsKey(area.worldId)) { "HomeClaim world ${area.worldId.value} not found" }
        require(areas.values.none { it.worldId == area.worldId && it.key == area.key }) {
            "Area key '${area.key}' already exists in world ${area.worldId.value}"
        }
        AreaResolver.requireNoEqualPriorityOverlap(area, listAreas(area.worldId))
        areas[area.id] = area
        return area
    }

    @Synchronized
    override fun updateArea(area: Area): Area {
        require(areas.containsKey(area.id)) { "Area ${area.id.value} not found" }
        require(worlds.containsKey(area.worldId)) { "HomeClaim world ${area.worldId.value} not found" }
        require(areas.values.none { it.id != area.id && it.worldId == area.worldId && it.key == area.key }) {
            "Area key '${area.key}' already exists in world ${area.worldId.value}"
        }
        AreaResolver.requireNoEqualPriorityOverlap(area, listAreas(area.worldId).filterNot { it.id == area.id })
        areas[area.id] = area
        return area
    }

    @Synchronized
    override fun deleteArea(id: AreaId) {
        areas.remove(id)
    }

    @Synchronized
    fun ensureLegacyWorld(name: String): HomeClaimWorld {
        val id = WorldId.fromLegacyName(name)
        return worlds[id] ?: HomeClaimWorld(id = id, name = name).also { worlds[id] = it }
    }

    @Synchronized
    fun ensureLegacyArea(worldName: String, scope: AreaScope = AreaScope.WholeWorld): Area {
        val world = ensureLegacyWorld(worldName)
        val id = AreaId.legacyDefault(world.id)
        return areas[id] ?: Area(id, world.id, "default", scope).also { areas[id] = it }
    }
}
