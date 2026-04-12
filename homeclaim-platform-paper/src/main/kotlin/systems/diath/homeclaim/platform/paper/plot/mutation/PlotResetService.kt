package systems.diath.homeclaim.platform.paper.plot.mutation

import systems.diath.homeclaim.core.mutation.MutationJobInfo
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

    fun cancelPendingJobs(worldName: String? = null): Int = 0

    fun activeJobDiagnostics(worldName: String? = null): List<String> = emptyList()

    fun activeJobs(worldName: String? = null): List<PlotJobSnapshot> = emptyList()

    fun activeJobInfo(worldName: String? = null): List<MutationJobInfo> = emptyList()
}

object NoOpPlotResetService : PlotResetService {
    override fun queueReset(region: Region, reason: PlotResetReason): Boolean = false
}
