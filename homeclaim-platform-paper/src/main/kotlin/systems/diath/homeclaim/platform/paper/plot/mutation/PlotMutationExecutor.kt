package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.Material
import org.bukkit.World
import systems.diath.homeclaim.core.mutation.MutationBatch

internal object PlotMutationExecutor {
    fun apply(world: World, batch: MutationBatch) {
        for (operation in batch.operations) {
            val material = requireNotNull(Material.matchMaterial(operation.blockStateId)) {
                "Unknown material in mutation batch ${batch.id}: ${operation.blockStateId}"
            }
            world.getBlockAt(operation.x, operation.y, operation.z).setType(material, false)
        }
    }
}