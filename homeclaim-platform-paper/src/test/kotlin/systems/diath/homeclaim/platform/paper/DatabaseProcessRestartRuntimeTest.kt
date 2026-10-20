package systems.diath.homeclaim.platform.paper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import systems.diath.homeclaim.core.model.*
import systems.diath.homeclaim.core.policy.CorrelationId
import systems.diath.homeclaim.core.service.AuditEntry
import systems.diath.homeclaim.core.service.AuditStatus
import systems.diath.homeclaim.core.store.JdbcAuditService
import systems.diath.homeclaim.core.store.JdbcRegionRepository
import systems.diath.homeclaim.core.store.JdbcWorldAreaRepository
import systems.diath.homeclaim.core.store.SqlDsl
import systems.diath.homeclaim.platform.paper.plot.mutation.*
import java.util.UUID

/** Two separate Gradle/JVM phases exercise the same DB after its process was stopped and restarted. */
class DatabaseProcessRestartRuntimeTest {
    @Test
    fun `postgres state survives database process restart`() = verifyDialect(
        "HOMECLAIM_TEST_POSTGRES", "org.postgresql.Driver", "classpath:db/migration/postgres"
    )

    @Test
    fun `mariadb state survives database process restart`() = verifyDialect(
        "HOMECLAIM_TEST_MARIADB", "org.mariadb.jdbc.Driver", "classpath:db/migration/mariadb"
    )

    private fun verifyDialect(prefix: String, driver: String, dialectLocation: String) {
        val config = connectionConfig(prefix, driver)
        val runId = System.getenv("HOMECLAIM_PROCESS_RESTART_RUN_ID")
        require(!runId.isNullOrBlank()) { "HOMECLAIM_PROCESS_RESTART_RUN_ID must identify this isolated test run" }
        val phase = requireNotNull(System.getProperty("homeclaim.database.process.restart.phase"))
        require(phase == "prepare" || phase == "verify") { "Unknown database process restart phase: $phase" }

        val platformWorldId = id(runId, "$prefix-platform-world")
        val areaId = AreaId(id(runId, "$prefix-area"))
        val regionId = RegionId(id(runId, "$prefix-region"))
        val backupId = id(runId, "$prefix-backup")
        val jobId = id(runId, "$prefix-job")
        val correlation = CorrelationId(id(runId, "$prefix-correlation"))
        val actorId = id(runId, "$prefix-actor")
        val checksum = "8f".repeat(32)

        val dataSource = SqlDsl.hikari(
            jdbcUrl = config.jdbcUrl,
            username = config.username,
            password = config.password,
            driverClassName = config.driver,
            maximumPoolSize = 3,
            minimumIdle = 0
        ) as HikariDataSource
        try {
            Flyway.configure(HomeClaimPaperPlugin::class.java.classLoader)
                .dataSource(dataSource)
                .locations("classpath:db/migration/common", dialectLocation)
                .baselineOnMigrate(false)
                .load()
                .migrate()

            if (phase == "prepare") {
                persistState(dataSource, prefix, platformWorldId, areaId, regionId, backupId, jobId,
                    correlation, actorId, checksum)
            } else {
                verifyState(dataSource, prefix, platformWorldId, areaId, regionId, backupId, jobId,
                    correlation, actorId, checksum)
            }
        } finally {
            dataSource.close()
        }
    }

    private fun persistState(
        dataSource: HikariDataSource,
        prefix: String,
        platformWorldId: UUID,
        areaId: AreaId,
        regionId: RegionId,
        backupId: UUID,
        jobId: UUID,
        correlation: CorrelationId,
        actorId: UUID,
        checksum: String
    ) {
        val worlds = JdbcWorldAreaRepository(dataSource).also { it.ensureSchema() }
        val world = worlds.createWorld("restart-${prefix.substringAfterLast('_').lowercase()}", platformWorldId)
        val bounds = Bounds(0, 15, 0, 255, 0, 15)
        val area = worlds.createArea(Area(areaId, world.id, "restart-${prefix.lowercase()}", AreaScope.Finite(bounds)))
        worlds.warmRuntimeCache()
        val audit = JdbcAuditService(dataSource)
        val regions = JdbcRegionRepository(dataSource, auditService = audit, worldAreaRepository = worlds)
        regions.createRegion(Region(
            id = regionId,
            world = world.name,
            shape = RegionShape.PLOT_GRID,
            bounds = bounds,
            owner = RegionOwnership.UNCLAIMED_OWNER_ID,
            areaId = area.id
        ))

        val backupIndex = JdbcPlotBackupIndex(dataSource)
        backupIndex.create(PlotBackupRecord(
            id = backupId,
            correlationId = correlation,
            worldId = world.id,
            paperWorldId = platformWorldId,
            areaId = area.id,
            regionId = regionId,
            bounds = bounds,
            createdByKind = "PLAYER",
            createdByRef = actorId.toString(),
            cause = "DATABASE_PROCESS_RESTART_TEST",
            status = PlotBackupStatus.CREATING,
            formatVersion = 2,
            compression = PlotBackupCompression.ZSTD,
            blockCount = 65_536
        ))
        backupIndex.markReady(backupId, 4096, checksum)

        JdbcPlotMutationJobStore(dataSource).create(PlotMutationJobRecord(
            id = jobId,
            correlationId = correlation,
            operation = "COPY",
            worldId = world.id,
            areaId = area.id,
            worldName = world.name,
            regionIds = listOf(regionId),
            backend = "paper-native",
            status = PlotMutationJobStatus.RECOVERY_REQUIRED,
            completedUnits = 12,
            totalUnits = 20,
            error = "process restart fixture",
            details = "format=homeclaim-transfer-v1\nphase=APPLYING_TARGET\nbackupId=$backupId\nchangedSides=TARGET"
        ))
        audit.append(AuditEntry(
            actorId = actorId,
            targetId = regionId.value,
            category = "REGION",
            action = "homeclaim:database_process_restart_fixture",
            correlationId = correlation,
            worldId = world.id,
            areaId = area.id,
            status = AuditStatus.FAILED,
            reason = "restart persistence fixture"
        ))
        verifyState(dataSource, "${prefix}_immediate", platformWorldId, areaId, regionId,
            backupId, jobId, correlation, actorId, checksum)
    }

    private fun verifyState(
        dataSource: HikariDataSource,
        prefix: String,
        platformWorldId: UUID,
        areaId: AreaId,
        regionId: RegionId,
        backupId: UUID,
        jobId: UUID,
        correlation: CorrelationId,
        actorId: UUID,
        checksum: String
    ) {
        val worlds = JdbcWorldAreaRepository(dataSource).also {
            it.ensureSchema()
            it.warmRuntimeCache()
        }
        val world = worlds.getWorldByPlatformId(platformWorldId)
        assertNotNull(world, "$prefix platform world binding")
        val homeClaimWorld = requireNotNull(world)
        val area = worlds.listAreas(homeClaimWorld.id).single { it.id == areaId }
        assertEquals(homeClaimWorld.id, area.worldId)

        val audit = JdbcAuditService(dataSource)
        val regions = JdbcRegionRepository(dataSource, auditService = audit, worldAreaRepository = worlds)
        assertEquals(regionId, regions.getRegionAt(areaId, 4, 64, 4))
        assertEquals(areaId, regions.getRegionById(regionId)?.areaId)

        val backup = JdbcPlotBackupIndex(dataSource).find(backupId)
        assertNotNull(backup, "$prefix backup metadata")
        assertEquals(PlotBackupStatus.READY, backup?.status)
        assertEquals(PlotBackupCompression.ZSTD, backup?.compression)
        assertEquals(2, backup?.formatVersion)
        assertEquals(checksum, backup?.checksumSha256)
        assertEquals(homeClaimWorld.id, backup?.worldId)
        assertEquals(areaId, backup?.areaId)

        val job = JdbcPlotMutationJobStore(dataSource).findById(jobId)
        assertNotNull(job, "$prefix mutation job")
        assertEquals(PlotMutationJobStatus.RECOVERY_REQUIRED, job?.status)
        assertEquals(correlation, job?.correlationId)
        assertEquals(listOf(regionId), job?.regionIds)
        assertTrue(job?.details?.contains("phase=APPLYING_TARGET") == true)
        assertTrue(job?.details?.contains("backupId=$backupId") == true)
        assertTrue(JdbcPlotMutationJobStore(dataSource).findRecoveryRequiredForRegions(listOf(regionId))
            .any { it.id == jobId })

        val entries = audit.findForTarget(regionId.value, java.time.Instant.EPOCH, 100)
        val entry = entries.singleOrNull { it.correlationId == correlation }
        assertNotNull(entry, "$prefix correlated recovery audit")
        assertEquals(actorId, entry?.actorId)
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT action, status, area_id FROM audit_log WHERE correlation_id = ?").use { statement ->
                statement.setObject(1, correlation.value)
                statement.executeQuery().use { result ->
                    assertTrue(result.next(), "$prefix structured audit row")
                    assertEquals("homeclaim:database_process_restart_fixture", result.getString("action"))
                    assertEquals(AuditStatus.FAILED.name, result.getString("status"))
                    assertEquals(areaId.value.toString(), result.getString("area_id"))
                }
            }
        }
    }

    private data class RestartConnectionConfig(
        val jdbcUrl: String,
        val username: String?,
        val password: String?,
        val driver: String
    )

    private fun connectionConfig(prefix: String, driver: String): RestartConnectionConfig {
        val url = System.getenv("${prefix}_JDBC_URL")
        if (url.isNullOrBlank()) {
            val required = System.getProperty("homeclaim.database.process.restart.required") == "true"
            if (required) error("${prefix}_JDBC_URL is required by database process restart tasks")
            assumeTrue(false, "Set ${prefix}_JDBC_URL to an isolated disposable database")
        }
        return RestartConnectionConfig(url!!, System.getenv("${prefix}_USERNAME"),
            System.getenv("${prefix}_PASSWORD"), driver)
    }

    private fun id(runId: String, key: String): UUID =
        UUID.nameUUIDFromBytes("homeclaim:database-process-restart:$runId:$key".toByteArray(Charsets.UTF_8))
}
