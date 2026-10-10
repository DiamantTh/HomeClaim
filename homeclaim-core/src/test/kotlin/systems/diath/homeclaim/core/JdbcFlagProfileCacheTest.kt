package systems.diath.homeclaim.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.sqlite.SQLiteDataSource
import systems.diath.homeclaim.core.model.FlagKey
import systems.diath.homeclaim.core.model.LimitKey
import systems.diath.homeclaim.core.model.PolicyValue
import systems.diath.homeclaim.core.policy.FlagProfile
import systems.diath.homeclaim.core.store.JdbcFlagProfileService
import java.sql.Connection
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class JdbcFlagProfileCacheTest {
    @Test
    fun `profile reads use runtime snapshot and successful writes replace it`() {
        val databaseFile = Files.createTempFile("homeclaim-flag-profile-cache", ".db")
        val dataSource = CountingSQLiteDataSource("jdbc:sqlite:${databaseFile.toAbsolutePath()}")
        try {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "CREATE TABLE flag_profiles (name TEXT PRIMARY KEY, flags JSON NOT NULL DEFAULT '{}', limits JSON NOT NULL DEFAULT '{}')"
                    )
                }
            }
            dataSource.connectionCount.set(0)

            val profiles = JdbcFlagProfileService(dataSource)
            assertEquals(1, dataSource.connectionCount.get(), "Service startup loads the persisted snapshot")

            val profile = FlagProfile(
                name = "protected",
                flags = mapOf(FlagKey("BUILD") to PolicyValue.Bool(false)),
                limits = mapOf(LimitKey("COMPONENT_COOLDOWN_MS") to PolicyValue.IntValue(250))
            )
            profiles.upsert(profile)
            assertEquals(2, dataSource.connectionCount.get(), "A profile write persists before publishing the new snapshot")

            dataSource.connectionCount.set(0)
            repeat(20) {
                assertEquals(profile, profiles.getProfile("protected"))
                assertNull(profiles.getProfile("missing"))
                assertEquals(listOf(profile), profiles.listProfiles())
            }
            assertEquals(0, dataSource.connectionCount.get(), "Policy profile lookups must not use JDBC")

            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO flag_profiles (name, flags, limits) VALUES (?, ?, ?)"
                ).use { statement ->
                    statement.setString(1, "external")
                    statement.setString(2, "{}")
                    statement.setString(3, "{}")
                    statement.executeUpdate()
                }
            }
            val beforeReload = dataSource.connectionCount.get()
            assertNull(profiles.getProfile("external"), "Out-of-band writes require explicit reload")
            profiles.reloadRuntimeProfiles()
            assertTrue(dataSource.connectionCount.get() > beforeReload)
            assertEquals("external", profiles.getProfile("external")?.name)
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
