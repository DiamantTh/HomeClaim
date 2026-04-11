package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldUnloadEvent
import java.util.logging.Logger

/**
 * Cancels pending plot jobs when a world unloads so no queued work continues to
 * target a disappearing world on Paper or Folia.
 */
internal class PlotJobWorldListener(
    private val logger: Logger,
    private val plotMutationService: PlotMutationService,
    private val plotResetService: PlotResetService
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onWorldUnload(event: WorldUnloadEvent) {
        val worldName = event.world.name
        val diagnostics = plotMutationService.activeJobDiagnostics(worldName) +
            plotResetService.activeJobDiagnostics(worldName)
        val cancelled = plotMutationService.cancelPendingJobs(worldName) +
            plotResetService.cancelPendingJobs(worldName)

        if (cancelled > 0 || diagnostics.isNotEmpty()) {
            logger.info("World unload for $worldName cancelled $cancelled pending plot job(s)")
            diagnostics.take(10).forEach { detail ->
                logger.info("PlotJob[$worldName]: $detail")
            }
        }
    }
}
