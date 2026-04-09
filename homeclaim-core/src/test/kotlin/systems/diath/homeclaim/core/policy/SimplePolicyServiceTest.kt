package systems.diath.homeclaim.core.policy

import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionRole
import systems.diath.homeclaim.core.model.RegionShape
import systems.diath.homeclaim.core.model.RegionRoles
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.model.Zone
import systems.diath.homeclaim.core.model.ZoneDefaults
import systems.diath.homeclaim.core.store.InMemoryRegionStore
import systems.diath.homeclaim.core.store.InMemoryZoneStore
import systems.diath.homeclaim.core.model.ComponentId
import systems.diath.homeclaim.core.policy.FlagProfile
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimplePolicyServiceTest {
    private val owner: PlayerId = UUID.randomUUID()
    private val trusted: PlayerId = UUID.randomUUID()
    private val visitor: PlayerId = UUID.randomUUID()
    private val regionId = RegionId(UUID.randomUUID())
    private val world: WorldId = "world"
    private val region = Region(
        id = regionId,
        world = world,
        shape = RegionShape.CUBOID,
        bounds = Bounds(0, 10, 0, 10, 0, 10),
        owner = owner,
        roles = RegionRoles(trusted = setOf(trusted)),
        flags = mapOf(FlagCatalog.BUILD to systems.diath.homeclaim.core.model.PolicyValue.Bool(false))
    )

    @Test
    fun `owner bypasses flags`() {
        val regionStore = InMemoryRegionStore().also { it.createRegion(region, region.bounds) }
        val zoneStore = InMemoryZoneStore()
        val service = SimplePolicyService(regionStore, zoneStore, roleResolver = { _, _ -> RegionRole.OWNER })

        val decision = service.evaluate(owner, Action.REGION_BUILD, Position(world, 5, 5, 5))
        assertTrue(decision.allowed)
    }

    @Test
    fun `visitor denied when flag false`() {
        val regionStore = InMemoryRegionStore().also { it.createRegion(region, region.bounds) }
        val zoneStore = InMemoryZoneStore()
        val service = SimplePolicyService(regionStore, zoneStore, roleResolver = { _, player ->
            when (player) {
                owner -> RegionRole.OWNER
                trusted -> RegionRole.TRUSTED
                else -> RegionRole.VISITOR
            }
        })

        val decision = service.evaluate(visitor, Action.REGION_BUILD, Position(world, 5, 5, 5))
        assertFalse(decision.allowed)
        assertEquals(DecisionReason.FLAG_DENY, decision.reason)
    }

    @Test
    fun `zone defaults apply when region has no override`() {
        val regionStore = InMemoryRegionStore().also { it.createRegion(region.copy(flags = emptyMap()), region.bounds) }
        val zoneStore = InMemoryZoneStore().also {
            it.createZone(
                Zone(
                    id = systems.diath.homeclaim.core.model.ZoneId(UUID.randomUUID()),
                    world = world,
                    shape = RegionShape.CUBOID,
                    bounds = Bounds(0, 20, 0, 20, 0, 20),
                    priority = 10,
                    defaults = ZoneDefaults(
                        defaultFlags = mapOf(FlagCatalog.BUILD to systems.diath.homeclaim.core.model.PolicyValue.Bool(false))
                    )
                )
            )
        }

        val service = SimplePolicyService(regionStore, zoneStore, roleResolver = { _, _ -> RegionRole.VISITOR })
        val decision = service.evaluate(visitor, Action.REGION_BUILD, Position(world, 5, 5, 5))
        assertFalse(decision.allowed)
        assertEquals(DecisionReason.FLAG_DENY, decision.reason)
    }

    @Test
    fun `locked zone flag prevents region override`() {
        val regionStore = InMemoryRegionStore().also {
            it.createRegion(
                region.copy(flags = mapOf(FlagCatalog.BUILD to systems.diath.homeclaim.core.model.PolicyValue.Bool(true))),
                region.bounds
            )
        }
        val zoneStore = InMemoryZoneStore().also {
            it.createZone(
                Zone(
                    id = systems.diath.homeclaim.core.model.ZoneId(UUID.randomUUID()),
                    world = world,
                    shape = RegionShape.CUBOID,
                    bounds = Bounds(0, 20, 0, 20, 0, 20),
                    priority = 10,
                    defaults = ZoneDefaults(
                        defaultFlags = mapOf(FlagCatalog.BUILD to systems.diath.homeclaim.core.model.PolicyValue.Bool(false))
                    ),
                    lockedFlags = setOf(FlagCatalog.BUILD)
                )
            )
        }
        val service = SimplePolicyService(regionStore, zoneStore, roleResolver = { _, _ -> RegionRole.VISITOR })
        val decision = service.evaluate(visitor, Action.REGION_BUILD, Position(world, 5, 5, 5))
        assertFalse(decision.allowed)
    }

    @Test
    fun `component use respects cooldown limit`() {
        val regionWithLimit = region.copy(
            limits = mapOf(LimitCatalog.COMPONENT_COOLDOWN_MS to systems.diath.homeclaim.core.model.PolicyValue.IntValue(1000)),
            flags = mapOf(FlagCatalog.COMPONENT_USE to systems.diath.homeclaim.core.model.PolicyValue.Bool(true))
        )
        val regionStore = InMemoryRegionStore().also { it.createRegion(regionWithLimit, regionWithLimit.bounds) }
        val zoneStore = InMemoryZoneStore()
        val service = SimplePolicyService(regionStore, zoneStore, roleResolver = { _, _ -> RegionRole.VISITOR })
        val componentId = ComponentId(UUID.randomUUID())

        val first = service.evaluate(visitor, Action.COMPONENT_USE, Position(world, 5, 5, 5), extraContext = mapOf("componentId" to componentId, "regionId" to regionId))
        val second = service.evaluate(visitor, Action.COMPONENT_USE, Position(world, 5, 5, 5), extraContext = mapOf("componentId" to componentId, "regionId" to regionId))

        assertTrue(first.allowed)
        assertFalse(second.allowed)
        assertEquals(DecisionReason.COOLDOWN_ACTIVE, second.reason)
        assertTrue(second.detail?.contains("wait_ms=") == true)
    }

    @Test
    fun `flag profile can deny build`() {
        val profile = FlagProfile(
            name = "deny-build",
            flags = mapOf(FlagCatalog.BUILD to systems.diath.homeclaim.core.model.PolicyValue.Bool(false))
        )
        val regionStore = InMemoryRegionStore().also { it.createRegion(region.copy(flags = emptyMap()), region.bounds) }
        val zoneStore = InMemoryZoneStore()
        val service = SimplePolicyService(regionStore, zoneStore, roleResolver = { _, _ -> RegionRole.VISITOR })

        val decision = service.evaluate(visitor, Action.REGION_BUILD, Position(world, 5, 5, 5), extraContext = mapOf("flagProfile" to profile, "regionId" to regionId))
        assertFalse(decision.allowed)
        assertEquals(DecisionReason.FLAG_DENY, decision.reason)
    }
}
