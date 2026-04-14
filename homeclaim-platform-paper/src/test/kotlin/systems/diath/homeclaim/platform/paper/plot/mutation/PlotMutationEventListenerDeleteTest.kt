package systems.diath.homeclaim.platform.paper.plot.mutation

import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import systems.diath.homeclaim.core.event.PostRegionDeleteEvent
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionRoles
import systems.diath.homeclaim.core.model.RegionShape
import systems.diath.homeclaim.core.service.RegionService
import java.util.UUID

class PlotMutationEventListenerDeleteTest {

    @Test
    fun `delete event skips extra repaint when reset was accepted`() {
        val regionService = mock(RegionService::class.java)
        val mutationService = mock(PlotMutationService::class.java)
        val resetService = mock(PlotResetService::class.java)
        val listener = PlotMutationEventListener(regionService, mutationService, resetService)

        val region = Region(
            id = RegionId(UUID.randomUUID()),
            world = "plots",
            shape = RegionShape.CUBOID,
            bounds = Bounds(0, 15, 0, 319, 0, 15),
            owner = UUID.randomUUID(),
            roles = RegionRoles(),
            flags = emptyMap(),
            limits = emptyMap(),
            metadata = emptyMap(),
            mergeGroupId = null,
            price = 0.0
        )
        val event = PostRegionDeleteEvent(region.id, UUID.randomUUID(), "plots", region)

        doReturn(true).`when`(resetService).queueReset(region, PlotResetReason.DELETE)

        listener.onPostRegionDeleteEvent(event)

        verify(resetService, times(1)).queueReset(region, PlotResetReason.DELETE)
        verifyNoInteractions(mutationService)
    }
}
