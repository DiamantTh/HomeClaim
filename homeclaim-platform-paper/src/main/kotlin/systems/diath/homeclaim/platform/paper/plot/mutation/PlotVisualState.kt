package systems.diath.homeclaim.platform.paper.plot.mutation

import systems.diath.homeclaim.core.model.Region
import java.util.UUID

/**
 * Visual state for a plot/region. This is deliberately separate from ownership
 * and policy metadata so Paper/Folia world mutations can evolve independently.
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
    val UNCLAIMED_OWNER: UUID = UUID(0L, 0L)

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
            region.owner == UNCLAIMED_OWNER -> PlotVisualState.UNCLAIMED
            region.price > 0.0 -> PlotVisualState.SALE
            region.mergeGroupId != null -> PlotVisualState.MERGED
            else -> PlotVisualState.CLAIMED
        }
    }
}
