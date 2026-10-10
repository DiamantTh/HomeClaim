package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.model.Area
import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.AreaResolver
import systems.diath.homeclaim.core.model.AreaScope
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.HomeClaimWorld
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.service.WorldAreaService
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

class JdbcWorldAreaRepository(private val dataSource: DataSource) : WorldAreaService {
    private val worldSnapshot = AtomicReference<List<HomeClaimWorld>?>(null)
    private val areaSnapshot = ConcurrentHashMap<WorldId, List<Area>>()
    private val resolverSnapshot = ConcurrentHashMap<WorldId, AreaResolver>()

    /** Loads all spatial scope data before Paper protection listeners begin receiving events. */
    fun warmRuntimeCache() {
        listWorlds().forEach { world -> resolveArea(world.id, 0, 0, 0) }
    }
    /**
     * Makes the V5 storage contract available even on installations where Flyway's current
     * configuration deliberately skips non-PostgreSQL dialects. The matching Flyway migration
     * remains the canonical versioned DDL when migrations are enabled.
     */
    fun ensureSchema() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { statement ->
                statement.execute(
                    """CREATE TABLE IF NOT EXISTS worlds (
                        id UUID PRIMARY KEY,
                        platform_uuid UUID UNIQUE,
                        name VARCHAR(128) NOT NULL,
                        metadata JSON
                    )""".trimIndent()
                )
                statement.execute(
                    """CREATE TABLE IF NOT EXISTS areas (
                        id UUID PRIMARY KEY,
                        world_id UUID NOT NULL REFERENCES worlds(id) ON DELETE CASCADE,
                        area_key VARCHAR(128) NOT NULL,
                        whole_world BOOLEAN NOT NULL DEFAULT FALSE,
                        min_x INT, max_x INT, min_y INT, max_y INT,
                        min_z INT, max_z INT,
                        priority INT NOT NULL DEFAULT 0,
                        metadata JSON,
                        CONSTRAINT uq_areas_world_key UNIQUE(world_id, area_key),
                        CONSTRAINT ck_area_scope CHECK (
                            (whole_world = TRUE AND min_x IS NULL AND max_x IS NULL AND min_y IS NULL AND max_y IS NULL AND min_z IS NULL AND max_z IS NULL)
                            OR
                            (whole_world = FALSE AND min_x IS NOT NULL AND max_x IS NOT NULL AND min_y IS NOT NULL AND max_y IS NOT NULL AND min_z IS NOT NULL AND max_z IS NOT NULL)
                        )
                    )""".trimIndent()
                )
            }
            ensureColumn(conn, "regions", "area_id", "UUID REFERENCES areas(id)")
            ensureColumn(conn, "zones", "area_id", "UUID REFERENCES areas(id)")
        }
    }

    override fun getWorld(id: WorldId): HomeClaimWorld? = dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT * FROM worlds WHERE id = ?").use { ps ->
            ps.setObject(1, id.value)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toWorld() else null }
        }
    }

    override fun getWorldByName(name: String): HomeClaimWorld? = dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT * FROM worlds WHERE name = ? ORDER BY id").use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null
                else {
                    val match = rs.toWorld()
                    if (rs.next()) null else match
                }
            }
        }
    }

    override fun getWorldByPlatformId(platformWorldId: UUID): HomeClaimWorld? = dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT * FROM worlds WHERE platform_uuid = ?").use { ps ->
            ps.setObject(1, platformWorldId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toWorld() else null }
        }
    }

    override fun listWorlds(): List<HomeClaimWorld> = worldSnapshot.get() ?: dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT * FROM worlds ORDER BY name, id").use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toWorld()) } }
        }
    }.also { loaded -> worldSnapshot.compareAndSet(null, loaded) }

    override fun createWorld(name: String, platformWorldId: UUID?): HomeClaimWorld {
        val world = HomeClaimWorld(WorldId.newId(), platformWorldId, name)
        dataSource.connection.use { conn ->
            conn.prepareStatement("INSERT INTO worlds (id, platform_uuid, name, metadata) VALUES (?, ?, ?, ?)").use { ps ->
                ps.setObject(1, world.id.value)
                ps.setObject(2, platformWorldId)
                ps.setString(3, name)
                ps.setJsonString(4, "{}")
                ps.executeUpdate()
            }
        }
        invalidateWorlds()
        return world
    }

    override fun bindPlatformWorld(id: WorldId, platformWorldId: UUID, currentName: String?): HomeClaimWorld =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val current = conn.selectWorld(id) ?: throw NoSuchElementException("HomeClaim world ${id.value} not found")
                val other = conn.selectWorldByPlatformId(platformWorldId)
                require(other == null || other.id == id) { "Platform world is already bound to ${other?.id?.value}" }
                val bound = current.bindPlatformWorld(platformWorldId).let { value ->
                    if (currentName == null) value else value.renamed(currentName)
                }
                conn.prepareStatement("UPDATE worlds SET platform_uuid = ?, name = ? WHERE id = ?").use { ps ->
                    ps.setObject(1, platformWorldId)
                    ps.setString(2, bound.name)
                    ps.setObject(3, id.value)
                    ps.executeUpdate()
                }
                conn.commit()
                invalidateWorlds()
                bound
            } catch (failure: Throwable) {
                conn.rollback()
                throw failure
            } finally {
                conn.autoCommit = true
            }
        }

    override fun renameWorld(id: WorldId, newName: String): HomeClaimWorld = dataSource.connection.use { conn ->
        conn.prepareStatement("UPDATE worlds SET name = ? WHERE id = ?").use { ps ->
            ps.setString(1, newName)
            ps.setObject(2, id.value)
            if (ps.executeUpdate() == 0) throw NoSuchElementException("HomeClaim world ${id.value} not found")
        }
        getWorld(id)!!
    }.also { invalidateWorlds() }

    override fun getArea(id: AreaId): Area? = dataSource.connection.use { conn -> conn.selectArea(id) }

    override fun listAreas(worldId: WorldId): List<Area> = areaSnapshot[worldId] ?: dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT * FROM areas WHERE world_id = ? ORDER BY priority DESC, id").use { ps ->
            ps.setObject(1, worldId.value)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toArea()) } }
        }
    }.also { loaded -> areaSnapshot.putIfAbsent(worldId, loaded) }

    override fun resolveArea(worldId: WorldId, x: Int, y: Int, z: Int): Area? =
        resolverSnapshot.computeIfAbsent(worldId) { AreaResolver(listAreas(worldId)) }
            .resolveArea(worldId, x, y, z)

    override fun resolveArea(legacyWorldName: String, x: Int, y: Int, z: Int): Area? {
        val world = listWorlds().singleOrNull { it.name == legacyWorldName } ?: return null
        return resolveArea(world.id, x, y, z)
    }

    override fun createArea(area: Area): Area {
        validateArea(area, excludedId = null)
        dataSource.connection.use { conn -> conn.insertArea(area) }
        invalidateAreas(area.worldId)
        return area
    }

    override fun updateArea(area: Area): Area {
        val previousArea = getArea(area.id) ?: throw NoSuchElementException("Area ${area.id.value} not found")
        validateArea(area, excludedId = area.id)
        dataSource.connection.use { conn ->
            val scope = area.scope.sqlValues()
            conn.prepareStatement(
                "UPDATE areas SET world_id = ?, area_key = ?, whole_world = ?, min_x = ?, max_x = ?, min_y = ?, max_y = ?, min_z = ?, max_z = ?, priority = ?, metadata = ? WHERE id = ?"
            ).use { ps ->
                ps.setObject(1, area.worldId.value)
                ps.setString(2, area.key)
                ps.setBoolean(3, scope.wholeWorld)
                scope.bounds?.let { ps.setInt(4, it.minX) } ?: ps.setNull(4, java.sql.Types.INTEGER)
                scope.bounds?.let { ps.setInt(5, it.maxX) } ?: ps.setNull(5, java.sql.Types.INTEGER)
                scope.bounds?.let { ps.setInt(6, it.minY) } ?: ps.setNull(6, java.sql.Types.INTEGER)
                scope.bounds?.let { ps.setInt(7, it.maxY) } ?: ps.setNull(7, java.sql.Types.INTEGER)
                scope.bounds?.let { ps.setInt(8, it.minZ) } ?: ps.setNull(8, java.sql.Types.INTEGER)
                scope.bounds?.let { ps.setInt(9, it.maxZ) } ?: ps.setNull(9, java.sql.Types.INTEGER)
                ps.setInt(10, area.priority)
                ps.setJsonString(11, Serialization.toJson(area.metadata))
                ps.setObject(12, area.id.value)
                if (ps.executeUpdate() == 0) throw NoSuchElementException("Area ${area.id.value} not found")
            }
        }
        invalidateAreas(previousArea.worldId)
        if (previousArea.worldId != area.worldId) invalidateAreas(area.worldId)
        return area
    }

    override fun deleteArea(id: AreaId) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM areas WHERE id = ?").use { ps ->
                ps.setObject(1, id.value)
                ps.executeUpdate()
            }
        }
        areaSnapshot.clear()
        resolverSnapshot.clear()
    }

    /**
     * Compatibility path for older API/import callers that still submit only a world name.
     * The generated default area intentionally covers the whole world to preserve old protection
     * semantics until an administrator explicitly creates finite areas.
     */
    fun ensureLegacyArea(name: String): Area {
        val worldId = WorldId.fromLegacyName(name)
        val areaId = AreaId.legacyDefault(worldId)
        getArea(areaId)?.let { return it }

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.insertLegacyWorldIfMissing(worldId, name)
                conn.insertLegacyAreaIfMissing(areaId, worldId)
                val area = conn.selectArea(areaId) ?: error("Could not create legacy area for '$name'")
                conn.commit()
                invalidateWorlds()
                invalidateAreas(worldId)
                return area
            } catch (failure: Throwable) {
                conn.rollback()
                throw failure
            } finally {
                conn.autoCommit = true
            }
        }
    }

    /** Create missing legacy worlds/default areas and bind all old rows without changing their IDs. */
    fun migrateLegacyWorldNames() {
        dataSource.connection.use { conn ->
            val names = (conn.distinctWorldNames("regions") + conn.distinctWorldNames("zones"))
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
            conn.autoCommit = false
            try {
                for (name in names) {
                    val worldId = WorldId.fromLegacyName(name)
                    val areaId = AreaId.legacyDefault(worldId)
                    conn.insertLegacyWorldIfMissing(worldId, name)
                    conn.insertLegacyAreaIfMissing(areaId, worldId)
                    conn.prepareStatement("UPDATE regions SET area_id = ? WHERE world = ? AND area_id IS NULL").use { ps ->
                        ps.setObject(1, areaId.value)
                        ps.setString(2, name)
                        ps.executeUpdate()
                    }
                    conn.prepareStatement("UPDATE zones SET area_id = ? WHERE world = ? AND area_id IS NULL").use { ps ->
                        ps.setObject(1, areaId.value)
                        ps.setString(2, name)
                        ps.executeUpdate()
                    }
                }
                conn.commit()
                invalidateWorlds()
                areaSnapshot.clear()
                resolverSnapshot.clear()
            } catch (failure: Throwable) {
                conn.rollback()
                throw failure
            } finally {
                conn.autoCommit = true
            }
        }
    }

    private fun validateArea(area: Area, excludedId: AreaId?) {
        require(getWorld(area.worldId) != null) { "HomeClaim world ${area.worldId.value} not found" }
        val existing = listAreas(area.worldId).filterNot { it.id == excludedId }
        require(existing.none { it.key == area.key }) { "Area key '${area.key}' already exists in world ${area.worldId.value}" }
        AreaResolver.requireNoEqualPriorityOverlap(area, existing)
    }

    private fun invalidateWorlds() {
        worldSnapshot.set(null)
    }

    private fun invalidateAreas(worldId: WorldId) {
        areaSnapshot.remove(worldId)
        resolverSnapshot.remove(worldId)
    }

    private fun ResultSet.toWorld() = HomeClaimWorld(
        id = WorldId(UUID.fromString(getString("id"))),
        platformWorldId = getString("platform_uuid")?.let(UUID::fromString),
        name = getString("name"),
        metadata = getString("metadata")?.let { Serialization.fromJson(it) } ?: emptyMap()
    )

    private fun ResultSet.toArea(): Area {
        val wholeWorld = getBoolean("whole_world")
        val scope = if (wholeWorld) AreaScope.WholeWorld else AreaScope.Finite(
            Bounds(getInt("min_x"), getInt("max_x"), getInt("min_y"), getInt("max_y"), getInt("min_z"), getInt("max_z"))
        )
        return Area(
            id = AreaId(UUID.fromString(getString("id"))),
            worldId = WorldId(UUID.fromString(getString("world_id"))),
            key = getString("area_key"),
            scope = scope,
            priority = getInt("priority"),
            metadata = getString("metadata")?.let { Serialization.fromJson(it) } ?: emptyMap()
        )
    }

    private data class SqlScope(val wholeWorld: Boolean, val bounds: Bounds?)

    private fun AreaScope.sqlValues() = when (this) {
        AreaScope.WholeWorld -> SqlScope(true, null)
        is AreaScope.Finite -> SqlScope(false, bounds)
    }

    private fun Connection.selectWorld(id: WorldId): HomeClaimWorld? =
        prepareStatement("SELECT * FROM worlds WHERE id = ?").use { ps ->
            ps.setObject(1, id.value)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toWorld() else null }
        }

    private fun Connection.selectWorldByPlatformId(id: UUID): HomeClaimWorld? =
        prepareStatement("SELECT * FROM worlds WHERE platform_uuid = ?").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toWorld() else null }
        }

    private fun Connection.selectArea(id: AreaId): Area? =
        prepareStatement("SELECT * FROM areas WHERE id = ?").use { ps ->
            ps.setObject(1, id.value)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toArea() else null }
        }

    private fun Connection.insertArea(area: Area) {
        val scope = area.scope.sqlValues()
        prepareStatement(
            "INSERT INTO areas (id, world_id, area_key, whole_world, min_x, max_x, min_y, max_y, min_z, max_z, priority, metadata) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setObject(1, area.id.value)
            ps.setObject(2, area.worldId.value)
            ps.setString(3, area.key)
            ps.setBoolean(4, scope.wholeWorld)
            scope.bounds?.let { ps.setInt(5, it.minX) } ?: ps.setNull(5, java.sql.Types.INTEGER)
            scope.bounds?.let { ps.setInt(6, it.maxX) } ?: ps.setNull(6, java.sql.Types.INTEGER)
            scope.bounds?.let { ps.setInt(7, it.minY) } ?: ps.setNull(7, java.sql.Types.INTEGER)
            scope.bounds?.let { ps.setInt(8, it.maxY) } ?: ps.setNull(8, java.sql.Types.INTEGER)
            scope.bounds?.let { ps.setInt(9, it.minZ) } ?: ps.setNull(9, java.sql.Types.INTEGER)
            scope.bounds?.let { ps.setInt(10, it.maxZ) } ?: ps.setNull(10, java.sql.Types.INTEGER)
            ps.setInt(11, area.priority)
            ps.setJsonString(12, Serialization.toJson(area.metadata))
            ps.executeUpdate()
        }
    }

    private fun Connection.distinctWorldNames(table: String): List<String> {
        require(table == "regions" || table == "zones")
        return prepareStatement("SELECT DISTINCT world FROM $table WHERE world IS NOT NULL").use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    private fun ensureColumn(conn: Connection, table: String, column: String, definition: String) {
        val exists = conn.metaData.getColumns(conn.catalog, null, table, null).use { columns ->
            var found = false
            while (columns.next()) if (columns.getString("COLUMN_NAME").equals(column, ignoreCase = true)) found = true
            found
        }
        if (!exists) conn.createStatement().use { it.execute("ALTER TABLE $table ADD COLUMN $column $definition") }
    }

    private fun Connection.insertLegacyWorldIfMissing(id: WorldId, name: String) {
        if (selectWorld(id) != null) return
        prepareStatement("INSERT INTO worlds (id, platform_uuid, name, metadata) VALUES (?, NULL, ?, ?)").use { ps ->
            ps.setObject(1, id.value)
            ps.setString(2, name)
            ps.setJsonString(3, "{}")
            ps.executeUpdate()
        }
    }

    private fun Connection.insertLegacyAreaIfMissing(id: AreaId, worldId: WorldId) {
        if (selectArea(id) != null) return
        prepareStatement(
            "INSERT INTO areas (id, world_id, area_key, whole_world, priority, metadata) VALUES (?, ?, 'legacy-default', TRUE, 0, '{}')"
        ).use { ps ->
            ps.setObject(1, id.value)
            ps.setObject(2, worldId.value)
            ps.executeUpdate()
        }
    }
}
