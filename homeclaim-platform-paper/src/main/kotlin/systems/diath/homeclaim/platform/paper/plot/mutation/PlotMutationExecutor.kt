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
        val materialCache = mutableMapOf<String, Material>()
        
        for (operation in batch.operations) {
            val material = materialCache.getOrPut(operation.blockStateId) {
                resolveMaterial(operation.blockStateId, batch.id, operation.x, operation.y, operation.z)
            }
            
            world.getBlockAt(operation.x, operation.y, operation.z).setType(material, false)
        }
    }

    internal fun resolveMaterial(stateId: String, batchId: String, x: Int, y: Int, z: Int): Material =
        Material.matchMaterial(stateId)
            ?: throw IllegalArgumentException("Unknown block state '$stateId' in mutation batch '$batchId' at $x,$y,$z")
}
