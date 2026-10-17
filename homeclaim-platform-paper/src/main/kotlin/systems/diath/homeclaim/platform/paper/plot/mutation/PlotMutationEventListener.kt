package systems.diath.homeclaim.platform.paper.plot.mutation

import systems.diath.homeclaim.core.event.EventListener
import systems.diath.homeclaim.core.mutation.MutationReason
import systems.diath.homeclaim.core.event.PostRegionBuyEvent
import systems.diath.homeclaim.core.event.PostRegionClaimEvent
import systems.diath.homeclaim.core.event.PostRegionDeleteEvent
import systems.diath.homeclaim.core.event.PostRegionUpdateEvent
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.core.model.Region

/**
 * Bridges core region lifecycle events to the platform-specific plot mutation layer.
 */
class PlotMutationEventListener(
    private val regionService: RegionService,
    private val plotMutationService: PlotMutationService,
    private val plotResetService: PlotResetService = NoOpPlotResetService,
    private val deferReset: ((Region, PlotResetReason, (PlotResetCompletion) -> Unit) -> Boolean)? = null
) : EventListener {

    fun onPostRegionClaimEvent(event: PostRegionClaimEvent) {
        regionService.getRegionById(event.regionId)?.let { plotMutationService.applyRegionState(it, MutationReason.CLAIM) }
    }

    fun onPostRegionBuyEvent(event: PostRegionBuyEvent) {
        regionService.getRegionById(event.regionId)?.let { plotMutationService.applyRegionState(it, MutationReason.CLAIM) }
    }

    fun onPostRegionUpdateEvent(event: PostRegionUpdateEvent) {
        if (event.changes.isEmpty()) return
        val region = regionService.getRegionById(event.regionId) ?: return

        if ("owner" in event.changes && PlotVisualStates.resolve(region) == PlotVisualState.UNCLAIMED) {
            queueResetOrFallback(region, PlotResetReason.UNCLAIM) {
                plotMutationService.applyRegionState(region, MutationReason.UNCLAIM)
            }
            return
        }

        plotMutationService.applyRegionState(region, MutationReason.ADMIN)
    }

    fun onPostRegionDeleteEvent(event: PostRegionDeleteEvent) {
        queueResetOrFallback(event.regionSnapshot, PlotResetReason.DELETE) {
            plotMutationService.handleRegionDeleted(event.regionSnapshot, MutationReason.UNCLAIM)
        }
    }

    private fun queueResetOrFallback(region: Region, reason: PlotResetReason, fallback: () -> Unit) {
        val deferredReset = deferReset
        if (deferredReset == null) {
            if (!plotResetService.queueReset(region, reason)) fallback()
            return
        }
        val completed: (PlotResetCompletion) -> Unit = { result ->
            if (result.status != PlotResetCompletionStatus.COMPLETED && !result.partialMutation && result.completedColumns == 0) fallback()
        }
        val queued = deferredReset.invoke(region, reason, completed)
        if (!queued) fallback()
    }

}
