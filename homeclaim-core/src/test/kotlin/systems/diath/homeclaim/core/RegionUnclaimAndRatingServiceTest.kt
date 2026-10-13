package systems.diath.homeclaim.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import systems.diath.homeclaim.core.model.*
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.CorrelationId
import systems.diath.homeclaim.core.service.*
import systems.diath.homeclaim.core.store.InMemoryRegionStore
import systems.diath.homeclaim.core.store.RatingRepository
import java.time.Instant
import java.util.UUID

class RegionUnclaimAndRatingServiceTest {
    private val owner = UUID.randomUUID()
    private val area = AreaId(UUID.randomUUID())

    private fun region(group: MergeGroupId? = null) = Region(
        RegionId(UUID.randomUUID()), "plots", RegionShape.CUBOID, Bounds(0, 15, 0, 255, 0, 15), owner,
        roles = RegionRoles(trusted = setOf(UUID.randomUUID()), members = setOf(UUID.randomUUID()), banned = setOf(UUID.randomUUID())),
        metadata = mapOf("alias" to "owner label", "description" to "owner text", "preserve" to "yes"),
        home = RegionHome(4.5, 70.0, 4.5, 0f, 0f), mergeGroupId = group, price = 25.0, areaId = area
    )

    @Test fun `unclaim clears owner-authored state and emits one lifecycle`() {
        val store = InMemoryRegionStore(); val audit = CapturingAudit(); val original = region(); store.createRegion(original); audit.entries.clear()
        val service = RegionUnclaimService(store, audit, RegionMutationAuthorizer { _, _, _ -> true })
        val result = service.unclaim(RegionMutationContext(ActionActor.Player(owner), "test", "delete"), original.id)
        assertTrue(result.succeeded)
        val released = requireNotNull(store.getRegionById(original.id))
        assertTrue(RegionOwnership.isUnclaimed(released.owner)); assertEquals(RegionRoles(), released.roles)
        assertNull(released.home); assertEquals(0.0, released.price)
        assertEquals(mapOf("preserve" to "yes"), released.metadata)
        assertEquals(listOf(AuditStatus.ATTEMPTED, AuditStatus.COMPLETED), audit.entries.map { it.status })
    }

    @Test fun `merged region cannot be unclaimed until unlink`() {
        val store = InMemoryRegionStore(); val original = region(MergeGroupId(UUID.randomUUID())); store.createRegion(original)
        val result = RegionUnclaimService(store, authorizer = RegionMutationAuthorizer { _, _, _ -> true })
            .unclaim(RegionMutationContext(ActionActor.Player(owner), "test", "delete"), original.id)
        assertEquals(OperationStatus.CONFLICT, result.status)
        assertFalse(RegionOwnership.isUnclaimed(requireNotNull(store.getRegionById(original.id)).owner))
    }

    @Test fun `rating creates then updates one row and protects owner and merged regions`() {
        val store = InMemoryRegionStore(); val original = region(); store.createRegion(original)
        val ratings = MemoryRatings(); val actor = UUID.randomUUID()
        val service = RegionRatingService(store, ratings)
        val context = RegionMutationContext(ActionActor.Player(actor), "test", "rate", CorrelationId.newId())
        assertEquals("CREATED", service.rate(context, original.id, 4).detail)
        assertEquals("UPDATED", service.rate(context, original.id, 5).detail)
        assertEquals(1, ratings.getPlotRatings(original.id).size)
        assertEquals(5, ratings.getStats(original.id).averageRating.toInt())
        assertEquals(OperationStatus.DENIED, service.rate(context.copy(actor = ActionActor.Player(owner)), original.id, 4).status)
        assertEquals(OperationStatus.INVALID, service.rate(context, original.id, 0).status)
        store.updateRegion(original.copy(mergeGroupId = MergeGroupId(UUID.randomUUID())))
        assertEquals(OperationStatus.CONFLICT, service.rate(context, original.id, 5).status)
    }

    private class CapturingAudit : AuditService { val entries = mutableListOf<AuditEntry>(); override fun append(entry: AuditEntry) { entries += entry } }
    private class MemoryRatings : RatingRepository {
        private val values = linkedMapOf<Pair<RegionId, UUID>, PlotRating>()
        override fun saveRating(rating: PlotRating): PlotRating { values[rating.regionId to rating.raterId] = rating.copy(updatedAt = Instant.now()); return values.getValue(rating.regionId to rating.raterId) }
        override fun getRating(regionId: RegionId, raterId: UUID) = values[regionId to raterId]
        override fun getPlotRatings(regionId: RegionId) = values.values.filter { it.regionId == regionId }
        override fun getStats(regionId: RegionId): PlotRatingStats { val all = getPlotRatings(regionId); return PlotRatingStats(regionId, all.size, all.map { it.rating }.average().takeIf { !it.isNaN() } ?: 0.0, all.groupingBy { it.rating }.eachCount()) }
        override fun deleteRating(id: UUID) = values.entries.removeIf { it.value.id == id }
    }
}
