package systems.diath.homeclaim.platform.paper.plot.mutation

import systems.diath.homeclaim.core.event.EventListener
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
    private val plotMutationService: PlotMutationService
) : EventListener {

    fun onPostRegionClaimEvent(event: PostRegionClaimEvent) {
        regionService.getRegionById(event.regionId)?.let(plotMutationService::applyRegionState)
    }

    fun onPostRegionBuyEvent(event: PostRegionBuyEvent) {
        regionService.getRegionById(event.regionId)?.let(plotMutationService::applyRegionState)
    }

    fun onPostRegionUpdateEvent(event: PostRegionUpdateEvent) {
        if (event.changes.isEmpty()) return
        regionService.getRegionById(event.regionId)?.let(plotMutationService::applyRegionState)
    }

    fun onPostRegionDeleteEvent(event: PostRegionDeleteEvent) {
        plotMutationService.handleRegionDeleted(event.regionSnapshot)
    }

    fun onPostRegionMergeEvent(event: PostRegionMergeEvent) {
        val regions = event.regionIds.mapNotNull(regionService::getRegionById)
        plotMutationService.handleRegionsMerged(regions)
    }
}
