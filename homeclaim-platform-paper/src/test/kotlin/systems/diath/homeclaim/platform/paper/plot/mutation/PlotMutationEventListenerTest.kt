package systems.diath.homeclaim.platform.paper.plot.mutation

import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import systems.diath.homeclaim.core.event.PostRegionUpdateEvent
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionRoles
import systems.diath.homeclaim.core.model.RegionShape
import systems.diath.homeclaim.core.mutation.MutationReason
import systems.diath.homeclaim.core.service.RegionService
import java.util.UUID

class PlotMutationEventListenerTest {

    @Test
    fun `unclaim update queues reset without duplicate repaint when reset was accepted`() {
        val regionService = mock(RegionService::class.java)
        val mutationService = mock(PlotMutationService::class.java)
        val resetService = mock(PlotResetService::class.java)
        val listener = PlotMutationEventListener(regionService, mutationService, resetService)

        val regionId = RegionId(UUID.randomUUID())
        val region = Region(
            id = regionId,
            world = "plots",
            shape = RegionShape.CUBOID,
            bounds = Bounds(0, 15, 0, 319, 0, 15),
            owner = PlotVisualStates.UNCLAIMED_OWNER,
            roles = RegionRoles(),
            flags = emptyMap(),
            limits = emptyMap(),
            metadata = emptyMap(),
            mergeGroupId = null,
            price = 0.0
        )
        val event = PostRegionUpdateEvent(regionId, UUID.randomUUID(), mapOf("owner" to "changed"))

        doReturn(region).`when`(regionService).getRegionById(regionId)
        doReturn(true).`when`(resetService).queueReset(region, PlotResetReason.UNCLAIM)

        listener.onPostRegionUpdateEvent(event)

        verify(resetService, times(1)).queueReset(region, PlotResetReason.UNCLAIM)
        verify(mutationService, never()).applyRegionState(region, MutationReason.ADMIN)
        verify(mutationService, never()).applyRegionState(region, MutationReason.UNCLAIM)
    }
}
