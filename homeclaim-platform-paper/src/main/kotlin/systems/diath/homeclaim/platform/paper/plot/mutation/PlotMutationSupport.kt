package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.Material
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.platform.paper.plot.PlotWorldConfig

internal data class PlotBorderStyle(
    val fillMaterial: Material,
    val capMaterial: Material
)

internal object PlotMutationSupport {
    fun styleFor(region: Region, config: PlotWorldConfig, state: PlotVisualState): PlotBorderStyle {
        val explicitCap = region.metadata["plot.visual.border.material"]
            ?.let(Material::matchMaterial)
            ?.takeIf { it != Material.AIR }

        val capMaterial = explicitCap ?: when (state) {
            PlotVisualState.UNCLAIMED -> config.unclaimedBorderBlock
            PlotVisualState.CLAIMED -> config.claimedBorderBlock
            PlotVisualState.MERGED -> config.mergedBorderBlock
            PlotVisualState.SALE -> config.saleBorderBlock
            PlotVisualState.ADMIN -> config.adminBorderBlock
            PlotVisualState.RESERVED -> config.reservedBorderBlock
        }

        return PlotBorderStyle(
            fillMaterial = config.wallBlock,
            capMaterial = capMaterial
        )
    }

    fun repaintColumns(
        world: org.bukkit.World,
        columns: Collection<Pair<Int, Int>>,
        config: PlotWorldConfig,
        style: PlotBorderStyle
    ) {
        if (columns.isEmpty()) return
        val minY = config.minGenHeight ?: -64
        val topY = (config.plotHeight - 1).coerceAtLeast(minY)

        for ((x, z) in columns) {
            for (y in minY until topY) {
                world.getBlockAt(x, y, z).setType(style.fillMaterial, false)
            }
            world.getBlockAt(x, topY, z).setType(style.capMaterial, false)
        }
    }
}
