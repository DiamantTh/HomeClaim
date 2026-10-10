package systems.diath.homeclaim.platform.paper.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionShape
import java.util.UUID

class OwnedRegionSelectorTest {
    private val lowId = region("00000000-0000-0000-0000-000000000001")
    private val highId = region("00000000-0000-0000-0000-000000000002")

    @Test
    fun `one owned plot is selected without a selector`() {
        assertEquals(OwnedRegionSelection.Selected(lowId), OwnedRegionSelector.select(listOf(lowId), null))
    }

    @Test
    fun `multiple plots require an explicit selector in stable id order`() {
        assertEquals(
            OwnedRegionSelection.SelectionRequired(listOf(lowId, highId)),
            OwnedRegionSelector.select(listOf(highId, lowId), null)
        )
    }

    @Test
    fun `one based number selects the stable list position`() {
        assertEquals(OwnedRegionSelection.Selected(highId), OwnedRegionSelector.select(listOf(highId, lowId), "2"))
    }

    @Test
    fun `full region id selects a matching plot independent of collection order`() {
        assertEquals(
            OwnedRegionSelection.Selected(highId),
            OwnedRegionSelector.select(listOf(highId, lowId), highId.id.value.toString())
        )
    }

    @Test
    fun `unknown and out of range selectors are rejected`() {
        assertEquals(OwnedRegionSelection.InvalidSelector, OwnedRegionSelector.select(listOf(lowId), "2"))
        assertEquals(OwnedRegionSelection.InvalidSelector, OwnedRegionSelector.select(listOf(lowId), UUID.randomUUID().toString()))
    }

    private fun region(id: String) = Region(
        id = RegionId(UUID.fromString(id)),
        world = "plots",
        shape = RegionShape.CUBOID,
        bounds = Bounds(0, 15, 0, 255, 0, 15),
        owner = UUID.randomUUID()
    )
}
