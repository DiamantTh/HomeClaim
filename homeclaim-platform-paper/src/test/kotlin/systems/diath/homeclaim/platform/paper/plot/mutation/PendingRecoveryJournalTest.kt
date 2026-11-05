package systems.diath.homeclaim.platform.paper.plot.mutation

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.policy.CorrelationId
import systems.diath.homeclaim.core.store.SqlDsl
import java.nio.file.Files
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

class PendingRecoveryJournalTest {
    @Test
    fun `failed journal remains blocked and reconciles without classifying unrelated active work`() {
        val file = Files.createTempFile("homeclaim-pending-recovery-", ".db")
        val pool = SqlDsl.hikari("jdbc:sqlite:$file", null, null, "org.sqlite.JDBC", 2, 0) as com.zaxxer.hikari.HikariDataSource
        try {
            Flyway.configure().dataSource(pool).locations("classpath:db/migration/common").load().migrate()
            val unavailable = AtomicBoolean(false)
            val intermittent = object : DataSource by pool {
                override fun getConnection(): java.sql.Connection {
                    if (unavailable.get()) throw SQLException("controlled unavailable database")
                    return pool.connection
                }
            }
            val store = JdbcPlotMutationJobStore(intermittent)
            store.loadRecoveryBlockers()
            fun job() = PlotMutationJobRecord(UUID.randomUUID(), CorrelationId.newId(), "MOVE", null,
                AreaId(UUID.randomUUID()), "test", listOf(RegionId(UUID.randomUUID())), "test", PlotMutationJobStatus.QUEUED,
                totalUnits = 4, details = "phase=CLEANING_SOURCE")
            val failed = job(); val active = job()
            listOf(failed, active).forEach { store.create(it); store.markRunning(it.id) }
            val coordinator = RegionMutationConflictCoordinator(store)
            unavailable.set(true)
            assertThrows(SQLException::class.java) { store.finish(failed.id, PlotMutationJobStatus.RECOVERY_REQUIRED, 3, 4, "partial world write") }
            assertEquals(PlotMutationJobStatus.RECOVERY_REQUIRED, store.findById(failed.id)?.status)
            assertNotNull(coordinator.checkAvailable(failed.regionIds))
            unavailable.set(false)
            fun durableStatus(id: UUID): String = pool.connection.use { connection ->
                connection.prepareStatement("SELECT status FROM plot_mutation_jobs WHERE id = ?").use { query ->
                    query.setObject(1, id); query.executeQuery().use { rows -> check(rows.next()); rows.getString(1) }
                }
            }
            assertEquals("RUNNING", durableStatus(failed.id))
            assertTrue(store.loadRecoveryBlockers().any { it.id == failed.id })
            assertNotNull(coordinator.checkAvailable(failed.regionIds))
            assertEquals(1, store.flushPendingRecoveryWrites())
            assertEquals("RECOVERY_REQUIRED", durableStatus(failed.id))
            assertEquals("RUNNING", durableStatus(active.id))
            assertEquals(0, store.flushPendingRecoveryWrites())
            assertTrue(store.beginRecovery(failed.id, "explicit recovery"))
            assertTrue(store.finishRecovery(failed.id, PlotMutationJobStatus.RECOVERED, "verified restore"))
            assertNull(coordinator.checkAvailable(failed.regionIds))
        } finally {
            pool.close()
            Files.deleteIfExists(file)
        }
    }
}
