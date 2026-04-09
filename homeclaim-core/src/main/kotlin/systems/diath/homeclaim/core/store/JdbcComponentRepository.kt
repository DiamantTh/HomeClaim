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
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcComponentRepository(
    private val dataSource: DataSource
) : ComponentService {
    override fun createComponent(
        regionId: RegionId,
        type: ComponentType,
        position: Position,
        config: ComponentConfig,
        policy: ComponentPolicy
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
                setString(9, Serialization.toJson(policy))
                setString(10, Serialization.toJson(config))
                setObject(11, UUID(0, 0))
            }.executeUpdate()
        }
        return id
    }

    override fun getComponentAt(position: Position): Component? {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT * FROM components
                WHERE world = ? AND x = ? AND y = ? AND z = ?
                LIMIT 1
                """.trimIndent()
            ).apply {
                setString(1, position.world)
                setInt(2, position.x)
                setInt(3, position.y)
                setInt(4, position.z)
            }.executeQuery().use { rs ->
                return if (rs.next()) rs.toComponent() else null
            }
        }
    }

    override fun listComponents(regionId: RegionId): List<Component> {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM components WHERE region_id = ?").apply {
                setObject(1, regionId.value)
            }.executeQuery().use { rs ->
                val result = mutableListOf<Component>()
                while (rs.next()) result += rs.toComponent()
                return result
            }
        }
    }

    override fun updateComponent(componentId: ComponentId, config: ComponentConfig?, policy: ComponentPolicy?, state: ComponentState?) {
        if (config == null && policy == null && state == null) return
        val assignments = mutableListOf<String>()
        val params = mutableListOf<Any>()
        config?.let { assignments += "config = ?"; params += Serialization.toJson(it) }
        policy?.let { assignments += "policy = ?"; params += Serialization.toJson(it) }
        state?.let { assignments += "state = ?"; params += it.name }
        assignments += "updated_at = now()"

        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE components SET ${assignments.joinToString(", ")} WHERE id = ?"
            ).apply {
                params.forEachIndexed { idx, value -> setObject(idx + 1, value) }
                setObject(params.size + 1, componentId.value)
            }.executeUpdate()
        }
    }

    override fun deleteComponent(componentId: ComponentId) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM components WHERE id = ?").apply {
                setObject(1, componentId.value)
            }.executeUpdate()
        }
    }

    override fun indexByChunk(world: String, chunkX: Int, chunkZ: Int): List<ComponentId> {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id FROM components
                WHERE world = ?
                  AND (x >> 4) = ?
                  AND (z >> 4) = ?
                """.trimIndent()
            ).apply {
                setString(1, world)
                setInt(2, chunkX)
                setInt(3, chunkZ)
            }.executeQuery().use { rs ->
                val ids = mutableListOf<ComponentId>()
                while (rs.next()) ids += ComponentId(UUID.fromString(rs.getString("id")))
                return ids
            }
        }
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
            createdBy = getObject("created_by", UUID::class.java),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant()
        )
    }
}
