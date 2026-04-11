package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.World
import org.bukkit.event.world.WorldUnloadEvent
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.util.logging.Logger

class PlotJobWorldListenerTest {

    @Test
    fun `cancels mutation and reset jobs for the unloading world`() {
        val logger = mock(Logger::class.java)
        val mutationService = mock(PlotMutationService::class.java)
        val resetService = mock(PlotResetService::class.java)
        val world = mock(World::class.java)
        val event = mock(WorldUnloadEvent::class.java)

        doReturn("plots").`when`(world).name
        doReturn(world).`when`(event).world
        doReturn(listOf("mutation:plots:job-1:age=42ms:cancelled=false")).`when`(mutationService).activeJobDiagnostics("plots")
        doReturn(listOf("reset:plots:job-2:age=7ms:cancelled=false")).`when`(resetService).activeJobDiagnostics("plots")
        doReturn(1).`when`(mutationService).cancelPendingJobs("plots")
        doReturn(1).`when`(resetService).cancelPendingJobs("plots")

        val listener = PlotJobWorldListener(logger, mutationService, resetService)
        listener.onWorldUnload(event)

        verify(mutationService, times(1)).activeJobDiagnostics("plots")
        verify(resetService, times(1)).activeJobDiagnostics("plots")
        verify(mutationService, times(1)).cancelPendingJobs("plots")
        verify(resetService, times(1)).cancelPendingJobs("plots")
        verify(logger, times(3)).info(org.mockito.ArgumentMatchers.anyString())
    }
}
