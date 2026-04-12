package systems.diath.homeclaim.platform.paper.plot.mutation

import systems.diath.homeclaim.core.mutation.MutationJobInfo
import systems.diath.homeclaim.core.mutation.MutationReason
import systems.diath.homeclaim.core.model.Region

/**
 * Platform-specific world mutation hook for plot lifecycle changes.
 *
 * Core services remain data-driven; Paper/Folia decide independently how to
 * visualize those state changes in the world.
 */
interface PlotMutationService {
    fun applyRegionState(region: Region, reason: MutationReason = MutationReason.ADMIN)

    fun handleRegionDeleted(region: Region, reason: MutationReason = MutationReason.UNCLAIM) {
        applyRegionState(region, reason)
    }

    fun handleRegionsMerged(regions: Collection<Region>, reason: MutationReason = MutationReason.MERGE) {
        regions.forEach { applyRegionState(it, reason) }
    }

    fun handleRegionsUnlinked(regions: Collection<Region>, createRoads: Boolean = true, reason: MutationReason = MutationReason.UNCLAIM) {
        regions.forEach { applyRegionState(it, reason) }
    }

    fun cancelPendingJobs(worldName: String? = null): Int = 0

    fun activeJobDiagnostics(worldName: String? = null): List<String> = emptyList()

    fun activeJobs(worldName: String? = null): List<PlotJobSnapshot> = emptyList()

    fun activeJobInfo(worldName: String? = null): List<MutationJobInfo> = emptyList()
}

object NoOpPlotMutationService : PlotMutationService {
    override fun applyRegionState(region: Region, reason: MutationReason) = Unit
}

data class PlotJobSnapshot(
    val key: String,
    val world: String,
    val kind: String,
    val reason: MutationReason? = null,
    val ageMillis: Long,
    val cancelRequested: Boolean
)
