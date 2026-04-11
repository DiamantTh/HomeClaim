package systems.diath.homeclaim.platform.paper.plot.mutation

import systems.diath.homeclaim.core.model.Region

/**
 * Platform-specific world mutation hook for plot lifecycle changes.
 *
 * Core services remain data-driven; Paper/Folia decide independently how to
 * visualize those state changes in the world.
 */
interface PlotMutationService {
    fun applyRegionState(region: Region)

    fun handleRegionDeleted(region: Region) {
        applyRegionState(region)
    }

    fun handleRegionsMerged(regions: Collection<Region>) {
        regions.forEach(::applyRegionState)
    }

    fun handleRegionsUnlinked(regions: Collection<Region>, createRoads: Boolean = true) {
        regions.forEach(::applyRegionState)
    }

    fun cancelPendingJobs(worldName: String? = null): Int = 0

    fun activeJobDiagnostics(worldName: String? = null): List<String> = emptyList()
}

object NoOpPlotMutationService : PlotMutationService {
    override fun applyRegionState(region: Region) = Unit
}
