package systems.diath.homeclaim.platform.paper

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.DriverManager

class DatabaseRuntimeTest {
    @Test
    fun sqliteDriverAndConsolidatedInitialFlywaySchemaAreAvailableAtRuntime() {
        val database = Files.createTempFile("homeclaim-initial-schema", ".db")
        val jdbcUrl = "jdbc:sqlite:${database.toAbsolutePath()}"
        try {
            Class.forName("org.sqlite.JDBC")
            Flyway.configure(HomeClaimPaperPlugin::class.java.classLoader)
                .dataSource(jdbcUrl, null, null)
                .locations("classpath:db/migration/common")
                .load()
                .migrate()

            DriverManager.getConnection(jdbcUrl).use { connection ->
                val requiredTables = listOf(
                    "worlds", "areas", "regions", "region_roles", "region_flags", "region_limits",
                    "zones", "components", "region_entry_denies", "plot_ratings", "audit_log"
                )
                val tables = connection.metaData.getTables(null, null, "%", arrayOf("TABLE"))
                val found = buildSet {
                    while (tables.next()) add(tables.getString("TABLE_NAME").lowercase())
                }
                requiredTables.forEach { assertTrue(it in found, "Missing initial-schema table: $it") }

                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT area_id, price, home_x, home_y, home_z, home_yaw, home_pitch FROM regions LIMIT 0").use {
                        assertNotNull(it.metaData)
                    }
                    statement.executeQuery("SELECT area_id FROM zones LIMIT 0").use {
                        assertNotNull(it.metaData)
                    }
                }
            }
        } finally {
            Files.deleteIfExists(database)
        }
    }

    @Test
    fun supportedJdbcDriverClassesArePackagedInTestRuntime() {
        listOf(
            "org.sqlite.JDBC",
            "org.postgresql.Driver",
            "org.mariadb.jdbc.Driver",
            "com.mysql.cj.jdbc.Driver"
        ).forEach { Class.forName(it) }
    }
}
