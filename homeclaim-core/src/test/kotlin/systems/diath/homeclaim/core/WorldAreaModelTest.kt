package systems.diath.homeclaim.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import systems.diath.homeclaim.core.model.Area
import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.AreaResolver
import systems.diath.homeclaim.core.model.AreaScope
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.HomeClaimWorld
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionShape
import systems.diath.homeclaim.core.model.Zone
import systems.diath.homeclaim.core.model.ZoneId
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.store.InMemoryRegionStore
import systems.diath.homeclaim.core.store.InMemoryZoneStore
import systems.diath.homeclaim.core.service.InMemoryWorldAreaService
import java.util.UUID

class WorldAreaModelTest {
    @Test
    fun `domain identifiers are UUID backed and independent from mutable names`() {
        val id = WorldId.newId()
        val world = HomeClaimWorld(id = id, name = "plots")
        val renamed = world.renamed("main")
        val platformId = UUID.randomUUID()

        assertEquals(id, renamed.id)
        assertNotEquals(WorldId.fromLegacyName("plots"), WorldId.fromLegacyName("main"))
        assertEquals(platformId, world.bindPlatformWorld(platformId).bindPlatformWorld(platformId).platformWorldId)
        assertThrows(IllegalArgumentException::class.java) {
            world.bindPlatformWorld(platformId).bindPlatformWorld(UUID.randomUUID())
        }
        assertNotEquals(AreaId.newId(), AreaId.newId())
    }

    @Test
    fun `finite area resolves only positions inside its explicit bounds`() {
        val worldId = WorldId.newId()
        val area = Area(
            AreaId.newId(), worldId, "plots",
            AreaScope.Finite(Bounds(-10, 10, 0, 100, -10, 10)), priority = 1
        )
        val resolver = AreaResolver(listOf(area))

        assertSame(area, resolver.resolveArea(worldId, 0, 64, 0))
        assertNull(resolver.resolveArea(worldId, 11, 64, 0))
        assertNull(resolver.resolveArea(WorldId.newId(), 0, 64, 0))
    }

    @Test
    fun `higher priority wins and equal priority overlaps are rejected`() {
        val worldId = WorldId.newId()
        val broad = Area(AreaId.newId(), worldId, "farm", AreaScope.WholeWorld, priority = 0)
        val plots = Area(
            AreaId.newId(), worldId, "plots",
            AreaScope.Finite(Bounds(0, 99, -64, 319, 0, 99)), priority = 5
        )
        val resolver = AreaResolver(listOf(broad, plots))

        assertEquals(plots.id, resolver.resolveArea(worldId, 10, 64, 10)?.id)
        assertEquals(broad.id, resolver.resolveArea(worldId, 120, 64, 10)?.id)
        assertThrows(IllegalArgumentException::class.java) {
            AreaResolver(listOf(broad, broad.copy(id = AreaId.newId(), key = "same-priority")))
        }
    }

    @Test
    fun `service supports multiple areas and no match remains no match`() {
        val service = InMemoryWorldAreaService()
        val world = service.createWorld("main", UUID.randomUUID())
        val plotArea = service.createArea(
            Area(AreaId.newId(), world.id, "plots", AreaScope.Finite(Bounds(0, 99, -64, 319, 0, 99)), 10)
        )
        val farmArea = service.createArea(
            Area(AreaId.newId(), world.id, "farm", AreaScope.Finite(Bounds(100, 199, -64, 319, 0, 99)), 10)
        )

        assertEquals(plotArea.id, service.resolveArea(world.id, 50, 64, 50)?.id)
        assertEquals(farmArea.id, service.resolveArea(world.id, 150, 64, 50)?.id)
        assertNull(service.resolveArea(world.id, 250, 64, 50))
        assertEquals(world.id, service.getWorldByPlatformId(world.platformWorldId!!)!!.id)
    }

    @Test
    fun `legacy default identity is stable and defaults to whole world coverage`() {
        val service = InMemoryWorldAreaService()
        val first = service.ensureLegacyArea("old-plots")
        val second = service.ensureLegacyArea("old-plots")

        assertEquals(first.id, second.id)
        assertEquals(first.worldId, second.worldId)
        assertTrue(first.scope is AreaScope.WholeWorld)
        assertTrue(first.contains(-30_000_000, -64, 30_000_000))
    }

    @Test
    fun `regions and zones with equal coordinates remain scoped to their area and world`() {
        val worlds = InMemoryWorldAreaService()
        val worldA = worlds.createWorld("a")
        val worldB = worlds.createWorld("b")
        val areaA = worlds.createArea(Area(
            AreaId.newId(), worldA.id, "plots", AreaScope.WholeWorld
        )).id
        val areaB = worlds.createArea(Area(
            AreaId.newId(), worldB.id, "plots", AreaScope.WholeWorld
        )).id
        val regionA = Region(
            id = RegionId(UUID.randomUUID()), world = "a", areaId = areaA,
            shape = RegionShape.CUBOID, bounds = Bounds(0, 10, 0, 100, 0, 10), owner = UUID.randomUUID()
        )
        val regionB = Region(
            id = RegionId(UUID.randomUUID()), world = "b", areaId = areaB,
            shape = RegionShape.CUBOID, bounds = regionA.bounds, owner = UUID.randomUUID()
        )
        val regions = InMemoryRegionStore().also {
            it.createRegion(regionA, regionA.bounds)
            it.createRegion(regionB, regionB.bounds)
        }

        assertEquals(regionA.id, regions.getRegionAt(areaA, 5, 50, 5))
        assertEquals(regionB.id, regions.getRegionAt(areaB, 5, 50, 5))
        assertNull(regions.getRegionAt(AreaId.newId(), 5, 50, 5))
        assertNotEquals(worldA.id, worldB.id)

        val zoneA = Zone(
            id = ZoneId(UUID.randomUUID()), world = "a", areaId = areaA,
            shape = RegionShape.CUBOID, bounds = regionA.bounds, priority = 1
        )
        val zones = InMemoryZoneStore().also { it.createZone(zoneA) }
        assertEquals(listOf(zoneA), zones.listZones(areaA))
        assertTrue(zones.listZones(areaB).isEmpty())
    }
}
