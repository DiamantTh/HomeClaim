package systems.diath.homeclaim.platform.paper.plot.mutation

import systems.diath.homeclaim.core.model.Region

/**
 * Separate, potentially heavier reset path for restoring a plot's interior.
 * This is intentionally decoupled from the lightweight border mutation service.
 */
enum class PlotResetReason {
    MANUAL,
    DELETE,
    UNCLAIM
}

interface PlotResetService {
    fun queueReset(region: Region, reason: PlotResetReason = PlotResetReason.MANUAL): Boolean
}

object NoOpPlotResetService : PlotResetService {
    override fun queueReset(region: Region, reason: PlotResetReason): Boolean = false
}
