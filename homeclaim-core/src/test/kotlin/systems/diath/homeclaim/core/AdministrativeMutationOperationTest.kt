package systems.diath.homeclaim.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import systems.diath.homeclaim.core.model.*
import systems.diath.homeclaim.core.policy.*
import systems.diath.homeclaim.core.service.*
import systems.diath.homeclaim.core.store.InMemoryComponentStore
import systems.diath.homeclaim.core.store.InMemoryFlagProfileService
import systems.diath.homeclaim.core.store.InMemoryRegionStore
import java.util.UUID
import java.time.Instant

class AdministrativeMutationOperationTest {
    private class CapturingAudit : AuditService {
        val entries = mutableListOf<AuditEntry>()
        override fun append(entry: AuditEntry) { entries += entry }
    }

    private data class Fixture(
        val regionService: InMemoryRegionStore,
        val region: Region,
        val actorId: UUID
    )

    private class InMemoryEntryDenies : EntryDenyService {
        private val rules = linkedMapOf<EntryDenyId, EntryDenyRule>()
        override fun createRule(regionId: RegionId, targetType: EntryDenyTargetType, targetValue: String, reason: String,
                                createdBy: PlayerId, expiresAt: Instant?): EntryDenyRule {
            val rule = EntryDenyRule(EntryDenyId(UUID.randomUUID()), regionId, targetType, targetValue, reason,
                createdBy, Instant.now(), expiresAt)
            rules[rule.id] = rule
            return rule
        }
        override fun getRule(id: EntryDenyId): EntryDenyRule? = rules[id]
        override fun listRules(regionId: RegionId, includeInactive: Boolean): List<EntryDenyRule> =
            rules.values.filter { it.regionId == regionId && (includeInactive || it.isEffective()) }
        override fun findActiveDeny(regionId: RegionId, playerId: PlayerId, playerName: String?): EntryDenyRule? =
            listRules(regionId).firstOrNull { it.matches(playerId, playerName) }
        override fun reportRule(id: EntryDenyId, reportedBy: PlayerId, reason: String): Boolean {
            val current = rules[id] ?: return false
            rules[id] = current.copy(status = EntryDenyStatus.REPORTED, reportedBy = reportedBy, reportReason = reason)
            return true
        }
        override fun revokeRule(id: EntryDenyId, revokedBy: PlayerId, reason: String): Boolean {
            val current = rules[id] ?: return false
            if (current.status == EntryDenyStatus.REVOKED) return false
            rules[id] = current.copy(status = EntryDenyStatus.REVOKED, revokedBy = revokedBy, revokeReason = reason)
            return true
        }
    }

    private fun fixture(audit: AuditService? = null): Fixture {
        val regionService = InMemoryRegionStore(auditService = audit)
        val world = "admin-operations"
        val region = Region(
            id = RegionId(UUID.randomUUID()),
            world = world,
            shape = RegionShape.CUBOID,
            bounds = Bounds(0, 15, 0, 255, 0, 15),
            owner = UUID.randomUUID()
        )
        regionService.createRegion(region)
        return Fixture(regionService, region, UUID.randomUUID())
    }

    @Test
    fun `role operations are actor aware idempotent and correlated`() {
        val audit = CapturingAudit()
        val fixture = fixture(audit)
        audit.entries.clear()
        val service = RegionRoleService(
            fixture.regionService,
            audit,
            RegionMutationAuthorizer { _, _, _ -> true }
        )
        val correlation = CorrelationId.newId()
        val request = RoleMutationRequest(
            actor = ActionActor.Player(fixture.actorId),
            regionId = fixture.region.id,
            targetPlayerId = UUID.randomUUID(),
            role = RegionRole.TRUSTED,
            kind = RoleMutationKind.ADD,
            platform = "test",
            correlationId = correlation
        )

        assertTrue(service.mutateRole(request).succeeded)
        assertTrue(service.mutateRole(request).succeeded)
        assertEquals(setOf(request.targetPlayerId), fixture.regionService.getRegionById(fixture.region.id)?.roles?.trusted)
        assertEquals("PLAYER", audit.entries.first().actorKind)
        assertTrue(audit.entries.all { it.correlationId == correlation })
        assertEquals(2, audit.entries.count { it.status == AuditStatus.COMPLETED })
        assertTrue(audit.entries.all { it.actionKey == HomeClaimOperationActions.ROLE_CHANGE })

        val denied = RegionRoleService(
            fixture.regionService,
            authorizer = RegionMutationAuthorizer { _, _, _ -> false }
        ).mutateRole(request.copy(targetPlayerId = UUID.randomUUID()))
        assertEquals(OperationStatus.DENIED, denied.status)
    }

    @Test
    fun `flag limit and profile operations validate and update runtime region`() {
        val audit = CapturingAudit()
        val fixture = fixture(audit)
        audit.entries.clear()
        val profiles = InMemoryFlagProfileService(listOf(
            FlagProfile(
                "quiet",
                flags = mapOf(FlagCatalog.PVP to PolicyValue.Bool(false)),
                limits = mapOf(LimitCatalog.COMPONENT_COOLDOWN_MS to PolicyValue.IntValue(-1))
            )
        ))
        val service = RegionAdminServiceImpl(fixture.regionService, profiles, auditService = audit)
        val context = RegionMutationContext(ActionActor.System("test"), "test", "admin_mutation")

        assertTrue(service.mutateFlag(context, fixture.region.id, FlagCatalog.PVP, PolicyValue.Bool(false)).succeeded)
        assertTrue(service.mutateFlag(context, fixture.region.id, FlagCatalog.PVP, null).succeeded)
        assertNull(fixture.regionService.getRegionById(fixture.region.id)?.flags?.get(FlagCatalog.PVP))
        assertEquals(
            OperationStatus.INVALID,
            service.mutateLimit(context, fixture.region.id, LimitCatalog.COMPONENT_COOLDOWN_MS, PolicyValue.IntValue(-2)).status
        )
        assertTrue(service.mutateLimit(context, fixture.region.id, LimitCatalog.COMPONENT_COOLDOWN_MS, PolicyValue.IntValue(-1)).succeeded)
        assertTrue(service.applyProfile(context, fixture.region.id, "quiet").succeeded)
        assertEquals(PolicyValue.Bool(false), fixture.regionService.getRegionById(fixture.region.id)?.flags?.get(FlagCatalog.PVP))
        assertEquals(PolicyValue.IntValue(-1), fixture.regionService.getRegionById(fixture.region.id)?.limits?.get(LimitCatalog.COMPONENT_COOLDOWN_MS))
        assertTrue(audit.entries.any { it.actionKey == HomeClaimOperationActions.FLAG_CHANGE && it.status == AuditStatus.COMPLETED })
        assertTrue(audit.entries.any { it.actionKey == HomeClaimOperationActions.PROFILE_CHANGE && it.status == AuditStatus.COMPLETED })

        val denied = RegionAdminServiceImpl(
            fixture.regionService,
            profiles,
            authorizer = RegionMutationAuthorizer { _, _, _ -> false }
        ).mutateFlag(context.copy(actor = ActionActor.Player(fixture.actorId)), fixture.region.id, FlagCatalog.PVP, PolicyValue.Bool(true))
        assertEquals(OperationStatus.DENIED, denied.status)
    }

    @Test
    fun `metadata operations are actor aware validated and correlated`() {
        val audit = CapturingAudit()
        val fixture = fixture(audit)
        audit.entries.clear()
        val service = RegionAdminServiceImpl(
            fixture.regionService,
            InMemoryFlagProfileService(),
            auditService = audit,
            authorizer = RegionMutationAuthorizer { _, _, _ -> true }
        )
        val context = RegionMutationContext(
            ActionActor.Player(fixture.actorId),
            "test",
            "metadata",
            CorrelationId.newId()
        )

        assertTrue(service.mutateMetadata(context, fixture.region.id, "alias", "My Plot").succeeded)
        assertEquals("My Plot", fixture.regionService.getRegionById(fixture.region.id)?.metadata?.get("alias"))
        assertEquals(OperationStatus.INVALID, service.mutateMetadata(context, fixture.region.id, "alias", "bad/alias").status)
        assertTrue(service.mutateMetadata(context, fixture.region.id, "alias", null).succeeded)
        assertNull(fixture.regionService.getRegionById(fixture.region.id)?.metadata?.get("alias"))
        assertTrue(audit.entries.all { it.actionKey == HomeClaimOperationActions.METADATA_CHANGE })
        assertTrue(audit.entries.all { it.correlationId == context.correlationId })
    }

    @Test
    fun `component operations persist player creator and audit create update remove`() {
        val fixture = fixture()
        val audit = CapturingAudit()
        val components = InMemoryComponentStore()
        val service = ComponentOperationService(
            components,
            fixture.regionService,
            audit,
            RegionMutationAuthorizer { _, _, _ -> true }
        )
        val context = RegionMutationContext(ActionActor.Player(fixture.actorId), "test", "component_change")
        val position = Position(WorldId.fromLegacyName(fixture.region.world), 2, 64, 2, fixture.region.world)
        val created = service.create(
            context,
            fixture.region.id,
            ComponentType.ELEVATOR_PAD,
            position,
            ElevatorConfig(),
            ComponentPolicy()
        )

        assertTrue(created.succeeded)
        assertEquals(fixture.actorId, created.value?.createdBy)
        val id = requireNotNull(created.value?.id)
        assertTrue(service.update(context, id, state = ComponentState.DISABLED).succeeded)
        assertEquals(ComponentState.DISABLED, components.getComponent(id)?.state)
        assertTrue(service.remove(context, id).succeeded)
        assertNull(components.getComponent(id))
        assertEquals(3, audit.entries.count { it.status == AuditStatus.COMPLETED })
        assertTrue(audit.entries.all { it.actionKey == HomeClaimOperationActions.COMPONENT_CHANGE })

        val outOfRegion = service.create(
            context,
            fixture.region.id,
            ComponentType.ELEVATOR_PAD,
            Position(WorldId.fromLegacyName(fixture.region.world), 40, 64, 40, fixture.region.world),
            ElevatorConfig(),
            ComponentPolicy()
        )
        assertEquals(OperationStatus.INVALID, outOfRegion.status)
        assertFalse(outOfRegion.succeeded)
        assertNotNull(audit.entries.last().error)
    }

    @Test
    fun `entry deny operations keep trusted system actor and one correlation`() {
        val fixture = fixture()
        val audit = CapturingAudit()
        val service = EntryDenyOperationService(InMemoryEntryDenies(), fixture.regionService, audit)
        val correlation = CorrelationId.newId()
        val context = RegionMutationContext(
            ActionActor.System("rest-admin-token"), "rest", "rest_entry_deny",
            correlationId = correlation
        )

        val created = service.create(
            context,
            fixture.region.id,
            EntryDenyTargetType.PLAYER,
            UUID.randomUUID().toString(),
            "test reason"
        )
        assertTrue(created.succeeded)
        assertEquals(UUID(0, 0), created.value?.createdBy) // legacy row identity; audit stores typed System actor.
        assertTrue(service.report(context, fixture.region.id, requireNotNull(created.value?.id), "review").succeeded)
        assertTrue(service.revoke(context, fixture.region.id, requireNotNull(created.value?.id), "resolved").succeeded)
        assertTrue(audit.entries.all { it.correlationId == correlation })
        assertTrue(audit.entries.all { it.actorKind == "SYSTEM" && it.actorRef == "rest-admin-token" })
    }
}
