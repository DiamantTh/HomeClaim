package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.FlagKey
import systems.diath.homeclaim.core.model.LimitKey
import systems.diath.homeclaim.core.model.PolicyValue
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.RegionShape
import systems.diath.homeclaim.core.model.Zone
import systems.diath.homeclaim.core.model.ZoneDefaults
import systems.diath.homeclaim.core.model.ZoneId
import systems.diath.homeclaim.core.service.ZoneService
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

class JdbcZoneRepository(
    private val dataSource: DataSource,
    private val worldAreaRepository: JdbcWorldAreaRepository? = null
) : ZoneService {
    /** Startup-loaded area/chunk index for policy resolution without JDBC event queries. */
    private val runtimeIndex = AtomicReference(ZoneRuntimeIndex.EMPTY)

    init {
        reloadRuntimeIndex()
    }

    override fun getZonesAt(areaId: AreaId, position: Position): List<Zone> {
        return runtimeIndex.get().findAt(areaId, position)
    }

    override fun listZones(areaId: AreaId): List<Zone> = runtimeIndex.get().zonesByArea[areaId].orEmpty()

    override fun getZoneById(zoneId: ZoneId): Zone? = runtimeIndex.get().zones[zoneId]

    override fun createZone(zone: Zone): ZoneId {
        if (zone.areaId == AreaId.legacyDefault(systems.diath.homeclaim.core.model.WorldId.fromLegacyName(zone.world))) {
            worldAreaRepository?.ensureLegacyArea(zone.world)
        }
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO zones (id, world, area_id, shape, min_x, max_x, min_y, max_y, min_z, max_z, priority, locked_flags, tags, default_flags, default_limits, allowed_trigger_blocks)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).apply {
                setObject(1, zone.id.value)
                setString(2, zone.world)
                setString(3, zone.areaId.value.toString())
                setString(4, zone.shape.name)
                setInt(5, zone.bounds.minX)
                setInt(6, zone.bounds.maxX)
                setInt(7, zone.bounds.minY)
                setInt(8, zone.bounds.maxY)
                setInt(9, zone.bounds.minZ)
                setInt(10, zone.bounds.maxZ)
                setInt(11, zone.priority)
                setString(12, Serialization.toJson(zone.lockedFlags.map { it.value }))
                setString(13, Serialization.toJson(zone.tags))
                setString(14, Serialization.toJson(zone.defaults.defaultFlags.mapKeys { it.key.value }))
                setString(15, Serialization.toJson(zone.defaults.defaultLimits.mapKeys { it.key.value }))
                setString(16, Serialization.toJson(zone.defaults.allowedTriggerBlocks))
            }.executeUpdate()
        }
        replaceCachedZones { it + (zone.id to zone) }
        return zone.id
    }

    override fun deleteZone(zoneId: ZoneId) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM zones WHERE id = ?").apply {
                setObject(1, zoneId.value)
            }.executeUpdate()
        }
        replaceCachedZones { it - zoneId }
    }

    private fun reloadRuntimeIndex() {
        val loaded = dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM zones").use { statement ->
                statement.executeQuery().use { rs ->
                    buildMap {
                        while (rs.next()) {
                            val zone = rs.toZone()
                            put(zone.id, zone)
                        }
                    }
                }
            }
        }
        runtimeIndex.set(ZoneRuntimeIndex.build(loaded))
    }

    private fun replaceCachedZones(transform: (Map<ZoneId, Zone>) -> Map<ZoneId, Zone>) {
        runtimeIndex.updateAndGet { current -> ZoneRuntimeIndex.build(transform(current.zones)) }
    }

    private fun ResultSet.toZone(): Zone {
        val locked = Serialization.mapper.readValue(getString("locked_flags"), List::class.java)
            .map { FlagKey.trusted(it.toString()) }.toSet()
        val defaultFlagsMap: Map<String, PolicyValue> = Serialization.fromJson(getString("default_flags"))
        val defaultLimitsMap: Map<String, PolicyValue> = Serialization.fromJson(getString("default_limits"))
        return Zone(
            id = ZoneId(UUID.fromString(getString("id"))),
            world = getString("world"),
            areaId = AreaId(UUID.fromString(getString("area_id"))),
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

    private class ZoneRuntimeIndex private constructor(
        val zones: Map<ZoneId, Zone>,
        val zonesByArea: Map<AreaId, List<Zone>>,
        private val byChunk: Map<AreaId, Map<Long, List<ZoneId>>>,
        private val wideZones: Map<AreaId, List<ZoneId>>
    ) {
        fun findAt(areaId: AreaId, position: Position): List<Zone> {
            val indexed = byChunk[areaId]?.get(chunkKey(position.x shr 4, position.z shr 4)).orEmpty()
            val wide = wideZones[areaId].orEmpty()
            if (indexed.isEmpty() && wide.isEmpty()) return emptyList()
            val matches = ArrayList<Zone>(indexed.size.coerceAtMost(8) + wide.size)
            for (id in indexed) zones[id]?.takeIf { it.appliesTo(position) }?.let(matches::add)
            for (id in wide) zones[id]?.takeIf { it.appliesTo(position) }?.let(matches::add)
            return matches
        }

        companion object {
            private const val MAX_INDEXED_CHUNKS_PER_ZONE = 2048L
            val EMPTY = ZoneRuntimeIndex(emptyMap(), emptyMap(), emptyMap(), emptyMap())

            fun build(zones: Map<ZoneId, Zone>): ZoneRuntimeIndex {
                val mutableChunks = HashMap<AreaId, HashMap<Long, MutableList<ZoneId>>>()
                val mutableWide = HashMap<AreaId, MutableList<ZoneId>>()
                for (zone in zones.values) {
                    val minChunkX = zone.bounds.minX shr 4
                    val maxChunkX = zone.bounds.maxX shr 4
                    val minChunkZ = zone.bounds.minZ shr 4
                    val maxChunkZ = zone.bounds.maxZ shr 4
                    val count = (maxChunkX.toLong() - minChunkX + 1) * (maxChunkZ.toLong() - minChunkZ + 1)
                    if (count > MAX_INDEXED_CHUNKS_PER_ZONE) {
                        mutableWide.getOrPut(zone.areaId, ::mutableListOf).add(zone.id)
                        continue
                    }
                    val chunks = mutableChunks.getOrPut(zone.areaId, ::HashMap)
                    for (chunkX in minChunkX..maxChunkX) {
                        for (chunkZ in minChunkZ..maxChunkZ) {
                            chunks.getOrPut(chunkKey(chunkX, chunkZ), ::mutableListOf).add(zone.id)
                        }
                    }
                }
                val compareZones: (ZoneId, ZoneId) -> Int = { left, right ->
                    val priority = zones.getValue(right).priority.compareTo(zones.getValue(left).priority)
                    if (priority != 0) priority else left.value.compareTo(right.value)
                }
                val sortedChunks = mutableChunks.mapValues { (_, chunks) ->
                    chunks.mapValues { (_, ids) -> ids.sortedWith(compareZones) }
                }
                val sortedWide = mutableWide.mapValues { (_, ids) -> ids.sortedWith(compareZones) }
                val zonesByArea = zones.values.groupBy(Zone::areaId).mapValues { (_, list) -> list.sortedWith(
                    compareByDescending<Zone> { it.priority }.thenBy { it.id.value }
                ) }
                return ZoneRuntimeIndex(zones.toMap(), zonesByArea, sortedChunks, sortedWide)
            }

            private fun chunkKey(x: Int, z: Int): Long = (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)
        }
    }
}
