package systems.diath.homeclaim.core

import org.sqlite.SQLiteDataSource
import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionRole
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.permission.PermissionResult
import systems.diath.homeclaim.core.policy.ActionEndpoint
import systems.diath.homeclaim.core.policy.ActionKey
import systems.diath.homeclaim.core.policy.ActionResourceRef
import systems.diath.homeclaim.core.policy.CorrelationId
import systems.diath.homeclaim.core.policy.DecisionEffect
import systems.diath.homeclaim.core.service.AuditEntry
import systems.diath.homeclaim.core.service.AuditSink
import systems.diath.homeclaim.core.service.AuditStatus
import systems.diath.homeclaim.core.service.BoundedAsyncAuditService
import systems.diath.homeclaim.core.store.JdbcAuditService
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuditPersistenceTest {
    @Test
    fun `jdbc audit sink stores action lifecycle and source target fields portably`() {
        val file = Files.createTempFile("homeclaim-audit-", ".db")
        val dataSource = SQLiteDataSource().apply { url = "jdbc:sqlite:$file" }
        try {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """CREATE TABLE audit_log (
                            audit_id TEXT PRIMARY KEY, actor_id TEXT, target_id TEXT,
                            category TEXT NOT NULL, action TEXT NOT NULL,
                            correlation_id TEXT, parent_correlation_id TEXT, actor_kind TEXT, actor_ref TEXT,
                            action_key TEXT, world_id TEXT, area_id TEXT,
                            source_world_id TEXT, source_area_id TEXT, source_x TEXT, source_y TEXT, source_z TEXT,
                            source_resource_type TEXT, source_resource_id TEXT,
                            target_world_id TEXT, target_area_id TEXT, target_x TEXT, target_y TEXT, target_z TEXT,
                            target_resource_type TEXT, target_resource_id TEXT,
                            role TEXT, policy_result TEXT, permission_result TEXT, bypass TEXT, reason TEXT,
                            status TEXT NOT NULL, target_count TEXT, error TEXT, payload TEXT NOT NULL, created_at TEXT
                        )""".trimIndent()
                    )
                }
            }
            val world = WorldId(UUID.randomUUID())
            val area = AreaId(UUID.randomUUID())
            val region = RegionId(UUID.randomUUID())
            val correlation = CorrelationId(UUID.randomUUID())
            val entry = AuditEntry(
                actorId = null,
                targetId = region.value,
                category = "REGION",
                action = "minecraft:hopper_transfer",
                auditId = UUID.randomUUID(),
                correlationId = correlation,
                actorKind = "AUTOMATION",
                actorRef = "hopper:42",
                actionKey = ActionKey("minecraft:hopper_transfer"),
                worldId = world,
                areaId = area,
                source = ActionEndpoint(Position(world, 1, 64, 1), ActionResourceRef.Region(region), area),
                target = ActionEndpoint(Position(world, 2, 64, 1), ActionResourceRef.Region(region), area),
                role = RegionRole.BANNED,
                policyResult = DecisionEffect.DENY,
                permissionResult = PermissionResult.DENIED,
                bypass = "homeclaim.bypass.protection",
                reason = "FLAG_DENY",
                status = AuditStatus.FAILED,
                targetCount = 2,
                error = "backend failed",
                payload = mapOf("event" to "hopper")
            )

            JdbcAuditService(dataSource).append(entry)

            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT * FROM audit_log WHERE audit_id = ?").use { statement ->
                    statement.setString(1, entry.auditId.toString())
                    statement.executeQuery().use { result ->
                        assertTrue(result.next())
                        assertEquals("AUTOMATION", result.getString("actor_kind"))
                        assertEquals("hopper:42", result.getString("actor_ref"))
                        assertEquals("minecraft:hopper_transfer", result.getString("action_key"))
                        assertEquals(correlation.toString(), result.getString("correlation_id"))
                        assertEquals(world.value.toString(), result.getString("world_id"))
                        assertEquals(area.value.toString(), result.getString("target_area_id"))
                        assertEquals("region", result.getString("source_resource_type"))
                        assertEquals("BANNED", result.getString("role"))
                        assertEquals("DENY", result.getString("policy_result"))
                        assertEquals("DENIED", result.getString("permission_result"))
                        assertEquals("FAILED", result.getString("status"))
                        assertEquals("backend failed", result.getString("error"))
                    }
                }
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `bounded audit service drains entries asynchronously on close`() {
        val persisted = mutableListOf<AuditEntry>()
        val failures = mutableListOf<String>()
        val service = BoundedAsyncAuditService(
            sink = AuditSink { persisted += it }, capacity = 4,
            onFailure = { message, _ -> failures += message }
        )
        val first = auditEntry()
        val second = auditEntry()

        service.append(first)
        service.append(second)
        service.close()

        assertEquals(2, persisted.size)
        assertTrue(persisted.containsAll(listOf(first, second)))
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `bounded audit queue reports overload instead of silently dropping entries`() {
        val sinkEntered = CountDownLatch(1)
        val releaseSink = CountDownLatch(1)
        val failures = mutableListOf<String>()
        val service = BoundedAsyncAuditService(
            sink = AuditSink {
                sinkEntered.countDown()
                releaseSink.await(2, TimeUnit.SECONDS)
            },
            capacity = 1,
            onFailure = { message, _ -> synchronized(failures) { failures += message } }
        )
        try {
            service.append(auditEntry())
            assertTrue(sinkEntered.await(2, TimeUnit.SECONDS))
            service.append(auditEntry()) // occupies the only queued slot
            service.append(auditEntry())
            assertEquals(1, service.failureCount)
            assertEquals(1, service.rejectedCount)
            assertTrue(synchronized(failures) { failures.single().contains("queue is full") })
        } finally {
            releaseSink.countDown()
            service.close()
        }
    }

    @Test
    fun `bounded audit service exposes sink failures`() {
        val failures = mutableListOf<String>()
        val service = BoundedAsyncAuditService(
            sink = AuditSink { error("database unavailable") },
            onFailure = { message, _ -> synchronized(failures) { failures += message } }
        )
        service.append(auditEntry())
        service.close()

        assertEquals(1, service.failureCount)
        assertTrue(synchronized(failures) { failures.single().contains("Could not persist audit") })
    }

    private fun auditEntry() = AuditEntry(
        actorId = UUID.randomUUID(), targetId = UUID.randomUUID(),
        category = "BLOCK", action = "BREAK", status = AuditStatus.DENIED
    )
}
