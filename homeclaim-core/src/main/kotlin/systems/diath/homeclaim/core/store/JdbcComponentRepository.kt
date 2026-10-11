package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.model.Component
import systems.diath.homeclaim.core.model.ComponentConfig
import systems.diath.homeclaim.core.model.ComponentId
import systems.diath.homeclaim.core.model.ComponentPolicy
import systems.diath.homeclaim.core.model.ComponentState
import systems.diath.homeclaim.core.model.ComponentType
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.service.ComponentService
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

class JdbcComponentRepository(
    private val dataSource: DataSource
) : ComponentService {
    private val runtimeSnapshot = AtomicReference(ComponentRuntimeSnapshot.EMPTY)

    init {
        reloadRuntimeSnapshot()
    }

    override fun createComponent(
        regionId: RegionId,
        type: ComponentType,
        position: Position,
        config: ComponentConfig,
        policy: ComponentPolicy
    ): ComponentId = createComponent(regionId, type, position, config, policy, UUID(0, 0))

    override fun createComponent(
        regionId: RegionId,
        type: ComponentType,
        position: Position,
        config: ComponentConfig,
        policy: ComponentPolicy,
        createdBy: PlayerId
    ): ComponentId {
        val id = ComponentId(UUID.randomUUID())
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO components (id, region_id, type, world, x, y, z, state, policy, config, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).apply {
                setObject(1, id.value)
                setObject(2, regionId.value)
                setString(3, type.name)
                setString(4, position.world)
                setInt(5, position.x)
                setInt(6, position.y)
                setInt(7, position.z)
                setString(8, ComponentState.ENABLED.name)
                setJsonString(9, Serialization.toJson(policy))
                setJsonString(10, Serialization.toJson(config))
                setObject(11, createdBy)
            }.executeUpdate()
        }
        val component = Component(
            id = id,
            regionId = regionId,
            type = type,
            position = Position(position.worldId, position.x, position.y, position.z, position.legacyWorldName),
            state = ComponentState.ENABLED,
            policy = policy,
            config = config,
            createdBy = createdBy
        )
        replaceCachedComponents { it + (id to component) }
        return id
    }

    override fun getComponentAt(position: Position): Component? = runtimeSnapshot.get().at(position)

    override fun getComponent(id: systems.diath.homeclaim.core.model.ComponentId): Component? =
        runtimeSnapshot.get().components[id]

    override fun listComponents(regionId: RegionId): List<Component> = runtimeSnapshot.get().byRegion[regionId].orEmpty()

    override fun updateComponent(componentId: ComponentId, config: ComponentConfig?, policy: ComponentPolicy?, state: ComponentState?) {
        if (config == null && policy == null && state == null) return
        val assignments = mutableListOf<String>()
        val params = mutableListOf<Pair<Any, Boolean>>()
        config?.let { assignments += "config = ?"; params += Serialization.toJson(it) to true }
        policy?.let { assignments += "policy = ?"; params += Serialization.toJson(it) to true }
        state?.let { assignments += "state = ?"; params += it.name to false }
        assignments += "updated_at = CURRENT_TIMESTAMP"

        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE components SET ${assignments.joinToString(", ")} WHERE id = ?"
            ).apply {
                params.forEachIndexed { idx, (value, isJson) ->
                    if (isJson) setJsonString(idx + 1, value as String) else setObject(idx + 1, value)
                }
                setObject(params.size + 1, componentId.value)
            }.executeUpdate()
        }
        runtimeSnapshot.get().components[componentId]?.let { current ->
            replaceCachedComponents { components ->
                components + (componentId to current.copy(
                    config = config ?: current.config,
                    policy = policy ?: current.policy,
                    state = state ?: current.state,
                    updatedAt = Instant.now()
                ))
            }
        }
    }

    override fun deleteComponent(componentId: ComponentId) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM components WHERE id = ?").apply {
                setObject(1, componentId.value)
            }.executeUpdate()
        }
        replaceCachedComponents { it - componentId }
    }

    override fun indexByChunk(world: String, chunkX: Int, chunkZ: Int): List<ComponentId> =
        runtimeSnapshot.get().byWorldChunk[world]?.get(chunkKey(chunkX, chunkZ)).orEmpty()

    private fun reloadRuntimeSnapshot() {
        val loaded = dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM components").use { statement ->
                statement.executeQuery().use { rs ->
                    buildMap {
                        while (rs.next()) {
                            val component = rs.toComponent()
                            put(component.id, component)
                        }
                    }
                }
            }
        }
        runtimeSnapshot.set(ComponentRuntimeSnapshot.build(loaded))
    }

    private fun replaceCachedComponents(transform: (Map<ComponentId, Component>) -> Map<ComponentId, Component>) {
        runtimeSnapshot.updateAndGet { current -> ComponentRuntimeSnapshot.build(transform(current.components)) }
    }

    private fun ResultSet.toComponent(): Component {
        return Component(
            id = ComponentId(UUID.fromString(getString("id"))),
            regionId = RegionId(UUID.fromString(getString("region_id"))),
            type = ComponentType.valueOf(getString("type")),
            position = Position(
                world = getString("world"),
                x = getInt("x"),
                y = getInt("y"),
                z = getInt("z")
            ),
            state = ComponentState.valueOf(getString("state")),
            policy = Serialization.fromJson(getString("policy")),
            config = Serialization.fromJson(getString("config")),
            createdBy = getRequiredUuid("created_by"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant()
        )
    }

    private class ComponentRuntimeSnapshot private constructor(
        val components: Map<ComponentId, Component>,
        val byRegion: Map<RegionId, List<Component>>,
        private val byPosition: Map<ComponentPositionKey, Component>,
        val byWorldChunk: Map<String, Map<Long, List<ComponentId>>>
    ) {
        fun at(position: Position): Component? {
            val world = position.legacyWorldName ?: position.worldId.value.toString()
            return byPosition[ComponentPositionKey(world, position.x, position.y, position.z)]
        }

        companion object {
            val EMPTY = ComponentRuntimeSnapshot(emptyMap(), emptyMap(), emptyMap(), emptyMap())

            fun build(components: Map<ComponentId, Component>): ComponentRuntimeSnapshot {
                val byPosition = HashMap<ComponentPositionKey, Component>()
                val byWorldChunk = HashMap<String, HashMap<Long, MutableList<ComponentId>>>()
                for (component in components.values) {
                    val world = component.position.legacyWorldName ?: component.position.worldId.value.toString()
                    byPosition[ComponentPositionKey(world, component.position.x, component.position.y, component.position.z)] = component
                    byWorldChunk.getOrPut(world, ::HashMap)
                        .getOrPut(chunkKey(component.position.x shr 4, component.position.z shr 4), ::mutableListOf)
                        .add(component.id)
                }
                val regions = components.values.groupBy(Component::regionId)
                val chunks = byWorldChunk.mapValues { (_, entries) ->
                    entries.mapValues { (_, ids) -> ids.sortedBy { it.value } }
                }
                return ComponentRuntimeSnapshot(components.toMap(), regions, byPosition, chunks)
            }

            private fun chunkKey(x: Int, z: Int): Long = (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)
        }
    }

    private data class ComponentPositionKey(val world: String, val x: Int, val y: Int, val z: Int)

    private companion object {
        fun chunkKey(x: Int, z: Int): Long = (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)
    }
}
