package systems.diath.homeclaim.core.policy

import systems.diath.homeclaim.core.model.*
import systems.diath.homeclaim.core.permission.*
import systems.diath.homeclaim.core.platform.PolicyGuard
import systems.diath.homeclaim.core.service.InMemoryWorldAreaService
import systems.diath.homeclaim.core.service.OperationStatus
import systems.diath.homeclaim.core.service.RegionOperationLimitService
import systems.diath.homeclaim.core.store.InMemoryRegionStore
import systems.diath.homeclaim.core.store.InMemoryZoneStore
import systems.diath.homeclaim.core.economy.SimpleEconService
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthorizationDecisionTest {
    private val worldId = WorldId.newId()
    private val areaId = AreaId.legacyDefault(worldId)
    private val owner = UUID.randomUUID()
    private val trusted = UUID.randomUUID()
    private val member = UUID.randomUUID()
    private val visitor = UUID.randomUUID()
    private val regionId = RegionId(UUID.randomUUID())
    private val position = Position(worldId, 5, 5, 5)

    private fun region(
        flags: Map<FlagKey, FlagValue> = emptyMap(),
        roles: RegionRoles = RegionRoles(trusted = setOf(trusted), members = setOf(member)),
        id: RegionId = regionId,
        regionArea: AreaId = areaId,
        regionOwner: PlayerId = owner
    ) = Region(
        id = id, world = "world", areaId = regionArea, shape = RegionShape.CUBOID,
        bounds = Bounds(0, 10, 0, 10, 0, 10), owner = regionOwner, roles = roles, flags = flags
    )

    private fun service(
        role: (PlayerId) -> RegionRole = { player -> region().roles.resolve(player, owner) },
        regions: InMemoryRegionStore = InMemoryRegionStore().also { it.createRegion(region(), region().bounds) },
        zones: InMemoryZoneStore = InMemoryZoneStore(),
        provider: CapabilityPermissionProvider? = null,
        areas: InMemoryWorldAreaService? = null
    ) = SimplePolicyService(
        regions, zones, { _, player -> role(player) }, areaService = areas, permissionProvider = provider
    )

    private fun grant(permission: String) = CapabilityPermissionProvider { _, asked, _ ->
        if (permission == asked) PermissionResult.GRANTED else PermissionResult.DENIED
    }

    private fun operationRequest(actionKey: ActionKey, region: Region) = ActionRequest(
        actor = ActionActor.Player(owner),
        actionKey = actionKey,
        target = ActionEndpoint(resource = ActionResourceRef.Region(region.id), areaId = region.areaId),
        regionId = region.id,
        areaId = region.areaId
    )

    @Test fun `world outside configured area returns not applicable before role check`() {
        val areas = InMemoryWorldAreaService().also { it.createWorld("world")
            .let { world -> it.createArea(Area(areaId, world.id, "plots", AreaScope.Finite(Bounds(0, 10, 0, 10, 0, 10)))) }
        }
        val decision = service(areas = areas).evaluate(visitor, Action.REGION_BUILD, Position("world", 20, 5, 5))
        assertEquals(DecisionEffect.NOT_APPLICABLE, decision.effect)
        assertEquals(DecisionSource.SCOPE, decision.source)
    }

    @Test fun `area match lets the policy resolver evaluate a region`() {
        val areas = InMemoryWorldAreaService().also { store ->
            val world = store.createWorld("world")
            store.createArea(Area(areaId, world.id, "plots", AreaScope.WholeWorld))
        }
        val scopedRegion = region(regionArea = areaId)
        val store = InMemoryRegionStore().also { it.createRegion(scopedRegion, scopedRegion.bounds) }
        val decision = service(areas = areas, regions = store).evaluate(owner, Action.REGION_BUILD, Position("world", 5, 5, 5))
        assertEquals(DecisionEffect.ALLOW, decision.effect)
        assertEquals(areaId, decision.context.areaId)
    }

    @Test fun `same coordinates in another area do not resolve the first areas region`() {
        val otherArea = AreaId.newId()
        val store = InMemoryRegionStore().also { it.createRegion(region(), region().bounds) }
        assertEquals(regionId, store.getRegionAt(areaId, 5, 5, 5))
        assertEquals(null, store.getRegionAt(otherArea, 5, 5, 5))
    }

    @Test fun `same coordinates in another world do not resolve the first worlds region`() {
        val store = InMemoryRegionStore().also { it.createRegion(region(), region().bounds) }
        assertEquals(null, store.getRegionAt(WorldId.newId(), 5, 5, 5))
    }

    @Test fun `owner role is resolved`() = assertEquals(RegionRole.OWNER, region().roles.resolve(owner, owner))
    @Test fun `trusted role is resolved`() = assertEquals(RegionRole.TRUSTED, region().roles.resolve(trusted, owner))
    @Test fun `member role is resolved`() = assertEquals(RegionRole.MEMBER, region().roles.resolve(member, owner))
    @Test fun `visitor role is resolved`() = assertEquals(RegionRole.VISITOR, region().roles.resolve(visitor, owner))
    @Test fun `ban wins even if actor is owner`() = assertEquals(RegionRole.BANNED, RegionRoles(banned = setOf(owner)).resolve(owner, owner))

    @Test fun `owner cannot override explicit local deny`() {
        val store = InMemoryRegionStore().also { it.createRegion(region(mapOf(FlagCatalog.BUILD to PolicyValue.Bool(false))), region().bounds) }
        val decision = service(regions = store).evaluate(owner, Action.REGION_BUILD, position)
        assertEquals(DecisionEffect.DENY, decision.effect)
        assertEquals(DecisionSource.POLICY, decision.source)
    }

    @Test fun `trusted and member can perform inherited actions`() {
        assertEquals(DecisionEffect.ALLOW, service().evaluate(trusted, Action.REGION_BUILD, position).effect)
        assertEquals(DecisionEffect.ALLOW, service().evaluate(member, Action.REGION_BUILD, position).effect)
    }

    @Test fun `visitor is denied when neither role nor permission grants action`() {
        val decision = service().evaluate(visitor, Action.REGION_BUILD, position)
        assertEquals(DecisionEffect.DENY, decision.effect)
        assertEquals(DecisionReason.ROLE_REQUIRED, decision.reason)
    }

    @Test fun `staff role without an explicit bypass remains under normal rules`() {
        val decision = service(role = { RegionRole.VISITOR }, provider = CapabilityPermissionProvider { _, _, _ -> PermissionResult.DENIED })
            .evaluate(visitor, Action.REGION_BUILD, position)
        assertEquals(DecisionEffect.DENY, decision.effect)
        assertEquals(DecisionReason.NO_PERMISSION, decision.reason)
    }

    @Test fun `member permission semantics are permanent and shared by both role models`() {
        assertTrue(RegionRole.MEMBER.allowsInheritedAction())
        assertTrue(PlotMemberRole.MEMBER.toRegionRole().allowsInheritedAction())
        assertFalse(PlotMemberRole.GUEST.toRegionRole().allowsInheritedAction())
    }

    @Test fun `ban defeats trusted and member memberships`() {
        val roles = RegionRoles(trusted = setOf(trusted), members = setOf(member), banned = setOf(trusted, member))
        assertEquals(RegionRole.BANNED, roles.resolve(trusted, owner))
        assertEquals(RegionRole.BANNED, roles.resolve(member, owner))
        assertEquals(DecisionEffect.DENY, service(role = { RegionRole.BANNED }).evaluate(trusted, Action.REGION_BUILD, position).effect)
    }

    @Test fun `explicit policy allow applies to visitors`() {
        val store = InMemoryRegionStore().also { it.createRegion(region(mapOf(FlagCatalog.BUILD to PolicyValue.Bool(true))), region().bounds) }
        assertEquals(DecisionEffect.ALLOW, service(regions = store).evaluate(visitor, Action.REGION_BUILD, position).effect)
    }

    @Test fun `explicit policy deny beats granted capability`() {
        val store = InMemoryRegionStore().also { it.createRegion(region(mapOf(FlagCatalog.BUILD to PolicyValue.Bool(false))), region().bounds) }
        val decision = service(regions = store, provider = grant(PolicyPermissions.forAction(Action.REGION_BUILD)))
            .evaluate(visitor, Action.REGION_BUILD, position)
        assertEquals(DecisionEffect.DENY, decision.effect)
    }

    @Test fun `missing policy is represented as inherit at policy layer`() {
        assertEquals(DecisionEffect.INHERIT, RegionPolicy.flagEffect(EffectivePolicy(emptyMap(), emptyMap(), emptySet()), FlagCatalog.BUILD))
    }

    @Test fun `locked zone deny beats owner role and capability`() {
        val store = InMemoryRegionStore().also { it.createRegion(region(flags = emptyMap()), region().bounds) }
        val zones = InMemoryZoneStore().also { it.createZone(Zone(
            id = ZoneId(UUID.randomUUID()), world = "world", areaId = areaId, shape = RegionShape.CUBOID,
            bounds = Bounds(0, 10, 0, 10, 0, 10), priority = 20,
            defaults = ZoneDefaults(mapOf(FlagCatalog.BUILD to PolicyValue.Bool(false))), lockedFlags = setOf(FlagCatalog.BUILD)
        )) }
        val decision = service(regions = store, zones = zones, provider = grant(PolicyPermissions.forAction(Action.REGION_BUILD)))
            .evaluate(owner, Action.REGION_BUILD, position)
        assertEquals(DecisionEffect.DENY, decision.effect)
        assertTrue(decision.trace.lockedPolicy)
    }

    @Test fun `lower priority zone cannot override higher priority zone`() {
        val store = InMemoryRegionStore().also { it.createRegion(region(), region().bounds) }
        val zones = InMemoryZoneStore().also {
            it.createZone(Zone(ZoneId(UUID.randomUUID()), "world", RegionShape.CUBOID, Bounds(0, 10, 0, 10, 0, 10), 20,
                ZoneDefaults(mapOf(FlagCatalog.BUILD to PolicyValue.Bool(false))), lockedFlags = setOf(FlagCatalog.BUILD), areaId = areaId))
            it.createZone(Zone(ZoneId(UUID.randomUUID()), "world", RegionShape.CUBOID, Bounds(0, 10, 0, 10, 0, 10), 10,
                ZoneDefaults(mapOf(FlagCatalog.BUILD to PolicyValue.Bool(true))), areaId = areaId))
        }
        assertEquals(DecisionEffect.DENY, service(regions = store, zones = zones).evaluate(owner, Action.REGION_BUILD, position).effect)
    }

    @Test fun `locked policy denies owner trusted member and visitor without bypass`() {
        val store = InMemoryRegionStore().also { it.createRegion(region(), region().bounds) }
        val zones = InMemoryZoneStore().also { it.createZone(Zone(
            ZoneId(UUID.randomUUID()), "world", RegionShape.CUBOID, Bounds(0, 10, 0, 10, 0, 10), 1,
            ZoneDefaults(mapOf(FlagCatalog.BUILD to PolicyValue.Bool(false))), lockedFlags = setOf(FlagCatalog.BUILD), areaId = areaId
        )) }
        val resolver = service(regions = store, zones = zones)
        listOf(owner, trusted, member, visitor).forEach { assertEquals(DecisionEffect.DENY, resolver.evaluate(it, Action.REGION_BUILD, position).effect) }
    }

    @Test fun `permission provider granted denied and undefined results are distinct`() {
        assertEquals(PermissionResult.GRANTED, grant("x").check(PolicyActorContext(), "x", PermissionContext()))
        assertEquals(PermissionResult.DENIED, grant("x").check(PolicyActorContext(), "y", PermissionContext()))
        assertEquals(PermissionResult.UNDEFINED, CapabilityPermissionProvider { _, _, _ -> PermissionResult.UNDEFINED }
            .check(PolicyActorContext(), "x", PermissionContext()))
    }

    @Test fun `granted action capability permits visitor under inherited policy`() {
        val decision = service(provider = grant(PolicyPermissions.forAction(Action.REGION_BUILD)))
            .evaluate(visitor, Action.REGION_BUILD, position)
        assertEquals(DecisionEffect.ALLOW, decision.effect)
        assertEquals(DecisionSource.PERMISSION, decision.source)
    }

    @Test fun `explicit protection bypass is the only staff policy bypass`() {
        val store = InMemoryRegionStore().also { it.createRegion(region(mapOf(FlagCatalog.BUILD to PolicyValue.Bool(false))), region().bounds) }
        val bypass = grant(PolicyPermissions.PROTECTION_BYPASS)
        val decision = service(regions = store, provider = bypass).evaluate(visitor, Action.REGION_BUILD, position)
        assertEquals(DecisionEffect.ALLOW, decision.effect)
        assertEquals(DecisionReason.BYPASS_PROTECTION, decision.reason)
        assertEquals(PolicyPermissions.PROTECTION_BYPASS, decision.trace.bypass)
    }

    @Test fun `explicit locked policy bypass allows locked deny and is traced`() {
        val store = InMemoryRegionStore().also { it.createRegion(region(), region().bounds) }
        val zones = InMemoryZoneStore().also { it.createZone(Zone(
            ZoneId(UUID.randomUUID()), "world", RegionShape.CUBOID, Bounds(0, 10, 0, 10, 0, 10), 1,
            ZoneDefaults(mapOf(FlagCatalog.BUILD to PolicyValue.Bool(false))), lockedFlags = setOf(FlagCatalog.BUILD), areaId = areaId
        )) }
        val decision = service(regions = store, zones = zones, provider = grant(PolicyPermissions.LOCKED_POLICY_BYPASS))
            .evaluate(visitor, Action.REGION_BUILD, position)
        assertEquals(DecisionEffect.ALLOW, decision.effect)
        assertEquals(DecisionReason.BYPASS_LOCKED_POLICY, decision.reason)
    }

    @Test fun `entry deny remains ahead of member access`() {
        val store = InMemoryRegionStore().also { it.createRegion(region(), region().bounds) }
        val entryDenies = systems.diath.homeclaim.core.store.InMemoryEntryDenyService().also {
            it.createRule(regionId, EntryDenyTargetType.PLAYER, member.toString(), "rule", owner)
        }
        val policy = SimplePolicyService(store, InMemoryZoneStore(), { _, _ -> RegionRole.MEMBER }, entryDenyService = entryDenies)
        val decision = policy.evaluate(member, Action.REGION_ENTER, position)
        assertEquals(DecisionEffect.DENY, decision.effect)
        assertEquals(DecisionReason.ENTRY_DENY, decision.reason)
    }

    @Test fun `entry deny also applies to owner unless explicit bypass is granted`() {
        val store = InMemoryRegionStore().also { it.createRegion(region(), region().bounds) }
        val entryDenies = systems.diath.homeclaim.core.store.InMemoryEntryDenyService().also {
            it.createRule(regionId, EntryDenyTargetType.PLAYER, owner.toString(), "owner restriction", owner)
        }
        val policy = SimplePolicyService(store, InMemoryZoneStore(), { _, _ -> RegionRole.OWNER }, entryDenyService = entryDenies)
        assertEquals(DecisionReason.ENTRY_DENY, policy.evaluate(owner, Action.REGION_ENTER, position).reason)
        assertEquals(DecisionReason.BYPASS_ENTRY,
            policy.evaluate(owner, Action.REGION_ENTER, position, mapOf("entryBypass" to true)).reason)
    }

    @Test fun `explicit entry bypass can bypass a ban and is traced`() {
        val policy = service(role = { RegionRole.BANNED }, provider = grant(PolicyPermissions.ENTRY_BYPASS))
        val decision = policy.evaluate(visitor, Action.REGION_ENTER, position)
        assertEquals(DecisionEffect.ALLOW, decision.effect)
        assertEquals(DecisionReason.BYPASS_ENTRY, decision.reason)
    }

    @Test fun `claim quota is enforced through shared operation service`() {
        val store = InMemoryRegionStore().also {
            it.createRegion(region(regionOwner = owner), region().bounds)
            it.createRegion(region(id = RegionId(UUID.randomUUID()), regionOwner = RegionOwnership.UNCLAIMED_OWNER_ID), region().bounds)
        }
        val limits = RegionOperationLimitService(store, maxOwnedRegions = 1)
        assertFalse(limits.evaluateClaim(owner).allowed)
        assertTrue(limits.evaluateClaim(visitor).allowed)
        val unclaimedId = RegionId(UUID.randomUUID())
        store.createRegion(region(id = unclaimedId, regionOwner = RegionOwnership.UNCLAIMED_OWNER_ID), region().bounds)
        assertFalse(limits.claimRegion(unclaimedId, owner, 0.0, SimpleEconService()))
        assertEquals(RegionOwnership.UNCLAIMED_OWNER_ID, store.getRegionById(unclaimedId)?.owner)
    }

    @Test fun `claim quota bypass is explicit and operation applies it`() {
        val store = InMemoryRegionStore().also { it.createRegion(region(regionOwner = owner), region().bounds) }
        val limits = RegionOperationLimitService(store, maxOwnedRegions = 1)
        assertTrue(limits.evaluateClaim(owner, bypass = true).allowed)
        val target = RegionId(UUID.randomUUID())
        store.createRegion(region(id = target, regionOwner = RegionOwnership.UNCLAIMED_OWNER_ID), region().bounds)
        assertTrue(limits.claimRegion(target, owner, 0.0, SimpleEconService(), bypass = true))
    }

    @Test fun `claim operation returns structured success after mutation`() {
        val unclaimed = region(regionOwner = RegionOwnership.UNCLAIMED_OWNER_ID)
        val store = InMemoryRegionStore().also { it.createRegion(unclaimed, unclaimed.bounds) }
        val limits = RegionOperationLimitService(store)
        var authorized = false

        val result = limits.claim(
            operationRequest(HomeClaimOperationActions.REGION_CLAIM, unclaimed),
            unclaimed.id,
            owner,
            0.0,
            SimpleEconService(),
            onAuthorized = { authorized = true }
        )

        assertEquals(OperationStatus.SUCCESS, result.status)
        assertEquals(owner, result.value?.owner)
        assertTrue(authorized)
    }

    @Test fun `claim operation distinguishes scope actor and quota failures`() {
        val unclaimed = region(regionOwner = RegionOwnership.UNCLAIMED_OWNER_ID)
        val store = InMemoryRegionStore().also { it.createRegion(unclaimed, unclaimed.bounds) }
        val limits = RegionOperationLimitService(store, maxOwnedRegions = 0)
        var authorized = false

        val noArea = operationRequest(HomeClaimOperationActions.REGION_CLAIM, unclaimed).copy(areaId = null)
        assertEquals(OperationStatus.NOT_APPLICABLE,
            limits.claim(noArea, unclaimed.id, owner, 0.0, SimpleEconService()).status)

        val wrongActor = operationRequest(HomeClaimOperationActions.REGION_CLAIM, unclaimed)
            .copy(actor = ActionActor.Player(visitor))
        assertEquals(OperationStatus.INVALID,
            limits.claim(wrongActor, unclaimed.id, owner, 0.0, SimpleEconService()).status)

        assertEquals(OperationStatus.LIMIT_REACHED,
            limits.claim(
                operationRequest(HomeClaimOperationActions.REGION_CLAIM, unclaimed),
                unclaimed.id,
                owner,
                0.0,
                SimpleEconService(),
                onAuthorized = { authorized = true }
            ).status)
        assertFalse(authorized)
        assertEquals(RegionOwnership.UNCLAIMED_OWNER_ID, store.getRegionById(unclaimed.id)?.owner)
    }

    @Test fun `merge operation returns a typed group id on success`() {
        val first = region()
        val second = region(id = RegionId(UUID.randomUUID()))
        val store = InMemoryRegionStore().also {
            it.createRegion(first, first.bounds)
            it.createRegion(second, second.bounds)
        }
        val result = RegionOperationLimitService(store).merge(
            operationRequest(HomeClaimOperationActions.REGION_MERGE, first),
            listOf(first.id, second.id)
        )

        assertEquals(OperationStatus.SUCCESS, result.status)
        assertNotNull(result.value)
        assertEquals(result.value, store.getRegionById(first.id)?.mergeGroupId)
        assertEquals(result.value, store.getRegionById(second.id)?.mergeGroupId)
    }

    @Test fun `merge operation rejects mixed areas and reports configured limit`() {
        val first = region()
        val otherArea = region(id = RegionId(UUID.randomUUID()), regionArea = AreaId.newId())
        val store = InMemoryRegionStore().also {
            it.createRegion(first, first.bounds)
            it.createRegion(otherArea, otherArea.bounds)
        }
        val limits = RegionOperationLimitService(store, maxMergedRegions = 1)
        val request = operationRequest(HomeClaimOperationActions.REGION_MERGE, first)

        assertEquals(OperationStatus.CONFLICT, limits.merge(request, listOf(first.id, otherArea.id)).status)
        val third = region(id = RegionId(UUID.randomUUID()))
        store.createRegion(third, third.bounds)
        assertEquals(OperationStatus.LIMIT_REACHED, limits.merge(request, listOf(first.id, third.id)).status)
    }

    @Test fun `concurrent claims cannot exceed one plot per player`() {
        val first = RegionId(UUID.randomUUID())
        val second = RegionId(UUID.randomUUID())
        val store = InMemoryRegionStore().also {
            it.createRegion(region(id = first, regionOwner = RegionOwnership.UNCLAIMED_OWNER_ID), region().bounds)
            it.createRegion(region(id = second, regionOwner = RegionOwnership.UNCLAIMED_OWNER_ID), region().bounds)
        }
        val limits = RegionOperationLimitService(store, maxOwnedRegions = 1)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(first, second).map { target ->
                pool.submit<Boolean> {
                    ready.countDown()
                    start.await()
                    limits.claimRegion(target, owner, 0.0, SimpleEconService())
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(1, futures.count { it.get(5, TimeUnit.SECONDS) })
            assertEquals(1, store.listRegionsByOwner(owner).size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun `repository will not overwrite an already claimed region`() {
        val claimed = region(regionOwner = owner)
        val store = InMemoryRegionStore().also { it.createRegion(claimed, claimed.bounds) }

        assertFalse(store.claimRegion(claimed.id, visitor, 0.0, SimpleEconService()))
        assertEquals(owner, store.getRegionById(claimed.id)?.owner)
    }

    @Test fun `merge quota enforced in shared operation service`() {
        val first = region()
        val second = region(id = RegionId(UUID.randomUUID()))
        val store = InMemoryRegionStore().also { it.createRegion(first, first.bounds); it.createRegion(second, second.bounds) }
        val limits = RegionOperationLimitService(store, maxMergedRegions = 1)
        assertFalse(limits.evaluateMerge(listOf(first.id, second.id)).allowed)
        assertTrue(limits.evaluateMerge(listOf(first.id), bypass = false).allowed)
        assertTrue(runCatching { limits.mergeRegions(listOf(first.id, second.id)) }.isFailure)
    }

    @Test fun `merge requires regions from same owner and area`() {
        val first = region()
        val second = region(id = RegionId(UUID.randomUUID()), regionOwner = visitor)
        val store = InMemoryRegionStore().also { it.createRegion(first, first.bounds); it.createRegion(second, second.bounds) }
        assertEquals("MERGE_RESOURCE_MISMATCH", RegionOperationLimitService(store).evaluateMerge(listOf(first.id, second.id)).reason)
    }

    @Test fun `unlimited quotas remain unlimited`() {
        val store = InMemoryRegionStore()
        val limits = RegionOperationLimitService(store)
        assertTrue(limits.evaluateClaim(owner).allowed)
        assertTrue(limits.evaluateMerge(emptyList()).allowed)
    }

    @Test fun `typed ActionRequest and PolicyGuard return the same authorization`() {
        val store = InMemoryRegionStore().also { it.createRegion(region(), region().bounds) }
        val zones = InMemoryZoneStore()
        val policy = SimplePolicyService(store, zones, { _, actor -> region().roles.resolve(actor, owner) })
        val direct = policy.evaluate(trusted, Action.REGION_BUILD, position)
        val guarded = PolicyGuard(policy, store).check(ActionRequest(
            actor = ActionActor.Player(trusted),
            actionKey = Action.REGION_BUILD.key,
            target = ActionEndpoint(position)
        ))
        assertEquals(direct.effect, guarded.effect)
        assertEquals(direct.reason, guarded.reason)
    }

    @Test fun `non-player policy actor has no synthetic player UUID`() {
        val store = InMemoryRegionStore().also { it.createRegion(region(), region().bounds) }
        val policy = service(regions = store)
        val decision = PolicyGuard(policy, store).check(ActionRequest(
            actor = ActionActor.System("test"),
            actionKey = Action.REGION_BUILD.key,
            target = ActionEndpoint(position)
        ))
        assertEquals(null, decision.context.playerId)
        assertEquals(DecisionEffect.DENY, decision.effect)
    }

    @Test fun `decision exposes role and policy trace`() {
        val decision = service().evaluate(trusted, Action.REGION_BUILD, position)
        assertNotNull(decision.trace.role)
        assertEquals(DecisionSource.ROLE, decision.source)
    }
}
