package systems.diath.homeclaim.platform.paper.plot.mutation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.MergeGroupId
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionShape
import systems.diath.homeclaim.core.model.WorldId
import java.util.UUID

class PlotTransferPlanTest {
    private val areaId = AreaId(UUID.randomUUID())
    private val worldId = WorldId(UUID.randomUUID())
    private val paperWorldId = UUID.randomUUID()
    private val owner = UUID.randomUUID()

    @Test
    fun `copy plan accepts equal bounds dimensions without transferring metadata`() {
        val source = region(Bounds(0, 15, 0, 255, 0, 15))
        val target = region(Bounds(32, 47, 0, 255, 32, 47))

        val result = PlotTransferPlanner.plan(
            PlotTransferOperation.COPY, source, target, worldId, worldId, paperWorldId, paperWorldId
        )

        val plan = assertInstanceOf(PlotTransferPlanResult.Ready::class.java, result).plan
        assertEquals(listOf(source.id, target.id).sortedBy { it.value.toString() }, plan.regionIds)
        assertEquals(65_536L, plan.blockCount)
        assertEquals("source-alias", source.metadata["alias"])
    }

    @Test
    fun `planner rejects same region dimensions world area merges and overlap`() {
        val base = region(Bounds(0, 15, 0, 255, 0, 15))
        fun rejection(source: Region, target: Region, sourceWorld: WorldId? = worldId, targetWorld: WorldId? = worldId,
                      sourcePaper: UUID? = paperWorldId, targetPaper: UUID? = paperWorldId) =
            assertInstanceOf(PlotTransferPlanResult.Rejected::class.java,
                PlotTransferPlanner.plan(PlotTransferOperation.MOVE, source, target, sourceWorld, targetWorld, sourcePaper, targetPaper)).reason

        assertEquals(PlotTransferRejection.SAME_REGION, rejection(base, base))
        assertEquals(PlotTransferRejection.DIMENSION_MISMATCH,
            rejection(base, region(Bounds(32, 48, 0, 255, 32, 47))))
        assertEquals(PlotTransferRejection.WORLD_MISMATCH,
            rejection(base, region(Bounds(32, 47, 0, 255, 32, 47)), targetWorld = WorldId(UUID.randomUUID())))
        assertEquals(PlotTransferRejection.WORLD_BINDING_MISMATCH,
            rejection(base, region(Bounds(32, 47, 0, 255, 32, 47)), targetPaper = UUID.randomUUID()))
        assertEquals(PlotTransferRejection.AREA_MISMATCH,
            rejection(base, region(Bounds(32, 47, 0, 255, 32, 47), area = AreaId(UUID.randomUUID()))))
        assertEquals(PlotTransferRejection.MERGED_REGION_UNSUPPORTED,
            rejection(base.copy(mergeGroupId = MergeGroupId(UUID.randomUUID())), region(Bounds(32, 47, 0, 255, 32, 47))))
        assertEquals(PlotTransferRejection.COMPONENTS_UNSUPPORTED,
            assertInstanceOf(PlotTransferPlanResult.Rejected::class.java, PlotTransferPlanner.plan(
                PlotTransferOperation.COPY, base, region(Bounds(32, 47, 0, 255, 32, 47)),
                worldId, worldId, paperWorldId, paperWorldId, sourceHasComponents = true
            )).reason)
        assertEquals(PlotTransferRejection.OVERLAPPING_BOUNDS,
            rejection(base, region(Bounds(8, 23, 0, 255, 8, 23))))
        assertEquals(PlotTransferRejection.TARGET_UNCLAIMED,
            rejection(base, region(Bounds(32, 47, 0, 255, 32, 47), owner = systems.diath.homeclaim.core.model.RegionOwnership.UNCLAIMED_OWNER_ID)))
    }

    @Test
    fun `transfer journal details retain identities and backup roles across phases`() {
        val source = UUID.randomUUID()
        val target = UUID.randomUUID()
        val sourceBackup = UUID.randomUUID()
        val targetBackup = UUID.randomUUID()
        val detail = PlotTransferJournalDetails(
            PlotTransferOperation.SWAP, source, target, sourceBackup, targetBackup, "APPLYING_SECONDARY",
            areaId, worldId, paperWorldId, "PLAYER", owner.toString(), sourceChanged = false, targetChanged = true
        ).encode()

        assertTrue(detail.contains("operation=SWAP"))
        assertTrue(detail.contains("sourceRegion=$source"))
        assertTrue(detail.contains("targetRegion=$target"))
        assertTrue(detail.contains("sourceBackup=$sourceBackup"))
        assertTrue(detail.contains("targetSafetyBackup=$targetBackup"))
        assertTrue(detail.contains("areaId="))
        assertTrue(detail.contains("worldId="))
        assertTrue(detail.contains("actorKind=PLAYER"))
        assertTrue(detail.contains("targetChanged=true"))
        assertTrue(detail.contains("phase=APPLYING_SECONDARY"))
    }

    private fun region(
        bounds: Bounds,
        area: AreaId = areaId,
        owner: UUID = this.owner
    ) = Region(RegionId(UUID.randomUUID()), "display-only", RegionShape.CUBOID, bounds, owner,
        metadata = mapOf("alias" to "source-alias"), areaId = area)
}
