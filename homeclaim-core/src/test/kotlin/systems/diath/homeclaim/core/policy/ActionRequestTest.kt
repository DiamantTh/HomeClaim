package systems.diath.homeclaim.core.policy

import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.Area
import systems.diath.homeclaim.core.model.AreaScope
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.ComponentId
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.PolicyValue
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.PlotMemberRole
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionRole
import systems.diath.homeclaim.core.model.RegionRoles
import systems.diath.homeclaim.core.model.RegionShape
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.model.toRegionRole
import systems.diath.homeclaim.core.platform.PolicyGuard
import systems.diath.homeclaim.core.permission.CapabilityPermissionProvider
import systems.diath.homeclaim.core.permission.PermissionResult
import systems.diath.homeclaim.core.permission.PolicyPermissions
import systems.diath.homeclaim.core.service.AuditEntries
import systems.diath.homeclaim.core.service.AuditPolicy
import systems.diath.homeclaim.core.service.AuditStatus
import systems.diath.homeclaim.core.service.AuditEntry
import systems.diath.homeclaim.core.policy.BulkActionRequest
import systems.diath.homeclaim.core.service.InMemoryWorldAreaService
import systems.diath.homeclaim.core.policy.ActionResourceRef.Region as RegionResource
import systems.diath.homeclaim.core.store.InMemoryRegionStore
import systems.diath.homeclaim.core.store.InMemoryZoneStore
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActionRequestTest {
    private val world = WorldId.fromLegacyName("action-test")
    private val areaOne = AreaId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
    private val areaTwo = AreaId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
    private val owner: PlayerId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val visitor: PlayerId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @Test
    fun `paper world uuid resolves to canonical homeclaim world id after display rename`() {
        val platformWorldId = UUID.randomUUID()
        val worldService = InMemoryWorldAreaService()
        val homeWorld = worldService.createWorld("old-display-name", platformWorldId)
        val area = worldService.createArea(Area(AreaId.newId(), homeWorld.id, "plots", AreaScope.WholeWorld))
        worldService.renameWorld(homeWorld.id, "renamed-display-name")
        val regionId = RegionId(UUID.randomUUID())
        val regions = InMemoryRegionStore().also {
            it.createRegion(region(regionId, area.id, emptyMap()).copy(world = "old-display-name"), Bounds(0, 10, 0, 100, 0, 10))
        }
        val policy = SimplePolicyService(regions, InMemoryZoneStore(), { _, _ -> RegionRole.OWNER }, areaService = worldService)
        val guard = PolicyGuard(policy, regions, worldService)
        val endpoint = guard.resolveEndpointAt(
            Position(WorldId(platformWorldId), 5, 64, 5, legacyWorldName = "renamed-display-name")
        )

        assertEquals(homeWorld.id, endpoint.position?.worldId)
        assertEquals(area.id, endpoint.areaId)
        assertEquals(regionId, (endpoint.resource as RegionResource).regionId)
    }

    @Test
    fun `built in action keys are stable namespaced and map to legacy actions`() {
        assertEquals("homeclaim:region_build", Action.REGION_BUILD.key.value)
        assertEquals(Action.REGION_BUILD, ActionKey.toLegacy(ActionKey.parse("homeclaim:region_build")))
        assertEquals(Action.entries.size, Action.entries.map { it.key }.toSet().size)
        Action.entries.forEach { assertEquals(it, ActionKey.toLegacy(it.key)) }
        assertFailsForInvalidKey("region_build")
    }

    @Test
    fun `native automation reaches explicit resource policy without becoming a player`() {
        val regionId = RegionId(UUID.randomUUID())
        val regions = InMemoryRegionStore().also {
            it.createRegion(region(regionId, areaOne, mapOf(FlagCatalog.BUILD to PolicyValue.Bool(true))), Bounds(0, 10, 0, 100, 0, 10))
        }
        val request = ActionRequest(
            actor = ActionActor.Automation("piston-1", "minecraft:piston"),
            actionKey = Action.PISTON_MOVE.key,
            target = ActionEndpoint(Position(world, 5, 64, 5), RegionResource(regionId), areaOne),
            context = ActionContext(modPolicyDefaults = ModPolicyDefaults(allowAutomation = true))
        )

        val decision = PolicyGuard(policy(regions, RegionRole.VISITOR), regions).check(request)

        assertEquals(DecisionEffect.ALLOW, decision.effect)
        assertEquals("AUTOMATION", decision.context.extra[PolicyActorContext.EXTRA_KIND])
    }

    @Test
    fun `native automation still obeys explicit resource deny`() {
        val regionId = RegionId(UUID.randomUUID())
        val regions = InMemoryRegionStore().also {
            it.createRegion(region(regionId, areaOne, mapOf(FlagCatalog.BUILD to PolicyValue.Bool(false))), Bounds(0, 10, 0, 100, 0, 10))
        }
        val request = ActionRequest(
            actor = ActionActor.Automation("piston-1", "minecraft:piston"),
            actionKey = Action.PISTON_MOVE.key,
            target = ActionEndpoint(Position(world, 5, 64, 5), RegionResource(regionId), areaOne),
            context = ActionContext(modPolicyDefaults = ModPolicyDefaults(allowAutomation = true))
        )

        val decision = PolicyGuard(policy(regions, RegionRole.OWNER), regions).check(request)

        assertEquals(DecisionEffect.DENY, decision.effect)
        assertEquals(DecisionReason.FLAG_DENY, decision.reason)
    }

    @Test
    fun `dispenser entity effects use entity policy while block effects use build policy`() {
        val regionId = RegionId(UUID.randomUUID())
        val regions = InMemoryRegionStore().also {
            it.createRegion(
                region(regionId, areaOne, mapOf(
                    FlagCatalog.BUILD to PolicyValue.Bool(true),
                    FlagCatalog.ENTITY_DAMAGE to PolicyValue.Bool(false)
                )),
                Bounds(0, 10, 0, 100, 0, 10)
            )
        }
        val service = policy(regions, RegionRole.VISITOR)
        val endpoint = ActionEndpoint(Position(world, 5, 64, 5), RegionResource(regionId), areaOne)
        val nativeDefaults = ActionContext(modPolicyDefaults = ModPolicyDefaults(allowAutomation = true))

        val blockEffect = PolicyGuard(service, regions).check(ActionRequest(
            actor = ActionActor.Automation("dispenser", "minecraft:dispenser"),
            actionKey = Action.DISPENSER_USE.key,
            target = endpoint,
            context = nativeDefaults
        ))
        val entityEffect = PolicyGuard(service, regions).check(ActionRequest(
            actor = ActionActor.Automation("dispenser", "minecraft:dispenser"),
            actionKey = Action.DISPENSER_ENTITY_USE.key,
            target = endpoint,
            context = nativeDefaults
        ))

        assertEquals(DecisionEffect.ALLOW, blockEffect.effect)
        assertEquals(DecisionEffect.DENY, entityEffect.effect)
        assertEquals(FlagCatalog.ENTITY_DAMAGE.value, entityEffect.trace.policyFlag)
    }

    @Test
    fun `request retains player source target and independent world area resources`() {
        val correlation = CorrelationId(UUID.randomUUID())
        val sourcePosition = Position(world, 1, 64, 1)
        val targetPosition = Position(WorldId(UUID.randomUUID()), 2, 64, 2)
        val request = ActionRequest(
            actor = ActionActor.Player(visitor),
            actionKey = Action.REGION_PVP.key,
            source = ActionEndpoint(sourcePosition, RegionResource(RegionId(UUID.randomUUID())), areaOne),
            target = ActionEndpoint(targetPosition, RegionResource(RegionId(UUID.randomUUID())), areaTwo),
            correlationId = correlation
        )

        assertEquals(visitor, (request.actor as ActionActor.Player).playerId)
        assertEquals(world, request.source?.worldId)
        assertEquals(targetPosition.worldId, request.target?.worldId)
        assertEquals(areaOne, request.source?.areaId)
        assertEquals(areaTwo, request.target?.areaId)
        assertEquals(correlation, request.correlationId)
    }

    @Test
    fun `policy decision retains matching source and target region`() {
        val regionId = RegionId(UUID.randomUUID())
        val regions = InMemoryRegionStore().also { it.createRegion(region(regionId, areaOne, emptyMap())) }
        val request = ActionRequest(
            actor = ActionActor.Player(owner),
            actionKey = Action.REGION_PVP.key,
            source = ActionEndpoint(Position(world, 1, 64, 1), RegionResource(regionId), areaOne),
            target = ActionEndpoint(Position(world, 2, 64, 2), RegionResource(regionId), areaOne)
        )

        val decision = PolicyGuard(policy(regions, RegionRole.OWNER), regions).check(request)
        val resolved = decision.context.actionRequest!!

        assertEquals(DecisionEffect.ALLOW, decision.effect)
        assertEquals(resolved.source?.resource, resolved.target?.resource)
        assertEquals(areaOne, resolved.source?.areaId)
        assertEquals(areaOne, resolved.target?.areaId)
    }

    @Test
    fun `policy decision preserves source and target across areas and worlds`() {
        val otherWorld = WorldId(UUID.randomUUID())
        val sourceRegion = RegionId(UUID.randomUUID())
        val targetRegion = RegionId(UUID.randomUUID())
        val regions = InMemoryRegionStore().also {
            it.createRegion(region(sourceRegion, areaOne, emptyMap()))
            it.createRegion(region(targetRegion, areaTwo, emptyMap()))
        }
        val request = ActionRequest(
            actor = ActionActor.Player(owner),
            actionKey = Action.REGION_PVP.key,
            source = ActionEndpoint(Position(world, 1, 64, 1), RegionResource(sourceRegion), areaOne),
            target = ActionEndpoint(Position(otherWorld, 2, 64, 2), RegionResource(targetRegion), areaTwo)
        )

        val decision = PolicyGuard(policy(regions, RegionRole.OWNER), regions).check(request)
        val resolved = decision.context.actionRequest!!

        assertEquals(DecisionEffect.ALLOW, decision.effect)
        assertEquals(world, resolved.source?.worldId)
        assertEquals(otherWorld, resolved.target?.worldId)
        assertEquals(RegionResource(sourceRegion), resolved.source?.resource)
        assertEquals(RegionResource(targetRegion), resolved.target?.resource)
        assertEquals(areaOne, resolved.source?.areaId)
        assertEquals(areaTwo, resolved.target?.areaId)
    }

    @Test
    fun `cross boundary guard denies when either managed endpoint denies`() {
        val sourceRegion = RegionId(UUID.randomUUID())
        val targetRegion = RegionId(UUID.randomUUID())
        val regions = InMemoryRegionStore().also {
            it.createRegion(region(sourceRegion, areaOne, mapOf(FlagCatalog.BUILD to PolicyValue.Bool(true))))
            it.createRegion(region(targetRegion, areaTwo, mapOf(FlagCatalog.BUILD to PolicyValue.Bool(false))))
        }
        val request = ActionRequest(
            actor = ActionActor.Player(visitor),
            actionKey = Action.REGION_BUILD.key,
            source = ActionEndpoint(Position(world, 1, 64, 1), RegionResource(sourceRegion), areaOne),
            target = ActionEndpoint(Position(world, 20, 64, 20), RegionResource(targetRegion), areaTwo)
        )

        val decision = PolicyGuard(policy(regions, RegionRole.VISITOR), regions).checkAcross(request)

        assertEquals(DecisionEffect.DENY, decision.effect)
        assertEquals(targetRegion, decision.context.regionId)
        assertEquals(request.source, decision.context.actionRequest?.source)
        assertEquals(request.target, decision.context.actionRequest?.target)
    }

    @Test
    fun `cross boundary with no managed endpoint stays not applicable`() {
        val regions = InMemoryRegionStore()
        val guard = PolicyGuard(policy(regions, RegionRole.VISITOR), regions, InMemoryWorldAreaService())
        val request = ActionRequest(
            actor = ActionActor.Automation("water", "minecraft:liquid"),
            actionKey = Action.LIQUID_FLOW.key,
            source = ActionEndpoint(Position(world, 500, 64, 500)),
            target = ActionEndpoint(Position(world, 501, 64, 500))
        )

        val decision = guard.checkAcross(request)

        assertEquals(DecisionEffect.NOT_APPLICABLE, decision.effect)
    }

    @Test
    fun `bulk boundary evaluation shares one source decision and retains each target`() {
        val sourceRegion = RegionId(UUID.randomUUID())
        val deniedRegion = RegionId(UUID.randomUUID())
        val allowedRegion = RegionId(UUID.randomUUID())
        val regions = InMemoryRegionStore().also {
            it.createRegion(region(sourceRegion, areaOne, mapOf(FlagCatalog.BUILD to PolicyValue.Bool(true))))
            it.createRegion(region(deniedRegion, areaTwo, mapOf(FlagCatalog.BUILD to PolicyValue.Bool(false))))
            it.createRegion(region(allowedRegion, areaTwo, mapOf(FlagCatalog.BUILD to PolicyValue.Bool(true))))
        }
        val base = ActionRequest(
            actor = ActionActor.Player(visitor),
            actionKey = Action.REGION_BUILD.key,
            source = ActionEndpoint(Position(world, 1, 64, 1), RegionResource(sourceRegion), areaOne)
        )
        val bulk = BulkActionRequest(base, listOf(
            ActionEndpoint(Position(world, 20, 64, 20), RegionResource(deniedRegion), areaTwo),
            ActionEndpoint(Position(world, 21, 64, 20), RegionResource(allowedRegion), areaTwo)
        ))

        val result = PolicyGuard(policy(regions, RegionRole.VISITOR), regions).checkBulk(bulk)

        assertEquals(2, result.decisions.size)
        assertEquals(1, result.deniedCount)
        assertEquals(1, result.allowedCount)
        assertEquals(bulk.correlationId, result.decisions.first().context.actionRequest?.correlationId)
        assertEquals(RegionResource(allowedRegion), result.decisions.last().context.actionRequest?.target?.resource)
    }

    @Test
    fun `only known target requiring actions reject a missing target`() {
        val builtIn = ActionRequest(ActionActor.Player(visitor), Action.REGION_BUILD.key)
        val noTargetAction = ActionRequest(ActionActor.System(), ActionKey("addon:server_reload"))

        assertNotNull(builtIn.validationError())
        assertNull(noTargetAction.validationError())
    }

    @Test
    fun `legacy plot member roles translate to the canonical region roles`() {
        assertEquals(RegionRole.OWNER, PlotMemberRole.OWNER.toRegionRole())
        assertEquals(RegionRole.TRUSTED, PlotMemberRole.TRUSTED.toRegionRole())
        assertEquals(RegionRole.MEMBER, PlotMemberRole.MEMBER.toRegionRole())
        assertTrue(PlotMemberRole.MEMBER.toRegionRole().allowsInheritedAction())
        assertEquals(RegionRole.VISITOR, PlotMemberRole.GUEST.toRegionRole())
        assertEquals(RegionRole.BANNED, PlotMemberRole.DENIED.toRegionRole())
    }

    @Test
    fun `guard maps typed requests into existing decision engine and retains scope`() {
        val regionId = RegionId(UUID.randomUUID())
        val region = region(regionId, areaOne, flags = emptyMap())
        val regions = InMemoryRegionStore().also { it.createRegion(region) }
        val policy = policy(regions, role = RegionRole.TRUSTED)
        val request = ActionRequest(
            actor = ActionActor.Player(visitor),
            actionKey = Action.REGION_BUILD.key,
            target = ActionEndpoint(Position(world, 5, 64, 5), areaId = areaOne)
        )

        val decision = PolicyGuard(policy, regions).check(request)

        assertEquals(DecisionEffect.ALLOW, decision.effect)
        assertEquals(Action.REGION_BUILD.key, decision.context.actionKey)
        assertEquals(areaOne, decision.context.actionRequest?.areaId)
        assertEquals(regionId, (decision.context.actionRequest?.target?.resource as RegionResource).regionId)
    }

    @Test
    fun `component target remains the target resource while policy resolves its parent region`() {
        val regionId = RegionId(UUID.randomUUID())
        val componentId = ComponentId(UUID.randomUUID())
        val regions = InMemoryRegionStore().also { it.createRegion(region(regionId, areaOne, emptyMap())) }
        val request = ActionRequest(
            actor = ActionActor.Player(owner),
            actionKey = Action.COMPONENT_USE.key,
            target = ActionEndpoint(
                Position(world, 5, 64, 5),
                ActionResourceRef.Component(componentId),
                areaOne
            ),
            regionId = regionId
        )

        val decision = PolicyGuard(policy(regions, role = RegionRole.OWNER), regions).check(request)
        val resolved = decision.context.actionRequest

        assertEquals(regionId, decision.context.regionId)
        assertEquals(ActionResourceRef.Component(componentId), resolved?.target?.resource)
        assertEquals(areaOne, resolved?.target?.areaId)
    }

    @Test
    fun `audit uses scope enriched by the central action decision`() {
        val regionId = RegionId(UUID.randomUUID())
        val regions = InMemoryRegionStore().also { it.createRegion(region(regionId, areaOne, emptyMap())) }
        val request = ActionRequest(
            actor = ActionActor.Player(owner),
            actionKey = Action.REGION_BUILD.key,
            target = ActionEndpoint(Position(world, 5, 64, 5), areaId = areaOne)
        )
        val decision = PolicyGuard(policy(regions, role = RegionRole.OWNER), regions).check(request)

        val audit = AuditEntries.actionResult(request, decision, AuditStatus.COMPLETED)

        assertEquals(areaOne, audit.areaId)
        assertEquals(ActionResourceRef.Region(regionId), audit.target?.resource)
        assertEquals(regionId.value, audit.targetId)
    }

    @Test
    fun `guard preserves locked denial and explicit bypass in decision trace`() {
        val regionId = RegionId(UUID.randomUUID())
        val regions = InMemoryRegionStore().also {
            it.createRegion(region(regionId, areaOne, flags = mapOf(FlagCatalog.BUILD to PolicyValue.Bool(false))))
        }
        val grantProvider = CapabilityPermissionProvider { _, permission, _ ->
            if (permission == PolicyPermissions.LOCKED_POLICY_BYPASS) PermissionResult.GRANTED
            else PermissionResult.UNDEFINED
        }
        val zone = systems.diath.homeclaim.core.model.Zone(
            id = systems.diath.homeclaim.core.model.ZoneId(UUID.randomUUID()),
            world = "action-test",
            shape = RegionShape.CUBOID,
            bounds = Bounds(0, 10, 0, 100, 0, 10),
            priority = 1,
            defaults = systems.diath.homeclaim.core.model.ZoneDefaults(
                defaultFlags = mapOf(FlagCatalog.BUILD to PolicyValue.Bool(false))
            ),
            lockedFlags = setOf(FlagCatalog.BUILD),
            areaId = areaOne
        )
        val zones = InMemoryZoneStore().also { it.createZone(zone) }
        val service = SimplePolicyService(
            regions, zones, { _, _ -> RegionRole.OWNER }, permissionProvider = grantProvider
        )
        val request = ActionRequest(
            ActionActor.Player(owner), Action.REGION_BUILD.key,
            target = ActionEndpoint(Position(world, 5, 64, 5), RegionResource(regionId), areaOne)
        )

        val decision = PolicyGuard(service, regions).check(request)

        assertEquals(DecisionEffect.ALLOW, decision.effect)
        assertEquals(DecisionReason.BYPASS_LOCKED_POLICY, decision.reason)
        assertEquals(PolicyPermissions.LOCKED_POLICY_BYPASS, decision.trace.bypass)
        assertEquals(PermissionResult.GRANTED, decision.trace.permissionResult)
    }

    @Test
    fun `missing area is not applicable and never converted into denial`() {
        val regions = InMemoryRegionStore()
        val guard = PolicyGuard(policy(regions, RegionRole.VISITOR), regions, InMemoryWorldAreaService())
        val decision = guard.check(
            ActionRequest(ActionActor.Player(visitor), Action.REGION_BUILD.key,
                target = ActionEndpoint(Position(world, 500, 64, 500)))
        )

        assertEquals(DecisionEffect.NOT_APPLICABLE, decision.effect)
        assertEquals(DecisionReason.NOT_APPLICABLE, decision.reason)
    }

    @Test
    fun `same coordinates in different area resources resolve different policies`() {
        val deniedRegion = RegionId(UUID.randomUUID())
        val allowedRegion = RegionId(UUID.randomUUID())
        val regions = InMemoryRegionStore().also {
            it.createRegion(region(deniedRegion, areaOne, mapOf(FlagCatalog.BUILD to PolicyValue.Bool(false))))
            it.createRegion(region(allowedRegion, areaTwo, mapOf(FlagCatalog.BUILD to PolicyValue.Bool(true))))
        }
        val guard = PolicyGuard(policy(regions, RegionRole.VISITOR), regions)
        val position = Position(world, 5, 64, 5)

        val denied = guard.check(ActionRequest(ActionActor.Player(visitor), Action.REGION_BUILD.key,
            target = ActionEndpoint(position, RegionResource(deniedRegion), areaOne)))
        val allowed = guard.check(ActionRequest(ActionActor.Player(visitor), Action.REGION_BUILD.key,
            target = ActionEndpoint(position, RegionResource(allowedRegion), areaTwo)))

        assertEquals(DecisionEffect.DENY, denied.effect)
        assertEquals(DecisionEffect.ALLOW, allowed.effect)
        assertEquals(deniedRegion, denied.context.regionId)
        assertEquals(allowedRegion, allowed.context.regionId)
        assertEquals(areaOne, denied.context.areaId)
        assertEquals(areaTwo, allowed.context.areaId)
    }

    @Test
    fun `permission grant applies to inherited rule but does not override explicit deny`() {
        val regionId = RegionId(UUID.randomUUID())
        val regions = InMemoryRegionStore().also { it.createRegion(region(regionId, areaOne, emptyMap())) }
        val provider = CapabilityPermissionProvider { _, permission, _ ->
            if (permission == "homeclaim.action.region_build") PermissionResult.GRANTED else PermissionResult.UNDEFINED
        }
        val policy = SimplePolicyService(regions, InMemoryZoneStore(), { _, _ -> RegionRole.VISITOR }, permissionProvider = provider)
        val request = ActionRequest(ActionActor.Player(visitor), Action.REGION_BUILD.key,
            target = ActionEndpoint(Position(world, 5, 64, 5), RegionResource(regionId), areaOne))

        val decision = PolicyGuard(policy, regions).check(request)

        assertEquals(DecisionEffect.ALLOW, decision.effect)
        assertEquals(DecisionSource.PERMISSION, decision.source)
        assertEquals(PermissionResult.GRANTED, decision.trace.permissionResult)
    }

    @Test
    fun `audit records lifecycle correlation and excludes routine allowed movement`() {
        val request = ActionRequest(ActionActor.Player(visitor), Action.REGION_BUILD.key,
            target = ActionEndpoint(Position(world, 5, 64, 5), RegionResource(RegionId(UUID.randomUUID())), areaOne))
        val decision = Decision(
            effect = DecisionEffect.DENY,
            reason = DecisionReason.FLAG_DENY,
            context = DecisionContext(visitor, RegionId(UUID.randomUUID()), Action.REGION_BUILD, world,
                areaId = areaOne, actionRequest = request),
            source = DecisionSource.POLICY,
            trace = DecisionTrace(role = "VISITOR", policyFlag = "BUILD", permission = "homeclaim.action.region_build",
                permissionResult = PermissionResult.UNDEFINED)
        )
        val failed = AuditEntries.actionResult(request, decision, AuditStatus.FAILED, "mutation backend failed")
        val denied = AuditEntries.actionResult(request, decision, AuditStatus.DENIED)
        val movement = AuditEntry(
            actorId = visitor,
            targetId = null,
            category = "REGION",
            action = "ENTER",
            actionKey = Action.REGION_ENTER.key,
            policyResult = DecisionEffect.ALLOW,
            status = AuditStatus.ALLOWED
        )

        assertEquals(request.correlationId, failed.correlationId)
        assertEquals(AuditStatus.FAILED, failed.status)
        assertEquals("mutation backend failed", failed.error)
        assertEquals(DecisionEffect.DENY, denied.policyResult)
        assertEquals(PermissionResult.UNDEFINED, denied.permissionResult)
        assertTrue(AuditPolicy.shouldPersist(denied))
        assertFalse(AuditPolicy.shouldPersist(movement))
    }

    @Test
    fun `audit action result supports each lifecycle state`() {
        val request = ActionRequest(ActionActor.Player(visitor), Action.REGION_BUILD.key,
            target = ActionEndpoint(Position(world, 5, 64, 5), RegionResource(RegionId(UUID.randomUUID())), areaOne))
        val decision = Decision(
            effect = DecisionEffect.ALLOW,
            reason = DecisionReason.ALLOWED,
            context = DecisionContext(visitor, RegionId(UUID.randomUUID()), Action.REGION_BUILD, world, areaId = areaOne),
            source = DecisionSource.ROLE,
            trace = DecisionTrace(role = "MEMBER")
        )

        val records = AuditStatus.entries.map { status ->
            AuditEntries.actionResult(request, decision, status,
                error = if (status == AuditStatus.FAILED) "operation failed" else null)
        }

        assertEquals(AuditStatus.entries, records.map { it.status })
        assertTrue(records.all { it.correlationId == request.correlationId })
        assertEquals("operation failed", records.single { it.status == AuditStatus.FAILED }.error)
    }

    @Test
    fun `administrative operation action keys have structured audit lifecycle hooks`() {
        val regionId = RegionId(UUID.randomUUID())
        val correlation = CorrelationId(UUID.randomUUID())
        val request = ActionRequest(
            actor = ActionActor.Player(owner),
            actionKey = HomeClaimOperationActions.REGION_CLAIM,
            target = ActionEndpoint(Position(world, 5, 64, 5), RegionResource(regionId), areaOne),
            correlationId = correlation
        )

        val audit = AuditEntries.operationResult(request, AuditStatus.COMPLETED, targetCount = 1)

        assertEquals("homeclaim:region_claim", audit.actionKey?.value)
        assertEquals(correlation, audit.correlationId)
        assertEquals(owner, audit.actorId)
        assertEquals(regionId.value, audit.targetId)
        assertEquals(areaOne, audit.areaId)
        assertEquals(1, audit.targetCount)
        assertTrue(AuditPolicy.shouldPersist(audit))
        assertTrue(AuditPolicy.shouldPersist(AuditEntries.operationResult(request, AuditStatus.ALLOWED)))
    }

    @Test
    fun `child requests can share correlation while unrelated requests get separate identities`() {
        val parent = CorrelationId(UUID.randomUUID())
        val first = ActionRequest(ActionActor.Player(visitor), Action.REGION_BUILD.key,
            target = ActionEndpoint(Position(world, 1, 64, 1)), correlationId = parent)
        val child = first.copy(target = ActionEndpoint(Position(world, 2, 64, 2)), parentCorrelationId = parent)
        val unrelated = ActionRequest(ActionActor.Player(visitor), Action.REGION_BUILD.key,
            target = ActionEndpoint(Position(world, 3, 64, 3)))

        assertEquals(first.correlationId, child.correlationId)
        assertEquals(parent, child.parentCorrelationId)
        assertTrue(first.correlationId != unrelated.correlationId)
    }

    @Test
    fun `bulk request evaluates every target and creates one correlated audit record`() {
        val deniedRegion = RegionId(UUID.randomUUID())
        val allowedRegion = RegionId(UUID.randomUUID())
        val regions = InMemoryRegionStore().also {
            it.createRegion(region(deniedRegion, areaOne, mapOf(FlagCatalog.BUILD to PolicyValue.Bool(false))))
            it.createRegion(region(allowedRegion, areaTwo, mapOf(FlagCatalog.BUILD to PolicyValue.Bool(true))))
        }
        val correlation = CorrelationId(UUID.randomUUID())
        val request = ActionRequest(ActionActor.Player(visitor), Action.REGION_BUILD.key,
            correlationId = correlation)
        val bulk = BulkActionRequest(request, listOf(
            ActionEndpoint(Position(world, 5, 64, 5), RegionResource(deniedRegion), areaOne),
            ActionEndpoint(Position(world, 5, 64, 5), RegionResource(allowedRegion), areaTwo)
        ))

        val result = PolicyGuard(policy(regions, RegionRole.VISITOR), regions).checkBulk(bulk)
        val audit = AuditEntries.bulkActionResult(bulk, result)

        assertEquals(2, result.targetCount)
        assertEquals(1, result.deniedCount)
        assertEquals(1, result.allowedCount)
        assertFalse(result.allowed)
        assertEquals(correlation, audit?.correlationId)
        assertEquals(2, audit?.targetCount)
        assertEquals(AuditStatus.DENIED, audit?.status)
        assertTrue(AuditPolicy.shouldPersist(audit!!))
    }

    @Test
    fun `allowed explosion audit records one authorized bulk event rather than claiming completion`() {
        val regionId = RegionId(UUID.randomUUID())
        val regions = InMemoryRegionStore().also {
            it.createRegion(
                region(regionId, areaOne, mapOf(FlagCatalog.EXPLOSION_DAMAGE to PolicyValue.Bool(true))),
                Bounds(0, 10, 0, 100, 0, 10)
            )
        }
        val position = Position(world, 5, 64, 5)
        val endpoint = ActionEndpoint(position, RegionResource(regionId), areaOne)
        val correlation = CorrelationId.newId()
        val bulk = BulkActionRequest(
            action = ActionRequest(
                actor = ActionActor.System("minecraft:explosion"),
                actionKey = Action.REGION_EXPLOSION.key,
                source = endpoint,
                context = ActionContext(modPolicyDefaults = ModPolicyDefaults(allowServerTasks = true)),
                correlationId = correlation
            ),
            targets = listOf(endpoint)
        )

        val result = PolicyGuard(policy(regions, RegionRole.OWNER), regions).checkBulk(bulk)
        val audit = AuditEntries.bulkActionResult(bulk, result)

        assertEquals(0, result.deniedCount)
        assertEquals(AuditStatus.ALLOWED, audit?.status)
        assertEquals(correlation, audit?.correlationId)
        assertEquals(1, audit?.targetCount)
        assertTrue(AuditPolicy.shouldPersist(audit!!))
    }

    private fun policy(regions: InMemoryRegionStore, role: RegionRole) =
        SimplePolicyService(regions, InMemoryZoneStore(), { _, _ -> role })

    private fun region(id: RegionId, area: AreaId, flags: Map<systems.diath.homeclaim.core.model.FlagKey, systems.diath.homeclaim.core.model.FlagValue>) =
        Region(
            id = id,
            world = "action-test",
            shape = RegionShape.CUBOID,
            bounds = Bounds(0, 10, 0, 100, 0, 10),
            owner = owner,
            roles = RegionRoles(),
            flags = flags,
            areaId = area
        )

    private fun assertFailsForInvalidKey(value: String) {
        assertTrue(runCatching { ActionKey(value) }.isFailure)
    }

}
