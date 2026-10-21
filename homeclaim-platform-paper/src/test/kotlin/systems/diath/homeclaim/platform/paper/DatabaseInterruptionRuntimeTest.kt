package systems.diath.homeclaim.platform.paper

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import systems.diath.homeclaim.core.store.SqlDsl
import java.util.UUID

/** Drives an actual database process outage while the same HomeClaim Hikari pool stays alive. */
class DatabaseInterruptionRuntimeTest {
    @Test
    fun `postgres outage fails closed and same pool reconnects`() = verify("POSTGRES", "org.postgresql.Driver")

    @Test
    fun `mariadb outage fails closed and same pool reconnects`() = verify("MARIADB", "org.mariadb.jdbc.Driver")

    private fun verify(prefix: String, driver: String) {
        val required = System.getProperty("homeclaim.database.interruption.required") == "true"
        val engine = System.getenv("HOMECLAIM_DB_CONTAINER_ENGINE")
        val container = System.getenv("HOMECLAIM_${prefix}_CONTAINER")
        val jdbcUrl = System.getenv("HOMECLAIM_TEST_${prefix}_JDBC_URL")
        if (engine.isNullOrBlank() || container.isNullOrBlank() || jdbcUrl.isNullOrBlank()) {
            val message = "database interruption task requires HOMECLAIM_DB_CONTAINER_ENGINE, HOMECLAIM_${prefix}_CONTAINER and isolated JDBC settings"
            if (required) error(message) else assumeTrue(false, message)
        }

        val pool = SqlDsl.hikari(
            jdbcUrl = jdbcUrl!!,
            username = System.getenv("HOMECLAIM_TEST_${prefix}_USERNAME"),
            password = System.getenv("HOMECLAIM_TEST_${prefix}_PASSWORD"),
            driverClassName = driver,
            maximumPoolSize = 2,
            minimumIdle = 0,
            connectionTimeoutMs = 1_500,
            validationTimeoutMs = 500,
            idleTimeoutMs = 1_000,
            maxLifetimeMs = 60_000,
            leakDetectionThresholdMs = 0
        ) as HikariDataSource
        val marker = UUID.randomUUID().toString()
        try {
            pool.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE IF NOT EXISTS homeclaim_interruption_probe (id VARCHAR(64) PRIMARY KEY, phase VARCHAR(32) NOT NULL)")
                    connection.prepareStatement("INSERT INTO homeclaim_interruption_probe (id, phase) VALUES (?, ?)").use { insert ->
                        insert.setString(1, marker)
                        insert.setString(2, "before-outage")
                        insert.executeUpdate()
                    }
                }
            }

            runContainerCommand(engine!!, "kill", "--signal", "KILL", container!!)
            assertThrows(Exception::class.java) {
                pool.connection.use { connection ->
                    connection.prepareStatement("INSERT INTO homeclaim_interruption_probe (id, phase) VALUES (?, ?)").use { insert ->
                        insert.setString(1, "$marker-down")
                        insert.setString(2, "during-outage")
                        insert.executeUpdate()
                    }
                }
            }

            runContainerCommand(engine, "start", container)
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90)
            var lastFailure: Exception? = null
            while (System.nanoTime() < deadline) {
                try {
                    pool.connection.use { connection ->
                        connection.prepareStatement("SELECT phase FROM homeclaim_interruption_probe WHERE id = ?").use { query ->
                            query.setString(1, marker)
                            query.executeQuery().use { result ->
                                assertTrue(result.next())
                                assertEquals("before-outage", result.getString(1))
                            }
                        }
                        connection.prepareStatement("SELECT COUNT(*) FROM homeclaim_interruption_probe WHERE id = ?").use { query ->
                            query.setString(1, "$marker-down")
                            query.executeQuery().use { result ->
                                result.next()
                                assertEquals(0, result.getInt(1), "An outage write must not report success or appear after reconnect")
                            }
                        }
                        connection.prepareStatement("INSERT INTO homeclaim_interruption_probe (id, phase) VALUES (?, ?)").use { insert ->
                            insert.setString(1, "$marker-after")
                            insert.setString(2, "after-reconnect")
                            assertEquals(1, insert.executeUpdate())
                        }
                        return
                    }
                } catch (failure: Exception) {
                    lastFailure = failure
                    Thread.sleep(500)
                }
            }
            throw AssertionError("The existing $prefix Hikari pool did not reconnect within 90 seconds", lastFailure)
        } finally {
            pool.close()
        }
    }

    private fun runContainerCommand(engine: String, vararg arguments: String) {
        val process = ProcessBuilder(listOf(engine) + arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        check(exit == 0) { "$engine ${arguments.joinToString(" ")} failed with exit $exit: ${output.take(2_000)}" }
    }
}
