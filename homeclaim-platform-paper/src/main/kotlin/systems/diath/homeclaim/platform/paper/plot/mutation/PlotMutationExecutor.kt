package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.Material
import org.bukkit.World
import systems.diath.homeclaim.core.mutation.MutationBatch

internal object PlotMutationExecutor {
    /**
     * Apply all block mutations from a batch.
     * Optimize by pre-caching material lookups to avoid repeated parsing for large batches.
     */
    fun apply(world: World, batch: MutationBatch) {
        // Pre-cache all unique materials to avoid repeated lookups
        val materialCache = mutableMapOf<String, Material?>()
        
        for (operation in batch.operations) {
            val material = materialCache.getOrPut(operation.blockStateId) {
                Material.matchMaterial(operation.blockStateId)
            }
            
            if (material != null) {
                world.getBlockAt(operation.x, operation.y, operation.z).setType(material, false)
            } else {
                // Silent ignore unknown material; batch continues
                // (Could log on first occurrence of unknown material)
            }
        }
    }
}