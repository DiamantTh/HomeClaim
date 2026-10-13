package systems.diath.homeclaim.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import systems.diath.homeclaim.core.model.*
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.service.*
import systems.diath.homeclaim.core.store.InMemoryRegionStore
import java.util.UUID

class RegionBiomeServiceTest {
    @Test fun `biome plan includes only the logical merge group and lifecycle completes once`() {
        val owner = UUID.randomUUID(); val area = AreaId(UUID.randomUUID()); val group = MergeGroupId(UUID.randomUUID())
        val first = region(owner, area, Bounds(0, 15, 0, 255, 0, 15), group)
        val second = region(owner, area, Bounds(16, 31, 0, 255, 0, 15), group)
        val other = region(owner, area, Bounds(32, 47, 0, 255, 0, 15), null)
        val store = InMemoryRegionStore(); listOf(first, second, other).forEach(store::createRegion)
        val audit = CapturingAudit()
        val service = RegionBiomeService(store, audit, RegionMutationAuthorizer { _, _, _ -> true })
        val prepared = service.prepare(RegionMutationContext(ActionActor.Player(owner), "test", "biome"), first.id, "minecraft:plains")
        assertTrue(prepared.succeeded); assertEquals(setOf(first.id, second.id), requireNotNull(prepared.value).regions.map { it.id }.toSet())
        service.completed(requireNotNull(prepared.value))
        assertEquals(listOf(AuditStatus.ATTEMPTED, AuditStatus.COMPLETED), audit.entries.map { it.status })
    }

    @Test fun `unclaimed biome change is denied`() {
        val region = region(RegionOwnership.UNCLAIMED_OWNER_ID, AreaId(UUID.randomUUID()), Bounds(0, 15, 0, 255, 0, 15), null)
        val store = InMemoryRegionStore(); store.createRegion(region)
        val result = RegionBiomeService(store, authorizer = RegionMutationAuthorizer { _, _, _ -> true })
            .prepare(RegionMutationContext(ActionActor.Player(UUID.randomUUID()), "test", "biome"), region.id, "minecraft:plains")
        assertEquals(OperationStatus.DENIED, result.status)
    }

    private fun region(owner: UUID, area: AreaId, bounds: Bounds, group: MergeGroupId?) = Region(
        RegionId(UUID.randomUUID()), "plots", RegionShape.CUBOID, bounds, owner, mergeGroupId = group, areaId = area
    )
    private class CapturingAudit : AuditService { val entries = mutableListOf<AuditEntry>(); override fun append(entry: AuditEntry) { entries += entry } }
}
