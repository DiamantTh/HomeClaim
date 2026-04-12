package systems.diath.homeclaim.platform.paper.plot.mutation

import systems.diath.homeclaim.core.event.EventListener
import systems.diath.homeclaim.core.mutation.MutationReason
import systems.diath.homeclaim.core.event.PostRegionBuyEvent
import systems.diath.homeclaim.core.event.PostRegionClaimEvent
import systems.diath.homeclaim.core.event.PostRegionDeleteEvent
import systems.diath.homeclaim.core.event.PostRegionMergeEvent
import systems.diath.homeclaim.core.event.PostRegionUpdateEvent
import systems.diath.homeclaim.core.service.RegionService

/**
 * Bridges core region lifecycle events to the platform-specific plot mutation layer.
 */
class PlotMutationEventListener(
    private val regionService: RegionService,
    private val plotMutationService: PlotMutationService,
    private val plotResetService: PlotResetService = NoOpPlotResetService
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
        plotMutationService.applyRegionState(region, MutationReason.ADMIN)
        if ("owner" in event.changes && PlotVisualStates.resolve(region) == PlotVisualState.UNCLAIMED) {
            plotResetService.queueReset(region, PlotResetReason.UNCLAIM)
        }
    }

    fun onPostRegionDeleteEvent(event: PostRegionDeleteEvent) {
        plotMutationService.handleRegionDeleted(event.regionSnapshot, MutationReason.UNCLAIM)
        plotResetService.queueReset(event.regionSnapshot, PlotResetReason.DELETE)
    }

    fun onPostRegionMergeEvent(event: PostRegionMergeEvent) {
        val regions = event.regionIds.mapNotNull(regionService::getRegionById)
        plotMutationService.handleRegionsMerged(regions, MutationReason.MERGE)
    }
}
