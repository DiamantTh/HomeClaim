package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.Material
import systems.diath.homeclaim.core.mutation.BlockMutationOp
import systems.diath.homeclaim.core.mutation.MutationBatch
import systems.diath.homeclaim.core.mutation.MutationPriority
import systems.diath.homeclaim.core.mutation.MutationReason
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.platform.paper.plot.PlotWorldChunkGenerator
import systems.diath.homeclaim.platform.paper.plot.PlotWorldConfig

internal object PlotMutationPlanFactory {
    fun borderBatch(
        id: String,
        world: String,
        bounds: Bounds,
        config: PlotWorldConfig,
        style: PlotBorderStyle,
        reason: MutationReason,
        includeNorth: Boolean = true,
        includeSouth: Boolean = true,
        includeWest: Boolean = true,
        includeEast: Boolean = true
    ): MutationBatch {
        val columns = PlotBorderPlanner.borderColumns(
            bounds,
            includeNorth = includeNorth,
            includeSouth = includeSouth,
            includeWest = includeWest,
            includeEast = includeEast
        )
        return columnBatch(id, world, columns, config, style, reason)
    }

    fun corridorBatch(
        id: String,
        world: String,
        columns: Collection<Pair<Int, Int>>,
        config: PlotWorldConfig,
        style: PlotBorderStyle,
        reason: MutationReason
    ): MutationBatch {
        return columnBatch(id, world, columns, config, style, reason)
    }

    fun columnBatch(
        id: String,
        world: String,
        columns: Collection<Pair<Int, Int>>,
        config: PlotWorldConfig,
        style: PlotBorderStyle,
        reason: MutationReason
    ): MutationBatch {
        val minY = config.minGenHeight ?: -64
        val topY = (config.plotHeight - 1).coerceAtLeast(minY)
        val operations = ArrayList<BlockMutationOp>(columns.size * ((topY - minY) + 1).coerceAtLeast(1))

        for ((x, z) in columns) {
            for (y in minY until topY) {
                operations += BlockMutationOp(x = x, y = y, z = z, blockStateId = style.fillMaterial.name)
            }
            operations += BlockMutationOp(x = x, y = topY, z = z, blockStateId = style.capMaterial.name)
        }

        return MutationBatch(
            id = id,
            world = world,
            reason = reason,
            priority = MutationPriority.NORMAL,
            allowAsyncPlan = true,
            operations = operations
        )
    }

    fun resetBatch(
        id: String,
        world: String,
        columns: Collection<Pair<Int, Int>>,
        generator: PlotWorldChunkGenerator,
        minY: Int,
        topY: Int,
        clearUntil: Int,
        reason: MutationReason = MutationReason.RESET
    ): MutationBatch {
        val operations = ArrayList<BlockMutationOp>()
        for ((x, z) in columns) {
            for (y in minY..topY) {
                operations += BlockMutationOp(x = x, y = y, z = z, blockStateId = generator.getBlockAt(x, y, z).name)
            }
            for (y in (topY + 1)..clearUntil) {
                operations += BlockMutationOp(x = x, y = y, z = z, blockStateId = Material.AIR.name)
            }
        }

        return MutationBatch(
            id = id,
            world = world,
            reason = reason,
            priority = MutationPriority.NORMAL,
            allowAsyncPlan = true,
            operations = operations
        )
    }
}