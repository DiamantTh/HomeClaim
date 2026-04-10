package systems.diath.homeclaim.platform.paper.plot

import org.bukkit.Material

private const val DEFAULT_PLOT_SIZE = 48
private const val DEFAULT_ROAD_WIDTH = 10
private const val DEFAULT_PLOT_HEIGHT = 64
private const val DEFAULT_PLOTS_PER_SIDE = 128

/**
 * Plot World Schema (1.21+)
 * plotBlock: Bodenblock für Plots (oben)
 * roadBlock: Block für Straßen/Ebenen
 * wallBlock: Block für Ränder/Mauern (Beton)
 * accentBlock: Akzentblock für Details (P2 Templates)
 */
data class PlotWorldConfig(
    val worldName: String,
    val plotSize: Int = DEFAULT_PLOT_SIZE,
    val roadWidth: Int = DEFAULT_ROAD_WIDTH,
    val plotHeight: Int = DEFAULT_PLOT_HEIGHT,
    val plotBlock: Material = Material.GRASS_BLOCK,              // Standard: Gras (oben)
    val roadBlock: Material = Material.DARK_PRISMARINE,          // Standard: Dunkles Prismarinblock (Straße)
    val wallBlock: Material = Material.DIAMOND_BLOCK,            // Standard: Diamantblock (Plotrand)
    val accentBlock: Material? = Material.SMOOTH_QUARTZ_STAIRS,  // Standard: Glatte Quarztreppe (Straßenrand)
    val unclaimedBorderBlock: Material = wallBlock,
    val claimedBorderBlock: Material = accentBlock ?: wallBlock,
    val mergedBorderBlock: Material = accentBlock ?: wallBlock,
    val saleBorderBlock: Material = Material.GOLD_BLOCK,
    val adminBorderBlock: Material = Material.EMERALD_BLOCK,
    val reservedBorderBlock: Material = Material.REDSTONE_BLOCK,
    val resetOnDelete: Boolean = false,
    val resetOnUnclaim: Boolean = false,
    val resetBatchColumnsPerTick: Int = 128,
    val plotsPerSide: Int = DEFAULT_PLOTS_PER_SIDE,
    val minGenHeight: Int? = null,
    val schema: String = "default"                               // "default" | "copper" | "deepslate" (P2)
) {
    fun gridSize(): Int = plotSize + roadWidth
}

/**
 * Vordefinierte Plot-Schematiken (P2 Feature - Template-Dateien)
 * Diese Templates können vom Setup-Wizard verwendet werden
 */
object PlotSchemas {
    // Standard: Gras (oben), Dunkles Prismarinblock (Straße), Diamantblock (Plotrand), Glatte Quarztreppe (Straßenrand)
    fun default(): PlotWorldConfig = PlotWorldConfig(
        worldName = "",
        plotBlock = Material.GRASS_BLOCK,
        roadBlock = Material.DARK_PRISMARINE,
        wallBlock = Material.DIAMOND_BLOCK,
        accentBlock = Material.SMOOTH_QUARTZ_STAIRS,
        schema = "default"
    )

    fun recommended(foliaMode: Boolean): PlotWorldConfig {
        return if (foliaMode) {
            default().copy(
                plotSize = 40,
                roadWidth = 8,
                plotHeight = 64,
                plotsPerSide = 96,
                resetBatchColumnsPerTick = 48,
                schema = "default"
            )
        } else {
            default().copy(
                plotSize = DEFAULT_PLOT_SIZE,
                roadWidth = DEFAULT_ROAD_WIDTH,
                plotHeight = DEFAULT_PLOT_HEIGHT,
                plotsPerSide = DEFAULT_PLOTS_PER_SIDE,
                schema = "default"
            )
        }
    }
    
    // Desert: Sand, Sandstone Straße, Orange Terracotta Rand
    fun desert(): PlotWorldConfig = PlotWorldConfig(
        worldName = "",
        plotBlock = Material.SAND,
        roadBlock = Material.SANDSTONE,
        wallBlock = Material.ORANGE_TERRACOTTA,
        accentBlock = Material.SMOOTH_SANDSTONE_STAIRS,
        schema = "desert"
    )
    
    // Snowy: Snowblock, Packed Ice Straße, Blue Ice Rand
    fun snowy(): PlotWorldConfig = PlotWorldConfig(
        worldName = "",
        plotBlock = Material.SNOW_BLOCK,
        roadBlock = Material.PACKED_ICE,
        wallBlock = Material.BLUE_ICE,
        accentBlock = Material.SNOW,
        schema = "snowy"
    )
    
    // Woodland: Dirt, Oak Wood Straße, Oak Log Rand
    fun woodland(): PlotWorldConfig = PlotWorldConfig(
        worldName = "",
        plotBlock = Material.DIRT,
        roadBlock = Material.OAK_WOOD,
        wallBlock = Material.OAK_LOG,
        accentBlock = Material.OAK_LEAVES,
        schema = "woodland"
    )
    
    // Nether: Netherrack, Nether Bricks Straße, Crimson Planks Rand
    fun nether(): PlotWorldConfig = PlotWorldConfig(
        worldName = "",
        plotBlock = Material.NETHERRACK,
        roadBlock = Material.NETHER_BRICKS,
        wallBlock = Material.CRIMSON_PLANKS,
        accentBlock = Material.CRIMSON_STAIRS,
        schema = "nether"
    )
    
    // P2: Kupfer-Theme
    fun copper(): PlotWorldConfig = PlotWorldConfig(
        worldName = "",
        plotBlock = Material.WAXED_OXIDIZED_COPPER,
        roadBlock = Material.STONE,
        wallBlock = Material.WAXED_COPPER_BLOCK,
        accentBlock = Material.OXIDIZED_COPPER,
        schema = "copper"
    )
    
    // P2: Deepslate-Theme
    fun deepslate(): PlotWorldConfig = PlotWorldConfig(
        worldName = "",
        plotBlock = Material.DEEPSLATE_TILES,
        roadBlock = Material.DEEPSLATE,
        wallBlock = Material.POLISHED_DEEPSLATE,
        accentBlock = Material.DEEPSLATE_BRICK_STAIRS,
        schema = "deepslate"
    )
    
    // P2: Modern (Sculk)
    fun modern(): PlotWorldConfig = PlotWorldConfig(
        worldName = "",
        plotBlock = Material.SCULK,
        roadBlock = Material.DEEPSLATE,
        wallBlock = Material.SCULK_SENSOR,
        accentBlock = Material.SCULK_CATALYST,
        schema = "modern"
    )
}

fun PlotWorldConfig.sanitized(): PlotWorldConfig {
    return copy(
        plotSize = plotSize.coerceAtLeast(4),
        roadWidth = roadWidth.coerceAtLeast(1),
        plotHeight = plotHeight.coerceAtLeast(1),
        plotsPerSide = plotsPerSide.coerceAtLeast(0),
        unclaimedBorderBlock = unclaimedBorderBlock,
        claimedBorderBlock = claimedBorderBlock,
        mergedBorderBlock = mergedBorderBlock,
        saleBorderBlock = saleBorderBlock,
        adminBorderBlock = adminBorderBlock,
        reservedBorderBlock = reservedBorderBlock,
        resetOnDelete = resetOnDelete,
        resetOnUnclaim = resetOnUnclaim,
        resetBatchColumnsPerTick = resetBatchColumnsPerTick.coerceAtLeast(1)
    )
}
