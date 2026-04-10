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
}

object NoOpPlotMutationService : PlotMutationService {
    override fun applyRegionState(region: Region) = Unit
}
