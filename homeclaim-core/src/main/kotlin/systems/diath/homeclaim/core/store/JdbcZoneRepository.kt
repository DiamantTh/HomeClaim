package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.FlagKey
import systems.diath.homeclaim.core.model.LimitKey
import systems.diath.homeclaim.core.model.PolicyValue
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.RegionShape
import systems.diath.homeclaim.core.model.Zone
import systems.diath.homeclaim.core.model.ZoneDefaults
import systems.diath.homeclaim.core.model.ZoneId
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.service.ZoneService
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

class JdbcZoneRepository(
    private val dataSource: DataSource
) : ZoneService {
    override fun getZonesAt(world: WorldId, position: Position): List<Zone> {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT * FROM zones
                WHERE world = ?
                  AND ? BETWEEN min_x AND max_x
                  AND ? BETWEEN min_y AND max_y
                  AND ? BETWEEN min_z AND max_z
                """.trimIndent()
            ).apply {
                setString(1, world)
                setInt(2, position.x)
                setInt(3, position.y)
                setInt(4, position.z)
            }.executeQuery().use { rs ->
                val zones = mutableListOf<Zone>()
                while (rs.next()) zones += rs.toZone()
                return zones
            }
        }
    }

    override fun listZones(world: WorldId): List<Zone> {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM zones WHERE world = ?").apply {
                setString(1, world)
            }.executeQuery().use { rs ->
                val zones = mutableListOf<Zone>()
                while (rs.next()) zones += rs.toZone()
                return zones
            }
        }
    }

    override fun getZoneById(zoneId: ZoneId): Zone? {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM zones WHERE id = ?").apply {
                setObject(1, zoneId.value)
            }.executeQuery().use { rs ->
                return if (rs.next()) rs.toZone() else null
            }
        }
    }

    override fun createZone(zone: Zone): ZoneId {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO zones (id, world, shape, min_x, max_x, min_y, max_y, min_z, max_z, priority, locked_flags, tags, default_flags, default_limits, allowed_trigger_blocks)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).apply {
                setObject(1, zone.id.value)
                setString(2, zone.world)
                setString(3, zone.shape.name)
                setInt(4, zone.bounds.minX)
                setInt(5, zone.bounds.maxX)
                setInt(6, zone.bounds.minY)
                setInt(7, zone.bounds.maxY)
                setInt(8, zone.bounds.minZ)
                setInt(9, zone.bounds.maxZ)
                setInt(10, zone.priority)
                setString(11, Serialization.toJson(zone.lockedFlags.map { it.value }))
                setString(12, Serialization.toJson(zone.tags))
                setString(13, Serialization.toJson(zone.defaults.defaultFlags.mapKeys { it.key.value }))
                setString(14, Serialization.toJson(zone.defaults.defaultLimits.mapKeys { it.key.value }))
                setString(15, Serialization.toJson(zone.defaults.allowedTriggerBlocks))
            }.executeUpdate()
        }
        return zone.id
    }

    override fun deleteZone(zoneId: ZoneId) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM zones WHERE id = ?").apply {
                setObject(1, zoneId.value)
            }.executeUpdate()
        }
    }

    private fun ResultSet.toZone(): Zone {
        val locked = Serialization.mapper.readValue(getString("locked_flags"), List::class.java)
            .map { FlagKey.trusted(it.toString()) }.toSet()
        val defaultFlagsMap: Map<String, PolicyValue> = Serialization.fromJson(getString("default_flags"))
        val defaultLimitsMap: Map<String, PolicyValue> = Serialization.fromJson(getString("default_limits"))
        return Zone(
            id = ZoneId(UUID.fromString(getString("id"))),
            world = getString("world"),
            shape = RegionShape.valueOf(getString("shape")),
            bounds = Bounds(
                minX = getInt("min_x"),
                maxX = getInt("max_x"),
                minY = getInt("min_y"),
                maxY = getInt("max_y"),
                minZ = getInt("min_z"),
                maxZ = getInt("max_z")
            ),
            priority = getInt("priority"),
            defaults = ZoneDefaults(
                defaultFlags = defaultFlagsMap.mapKeys { FlagKey.trusted(it.key) },
                defaultLimits = defaultLimitsMap.mapKeys { LimitKey.trusted(it.key) },
                allowedTriggerBlocks = Serialization.fromJson(getString("allowed_trigger_blocks"))
            ),
            lockedFlags = locked,
            tags = Serialization.fromJson(getString("tags"))
        )
    }
}
