package systems.diath.homeclaim.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.MergeGroupId
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionHome
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionShape
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.service.AuditEntry
import systems.diath.homeclaim.core.service.AuditService
import systems.diath.homeclaim.core.service.AuditStatus
import systems.diath.homeclaim.core.service.RegionHomeService
import systems.diath.homeclaim.core.service.RegionMutationAuthorizer
import systems.diath.homeclaim.core.service.RegionMutationContext
import systems.diath.homeclaim.core.service.OperationStatus
import systems.diath.homeclaim.core.store.InMemoryRegionStore
import java.util.UUID

class RegionHomeServiceTest {
    private val owner = UUID.randomUUID()
    private val area = AreaId(UUID.randomUUID())
    private fun region(bounds: Bounds = Bounds(0, 15, 0, 255, 0, 15), group: MergeGroupId? = null) = Region(
        RegionId(UUID.randomUUID()), "plots", RegionShape.CUBOID, bounds, owner, areaId = area, mergeGroupId = group
    )

    @Test fun `owner can save typed home and audit its lifecycle`() {
        val store = InMemoryRegionStore()
        val audit = CapturingAudit()
        val region = region(); store.createRegion(region)
        val service = RegionHomeService(store, audit, RegionMutationAuthorizer { _, _, _ -> true })
        val result = service.setHome(RegionMutationContext(ActionActor.Player(owner), "test", "sethome"), region.id,
            RegionHome(4.5, 70.0, 5.5, 90f, 10f))
        assertTrue(result.succeeded)
        assertEquals(4.5, store.getRegionById(region.id)?.home?.x)
        assertEquals(90f, store.getRegionById(region.id)?.home?.yaw)
        assertEquals(listOf(AuditStatus.ATTEMPTED, AuditStatus.COMPLETED), audit.entries.map { it.status })
        assertEquals(1, audit.entries.map { it.correlationId }.distinct().size)
    }

    @Test fun `home outside logical plot is rejected`() {
        val store = InMemoryRegionStore(); val region = region(); store.createRegion(region)
        val service = RegionHomeService(store, null, RegionMutationAuthorizer { _, _, _ -> true })
        val result = service.setHome(RegionMutationContext(ActionActor.Player(owner), "test", "sethome"), region.id,
            RegionHome(99.0, 70.0, 99.0, 0f, 0f))
        assertEquals(OperationStatus.INVALID, result.status)
        assertNull(store.getRegionById(region.id)?.home)
    }

    @Test fun `merged member permits home and unlink clears an out of bounds anchor`() {
        val store = InMemoryRegionStore(); val group = MergeGroupId(UUID.randomUUID())
        val first = region(Bounds(0, 15, 0, 255, 0, 15), group)
        val second = region(Bounds(16, 31, 0, 255, 0, 15), group)
        store.createRegion(first); store.createRegion(second)
        val service = RegionHomeService(store, null, RegionMutationAuthorizer { _, _, _ -> true })
        assertTrue(service.setHome(RegionMutationContext(ActionActor.Player(owner), "test", "sethome"), first.id,
            RegionHome(20.0, 70.0, 5.0, 0f, 0f)).succeeded)
        assertEquals(20.0, service.resolveHome(first)?.x)
        store.updateRegion(first.copy(mergeGroupId = null, home = store.getRegionById(first.id)?.home))
        store.updateRegion(second.copy(mergeGroupId = null))
        service.reconcileUnlinked(listOf(first, second))
        assertNull(store.getRegionById(first.id)?.home)
    }

    private class CapturingAudit : AuditService {
        val entries = mutableListOf<AuditEntry>()
        override fun append(entry: AuditEntry) { entries += entry }
    }
}
