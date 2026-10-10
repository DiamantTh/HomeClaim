package systems.diath.homeclaim.platform.paper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.flywaydb.core.Flyway
import systems.diath.homeclaim.api.PlotRestServer
import systems.diath.homeclaim.core.economy.SimpleEconService
import systems.diath.homeclaim.core.model.*
import systems.diath.homeclaim.core.policy.*
import systems.diath.homeclaim.core.service.AuditEntry
import systems.diath.homeclaim.core.service.AuditStatus
import systems.diath.homeclaim.core.service.RegionRoleService
import systems.diath.homeclaim.core.store.*
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.UUID

/** Optional runtime tests. Configure these only with isolated, disposable test databases. */
class DatabaseDialectRuntimeTest {
    @Test
    fun `sqlite schema repositories audit and rest work against a temporary database`() {
        val databaseFile = Files.createTempFile("homeclaim-runtime-", ".db")
        try {
            verifyFullRuntime(
                JdbcConnectionConfig("jdbc:sqlite:${databaseFile.toAbsolutePath()}", null, null, "org.sqlite.JDBC"),
                "classpath:db/migration/common"
            )
        } finally {
            Files.deleteIfExists(databaseFile)
        }
    }

    @Test
    fun `postgres schema repositories audit and rest work against an isolated database`() {
        val connection = configured("HOMECLAIM_TEST_POSTGRES", "org.postgresql.Driver")
        verifyFullRuntime(connection, "classpath:db/migration/common", "classpath:db/migration/postgres")
    }

    @Test
    fun `mariadb schema repositories audit and rest work against an isolated database`() {
        val connection = configured("HOMECLAIM_TEST_MARIADB", "org.mariadb.jdbc.Driver")
        verifyFullRuntime(connection, "classpath:db/migration/common", "classpath:db/migration/mariadb")
    }

    private fun configured(prefix: String, driver: String): JdbcConnectionConfig {
        val jdbcUrl = System.getenv("${prefix}_JDBC_URL")
        assumeTrue(!jdbcUrl.isNullOrBlank(), "Set ${prefix}_JDBC_URL to an isolated disposable test database")
        return JdbcConnectionConfig(
            jdbcUrl = jdbcUrl!!,
            username = System.getenv("${prefix}_USERNAME"),
            password = System.getenv("${prefix}_PASSWORD"),
            driver = driver
        )
    }

    private fun verifyFullRuntime(config: JdbcConnectionConfig, vararg locations: String) {
        val dataSource = SqlDsl.hikari(
            jdbcUrl = config.jdbcUrl,
            username = config.username,
            password = config.password,
            driverClassName = config.driver,
            maximumPoolSize = 4,
            minimumIdle = 0
        ) as com.zaxxer.hikari.HikariDataSource
        try {
            Flyway.configure(HomeClaimPaperPlugin::class.java.classLoader)
                .dataSource(dataSource)
                .locations(*locations)
                .baselineOnMigrate(false)
                .load()
                .migrate()

            if (dataSource.connection.use { it.metaData.databaseProductName.equals("PostgreSQL", ignoreCase = true) }) {
                dataSource.connection.use { connection ->
                    for ((table, column) in listOf(
                        "worlds" to "id",
                        "worlds" to "platform_uuid",
                        "areas" to "id",
                        "areas" to "world_id",
                        "regions" to "area_id",
                        "zones" to "area_id",
                        "audit_log" to "correlation_id",
                        "audit_log" to "source_area_id"
                    )) {
                        connection.prepareStatement(
                            "SELECT data_type FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?"
                        ).use { statement ->
                            statement.setString(1, table)
                            statement.setString(2, column)
                            statement.executeQuery().use { result ->
                                assertTrue(result.next(), "Missing PostgreSQL column $table.$column")
                                assertEquals("uuid", result.getString(1), "$table.$column should use PostgreSQL UUID")
                            }
                        }
                    }
                }
            }

            val audit = JdbcAuditService(dataSource)
            val worlds = JdbcWorldAreaRepository(dataSource)
            worlds.ensureSchema()
            worlds.migrateLegacyWorldNames()

            val platformWorldId = UUID.randomUUID()
            val world = worlds.createWorld("database-runtime-${platformWorldId}", platformWorldId)
            assertEquals(world.id, worlds.getWorldByPlatformId(platformWorldId)?.id)
            val bounds = Bounds(0, 31, 0, 255, 0, 15)
            val area = worlds.createArea(
                Area(
                    id = AreaId(UUID.randomUUID()),
                    worldId = world.id,
                    key = "runtime",
                    scope = AreaScope.Finite(bounds),
                    priority = 10
                )
            )
            assertEquals(area.id, worlds.resolveArea(world.id, 3, 64, 4)?.id)
            assertEquals(null, worlds.resolveArea(world.id, 40, 64, 4))
            worlds.warmRuntimeCache()

            val profiles = JdbcFlagProfileService(dataSource)
            val profile = FlagProfile(
                "runtime",
                mapOf(FlagKey("BUILD") to PolicyValue.Bool(false)),
                mapOf(LimitKey("COMPONENT_COOLDOWN_MS") to PolicyValue.IntValue(250))
            )
            profiles.upsert(profile)
            assertEquals(profile, profiles.getProfile("runtime"))

            val regions = JdbcRegionRepository(dataSource, auditService = audit, worldAreaRepository = worlds)
            val owner = UUID(0L, 0L)
            val visitor = UUID.randomUUID()
            val firstId = RegionId(UUID.randomUUID())
            val firstBounds = Bounds(0, 15, 0, 255, 0, 15)
            val firstRegion = Region(
                id = firstId,
                world = world.name,
                shape = RegionShape.PLOT_GRID,
                bounds = firstBounds,
                owner = owner,
                roles = RegionRoles(trusted = setOf(visitor)),
                flags = mapOf(FlagKey("BUILD") to PolicyValue.Bool(false)),
                limits = mapOf(LimitKey("COMPONENT_COOLDOWN_MS") to PolicyValue.IntValue(500)),
                areaId = area.id
            )
            regions.createRegion(firstRegion)
            assertEquals(firstId, regions.getRegionAt(area.id, 5, 64, 5))
            assertEquals(visitor, regions.getRegionById(firstId)?.roles?.trusted?.single())
            assertEquals(PolicyValue.Bool(false), regions.getRegionById(firstId)?.flags?.get(FlagKey("BUILD")))
            assertEquals(PolicyValue.IntValue(500), regions.getRegionById(firstId)?.limits?.get(LimitKey("COMPONENT_COOLDOWN_MS")))

            val zones = JdbcZoneRepository(dataSource, worlds)
            val zone = Zone(
                id = ZoneId(UUID.randomUUID()),
                world = world.name,
                shape = RegionShape.CUBOID,
                bounds = Bounds(2, 8, 60, 70, 2, 8),
                priority = 20,
                defaults = ZoneDefaults(defaultFlags = mapOf(FlagKey("BUILD") to PolicyValue.Bool(false))),
                lockedFlags = setOf(FlagKey("BUILD")),
                tags = setOf("runtime"),
                areaId = area.id
            )
            zones.createZone(zone)
            assertEquals(zone.id, zones.getZonesAt(area.id, Position(world.id, 5, 64, 5, world.name)).single().id)

            val components = JdbcComponentRepository(dataSource)
            val componentPosition = Position(world.id, 5, 64, 5, world.name)
            val componentId = components.createComponent(
                firstId,
                ComponentType.ELEVATOR_PAD,
                componentPosition,
                ElevatorConfig(),
                ComponentPolicy()
            )
            assertEquals(componentId, components.getComponentAt(componentPosition)?.id)
            components.updateComponent(componentId, state = ComponentState.DISABLED)
            assertEquals(ComponentState.DISABLED, components.getComponentAt(componentPosition)?.state)

            val denies = JdbcEntryDenyRepository(dataSource)
            val deny = denies.createRule(
                firstId,
                EntryDenyTargetType.PLAYER,
                UUID.randomUUID().toString(),
                "runtime verification",
                UUID.randomUUID()
            )
            assertEquals(deny.id, denies.listRules(firstId).single().id)

            val claimedBy = UUID.randomUUID()
            assertTrue(regions.claimRegion(firstId, claimedBy, 0.0, SimpleEconService()))
            assertEquals(claimedBy, regions.getRegionById(firstId)?.owner)

            val secondId = RegionId(UUID.randomUUID())
            val secondBounds = Bounds(16, 31, 0, 255, 0, 15)
            regions.createRegion(firstRegion.copy(id = secondId, bounds = secondBounds, owner = claimedBy))
            val mergeId = regions.mergeRegions(listOf(firstId, secondId))
            assertEquals(mergeId, regions.getRegionById(firstId)?.mergeGroupId)
            assertEquals(mergeId, regions.getRegionById(secondId)?.mergeGroupId)

            val correlationId = CorrelationId.newId()
            audit.append(
                AuditEntry(
                    actorId = claimedBy,
                    targetId = firstId.value,
                    category = "REGION",
                    action = HomeClaimOperationActions.REGION_CLAIM.value,
                    correlationId = correlationId,
                    actionKey = HomeClaimOperationActions.REGION_CLAIM,
                    worldId = world.id,
                    areaId = area.id,
                    status = AuditStatus.COMPLETED,
                    reason = "runtime test"
                )
            )
            val auditReadBack = JdbcAuditService(dataSource).findForTarget(firstId.value, java.time.Instant.EPOCH, 50)
            assertTrue(auditReadBack.any {
                it.targetId == firstId.value && it.actorId == claimedBy &&
                    it.action == HomeClaimOperationActions.REGION_CLAIM.value
            }, "Audit query should return the persisted record for the region")
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT COUNT(*) FROM audit_log WHERE correlation_id = ?").use { statement ->
                    statement.setObject(1, correlationId.value)
                    statement.executeQuery().use { result ->
                        result.next()
                        assertEquals(1, result.getInt(1))
                    }
                }
            }

            val roleService = RegionRoleService(regions)
            val restPlayerId = UUID.randomUUID()
            val port = ServerSocket(0).use { it.localPort }
            val rest = PlotRestServer(
                regionService = regions,
                plotMemberService = null,
                zoneService = zones,
                componentService = components,
                adminService = null,
                auditService = audit,
                port = port,
                authToken = "homeclaim-isolated-runtime-test",
                allowLocalhost = true,
                roleService = roleService
            )
            val engine = rest.start()
            try {
                val response = sendRestRequest(
                    port,
                    "POST",
                    "/api/v1/plots/${firstId.value}/trust",
                    token = "homeclaim-isolated-runtime-test",
                    body = """{"playerId":"$restPlayerId"}"""
                )
                assertEquals(200, response.statusCode(), response.body())
                assertTrue(regions.getRegionById(firstId)?.roles?.trusted?.contains(restPlayerId) == true)
                assertNotNull(regions.getRegionById(firstId))

                val invalid = sendRestRequest(
                    port,
                    "POST",
                    "/api/v1/plots/${firstId.value}/trust",
                    token = "homeclaim-isolated-runtime-test",
                    body = """{"playerId":"not-a-uuid"}"""
                )
                assertEquals(400, invalid.statusCode(), invalid.body())
                val missing = sendRestRequest(
                    port,
                    "GET",
                    "/api/v1/plots/${UUID.randomUUID()}",
                    token = "homeclaim-isolated-runtime-test"
                )
                assertEquals(404, missing.statusCode(), missing.body())
                val unauthorized = sendRestRequest(port, "GET", "/api/v1/health")
                assertEquals(401, unauthorized.statusCode(), unauthorized.body())

                dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        "SELECT COUNT(*) FROM audit_log WHERE action_key = ? AND status = ?"
                    ).use { statement ->
                        statement.setString(1, HomeClaimOperationActions.ROLE_CHANGE.value)
                        statement.setString(2, AuditStatus.COMPLETED.name)
                        statement.executeQuery().use { result ->
                            result.next()
                            assertTrue(result.getInt(1) >= 1)
                        }
                    }
                }

                // Recreate repository snapshots as a lightweight application restart check.
                val restartedWorlds = JdbcWorldAreaRepository(dataSource).also {
                    it.ensureSchema()
                    it.warmRuntimeCache()
                }
                assertEquals(world.id, restartedWorlds.getWorldByPlatformId(platformWorldId)?.id)
                assertEquals(area.id, restartedWorlds.resolveArea(world.id, 3, 64, 4)?.id)
                val restartedRegions = JdbcRegionRepository(
                    dataSource,
                    auditService = audit,
                    worldAreaRepository = restartedWorlds
                )
                assertTrue(restartedRegions.getRegionById(firstId)?.roles?.trusted?.contains(restPlayerId) == true)
                assertEquals(ComponentState.DISABLED, JdbcComponentRepository(dataSource).getComponentAt(componentPosition)?.state)
                assertEquals(deny.id, JdbcEntryDenyRepository(dataSource).listRules(firstId).single().id)
                assertEquals(profile, JdbcFlagProfileService(dataSource).getProfile("runtime"))
            } finally {
                engine.stop(100, 1_000)
            }

            val forbiddenPort = ServerSocket(0).use { it.localPort }
            val forbiddenServer = PlotRestServer(
                regionService = regions,
                plotMemberService = null,
                zoneService = zones,
                componentService = components,
                adminService = null,
                port = forbiddenPort,
                authToken = "homeclaim-isolated-runtime-test",
                allowedHosts = setOf("203.0.113.1"),
                allowLocalhost = false
            )
            val forbiddenEngine = forbiddenServer.start()
            try {
                val forbidden = sendRestRequest(
                    forbiddenPort,
                    "GET",
                    "/api/v1/health",
                    token = "homeclaim-isolated-runtime-test"
                )
                assertEquals(403, forbidden.statusCode(), forbidden.body())
            } finally {
                forbiddenEngine.stop(100, 1_000)
            }

            val failedPort = ServerSocket(0).use { it.localPort }
            val failedServer = PlotRestServer(
                regionService = regions,
                plotMemberService = null,
                zoneService = zones,
                componentService = components,
                adminService = null,
                auditService = audit,
                port = failedPort,
                authToken = "homeclaim-isolated-runtime-test",
                allowLocalhost = true,
                roleService = object : systems.diath.homeclaim.core.service.RoleService {
                    override fun setRole(regionId: RegionId, playerId: UUID, role: RegionRole) {
                        throw IllegalStateException("simulated role persistence failure")
                    }

                    override fun removeRole(regionId: RegionId, playerId: UUID, role: RegionRole) {
                        throw IllegalStateException("simulated role persistence failure")
                    }
                }
            )
            val failedEngine = failedServer.start()
            try {
                val failed = sendRestRequest(
                    failedPort,
                    "POST",
                    "/api/v1/plots/${firstId.value}/trust",
                    token = "homeclaim-isolated-runtime-test",
                    body = """{"playerId":"${UUID.randomUUID()}"}"""
                )
                assertEquals(500, failed.statusCode(), failed.body())
            } finally {
                failedEngine.stop(100, 1_000)
            }
        } finally {
            dataSource.close()
        }
    }

    private fun sendRestRequest(
        port: Int,
        method: String,
        path: String,
        token: String? = null,
        body: String? = null
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        token?.let { builder.header("X-Admin-Token", it) }
        if (body != null) builder.header("Content-Type", "application/json")
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        val request = builder.method(method, publisher).build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    private data class JdbcConnectionConfig(
        val jdbcUrl: String,
        val username: String?,
        val password: String?,
        val driver: String
    )
}
