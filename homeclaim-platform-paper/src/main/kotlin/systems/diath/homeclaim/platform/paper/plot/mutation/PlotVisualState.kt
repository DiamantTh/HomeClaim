package systems.diath.homeclaim.platform.paper.plot.mutation

import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionOwnership

/**
 * Visual state for a plot/region. This is deliberately separate from ownership
 * and policy metadata so Paper world mutations can evolve independently.
 */
enum class PlotVisualState {
    UNCLAIMED,
    CLAIMED,
    MERGED,
    SALE,
    ADMIN,
    RESERVED
}

object PlotVisualStates {
    fun resolve(region: Region): PlotVisualState {
        val explicitState = region.metadata["plot.visual.state"]?.trim()?.uppercase()
        if (explicitState != null) {
            return runCatching { PlotVisualState.valueOf(explicitState) }.getOrNull() ?: inferred(region)
        }
        return inferred(region)
    }

    private fun inferred(region: Region): PlotVisualState {
        return when {
            region.metadata["plot.admin"] == "true" -> PlotVisualState.ADMIN
            region.metadata["plot.reserved"] == "true" -> PlotVisualState.RESERVED
            RegionOwnership.isUnclaimed(region.owner) -> PlotVisualState.UNCLAIMED
            region.price > 0.0 -> PlotVisualState.SALE
            region.mergeGroupId != null -> PlotVisualState.MERGED
            else -> PlotVisualState.CLAIMED
        }
    }
}
