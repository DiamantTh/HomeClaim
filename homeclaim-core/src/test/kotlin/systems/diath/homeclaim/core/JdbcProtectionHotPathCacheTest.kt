package systems.diath.homeclaim.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.sqlite.SQLiteDataSource
import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.store.JdbcComponentRepository
import systems.diath.homeclaim.core.store.JdbcEntryDenyRepository
import systems.diath.homeclaim.core.store.JdbcRegionRepository
import systems.diath.homeclaim.core.store.JdbcZoneRepository
import java.sql.Connection
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class JdbcProtectionHotPathCacheTest {
    @Test
    fun `protection spatial role zone and component lookups do not open JDBC connections after startup`() {
        val databaseFile = Files.createTempFile("homeclaim-protection-cache", ".db")
        val dataSource = CountingSQLiteDataSource("jdbc:sqlite:${databaseFile.toAbsolutePath()}")
        try {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE regions (id TEXT)")
                    statement.execute("CREATE TABLE zones (id TEXT)")
                    statement.execute("CREATE TABLE components (id TEXT)")
                    statement.execute("CREATE TABLE region_entry_denies (id TEXT, created_at TEXT)")
                }
            }
            dataSource.connectionCount.set(0)

            val regions = JdbcRegionRepository(dataSource)
            val zones = JdbcZoneRepository(dataSource)
            val components = JdbcComponentRepository(dataSource)
            val entryDenies = JdbcEntryDenyRepository(dataSource)
            assertEquals(4, dataSource.connectionCount.get())

            dataSource.connectionCount.set(0)
            val area = AreaId(UUID.randomUUID())
            val world = WorldId(UUID.randomUUID())
            val player = UUID.randomUUID()
            val regionId = RegionId(UUID.randomUUID())
            val position = Position(world, 8, 64, -3, "plots")
            repeat(50) {
                assertNull(regions.getRegionAt(area, position.x, position.y, position.z))
                assertNull(regions.getRegionById(regionId))
                assertEquals(emptyList<systems.diath.homeclaim.core.model.Zone>(), zones.getZonesAt(area, position))
                assertNull(components.getComponentAt(position))
                assertNull(entryDenies.findActiveDeny(regionId, player, "visitor"))
            }
            assertEquals(0, dataSource.connectionCount.get())
        } finally {
            Files.deleteIfExists(databaseFile)
        }
    }

    private class CountingSQLiteDataSource(url: String) : SQLiteDataSource() {
        val connectionCount = AtomicInteger()

        init {
            this.url = url
        }

        override fun getConnection(): Connection {
            connectionCount.incrementAndGet()
            return super.getConnection()
        }
    }
}
