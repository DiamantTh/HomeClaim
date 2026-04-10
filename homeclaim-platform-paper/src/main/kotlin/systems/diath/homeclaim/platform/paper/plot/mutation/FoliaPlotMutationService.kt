package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.plugin.java.JavaPlugin
import systems.diath.homeclaim.core.model.Region

/**
 * Intentionally conservative first cut for Folia.
 *
 * Paper-style global border repainting stays out of the Folia path until a
 * dedicated region-aware mutation planner is introduced.
 */
class FoliaPlotMutationService(
    private val plugin: JavaPlugin
) : PlotMutationService {
    override fun applyRegionState(region: Region) {
        plugin.logger.fine("Skipping Paper-style plot mutation for Folia region ${region.id.value}")
    }
}
