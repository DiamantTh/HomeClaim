package systems.diath.homeclaim.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.sqlite.SQLiteDataSource
import systems.diath.homeclaim.core.model.Area
import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.AreaScope
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.store.JdbcWorldAreaRepository
import java.nio.file.Files
import java.util.UUID

class JdbcWorldAreaRepositoryMigrationTest {
    @Test
    fun `legacy world strings backfill idempotently without changing plot data`() {
        val databaseFile = Files.createTempFile("homeclaim-world-area", ".db")
        val dataSource = SQLiteDataSource().apply { url = "jdbc:sqlite:${databaseFile.toAbsolutePath()}" }
        val regionId = UUID.randomUUID().toString()
        val ownerId = UUID.randomUUID().toString()
        val mergeId = UUID.randomUUID().toString()
        val memberId = UUID.randomUUID().toString()
        try {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE regions (id TEXT PRIMARY KEY, world TEXT NOT NULL, owner TEXT NOT NULL, merge_group_id TEXT, price REAL NOT NULL, metadata TEXT)")
                    statement.execute("CREATE TABLE zones (id TEXT PRIMARY KEY, world TEXT NOT NULL)")
                    statement.execute("CREATE TABLE region_roles (region_id TEXT, player_id TEXT, role TEXT)")
                    statement.execute("CREATE TABLE region_flags (region_id TEXT, flag_key TEXT, flag_value TEXT)")
                    statement.execute("CREATE TABLE region_limits (region_id TEXT, limit_key TEXT, limit_value TEXT)")
                    statement.execute("CREATE TABLE region_entry_denies (id TEXT, region_id TEXT, target_value TEXT, reason TEXT)")
                    statement.execute("CREATE TABLE components (id TEXT, region_id TEXT)")
                }
                connection.prepareStatement("INSERT INTO regions VALUES (?, 'plots', ?, ?, 12.5, '{\"kept\":true}')").use {
                    it.setString(1, regionId)
                    it.setString(2, ownerId)
                    it.setString(3, mergeId)
                    it.executeUpdate()
                }
                connection.createStatement().use { statement ->
                    statement.execute("INSERT INTO zones VALUES ('zone-1', 'plots')")
                    statement.execute("INSERT INTO region_roles VALUES ('$regionId', '$memberId', 'TRUSTED')")
                    statement.execute("INSERT INTO region_flags VALUES ('$regionId', 'BUILD', 'false')")
                    statement.execute("INSERT INTO region_limits VALUES ('$regionId', 'MAX_ENTITIES', '10')")
                    statement.execute("INSERT INTO region_entry_denies VALUES ('deny-1', '$regionId', 'player-1', 'test')")
                    statement.execute("INSERT INTO components VALUES ('component-1', '$regionId')")
                }
            }

            val repository = JdbcWorldAreaRepository(dataSource)
            repository.ensureSchema()
            repository.migrateLegacyWorldNames()
            repository.migrateLegacyWorldNames()

            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT id, owner, merge_group_id, price, metadata, area_id FROM regions").use { rs ->
                        assertTrue(rs.next())
                        assertEquals(regionId, rs.getString("id"))
                        assertEquals(ownerId, rs.getString("owner"))
                        assertEquals(mergeId, rs.getString("merge_group_id"))
                        assertEquals(12.5, rs.getDouble("price"), 0.001)
                        assertEquals("{\"kept\":true}", rs.getString("metadata"))
                        assertEquals(
                            AreaId.legacyDefault(WorldId.fromLegacyName("plots")),
                            AreaId(UUID.fromString(rs.getString("area_id")))
                        )
                    }
                    statement.executeQuery("SELECT count(*) FROM worlds").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
                    statement.executeQuery("SELECT count(*) FROM areas").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
                    statement.executeQuery("SELECT count(*) FROM regions").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
                    statement.executeQuery("SELECT count(*) FROM region_roles").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
                    statement.executeQuery("SELECT count(*) FROM region_flags").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
                    statement.executeQuery("SELECT count(*) FROM region_limits").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
                    statement.executeQuery("SELECT count(*) FROM region_entry_denies").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
                    statement.executeQuery("SELECT count(*) FROM components").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
                    statement.executeQuery("SELECT count(*) FROM zones WHERE area_id IS NOT NULL").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
                }
            }
            assertNotNull(repository.getWorld(WorldId.fromLegacyName("plots")))

            val legacyWorldId = WorldId.fromLegacyName("plots")
            val otherWorld = repository.createWorld("other")
            val finiteArea = repository.createArea(
                Area(
                    id = AreaId.newId(), worldId = legacyWorldId, key = "finite",
                    scope = AreaScope.Finite(Bounds(-10, 10, -64, 319, -10, 10)), priority = 10
                )
            )
            assertEquals(finiteArea.id, repository.resolveArea(legacyWorldId, 0, 64, 0)?.id)
            assertNull(repository.resolveArea(otherWorld.id, 0, 64, 0))
            repository.updateArea(finiteArea.copy(worldId = otherWorld.id))
            assertEquals(AreaId.legacyDefault(legacyWorldId), repository.resolveArea(legacyWorldId, 0, 64, 0)?.id)
            assertEquals(finiteArea.id, repository.resolveArea(otherWorld.id, 0, 64, 0)?.id)
        } finally {
            Files.deleteIfExists(databaseFile)
        }
    }
}
