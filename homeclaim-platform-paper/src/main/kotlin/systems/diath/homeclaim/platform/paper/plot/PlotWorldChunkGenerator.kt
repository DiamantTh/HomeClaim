package systems.diath.homeclaim.platform.paper.plot

import org.bukkit.Material
import org.bukkit.generator.ChunkGenerator
import java.lang.Math.floorMod

class PlotWorldChunkGenerator(
    private val config: PlotWorldConfig
) : ChunkGenerator() {

    /**
     * Get the block material at a specific world coordinate.
     * Used by BulkBlockSetter for region conversion.
     */
    fun getBlockAt(worldX: Int, worldY: Int, worldZ: Int): Material {
        val cfg = config.sanitized()
        val minY = cfg.minGenHeight ?: -64
        val plotTop = cfg.plotHeight
        val grid = cfg.gridSize()
        val roadWidth = cfg.roadWidth
        val plotSize = cfg.plotSize

        val pathLower: Int
        val pathUpper: Int
        if (roadWidth == 0) {
            pathLower = -1
            pathUpper = -1
        } else if (roadWidth % 2 == 0) {
            pathLower = (roadWidth / 2) - 1
            pathUpper = pathLower + plotSize + 1
        } else {
            pathLower = roadWidth / 2
            pathUpper = pathLower + plotSize + 1
        }

        // Check if outside plot boundaries (if plotsPerSide is configured)
        if (cfg.plotsPerSide > 0) {
            val minCoord = cfg.plotAreaMinCoordinate()
            val maxCoordExclusive = cfg.plotAreaMaxExclusive()
            if (worldX < minCoord || worldX >= maxCoordExclusive || worldZ < minCoord || worldZ >= maxCoordExclusive) {
                // Outside plot area: return bedrock at bottom, air above
                return if (worldY <= minY) Material.BEDROCK else Material.AIR
            }
        }

        // Below generation height or above plot top = air
        if (worldY < minY || worldY >= plotTop) {
            return Material.AIR
        }

        val modX = floorMod(worldX, grid)
        val modZ = floorMod(worldZ, grid)
        val isRoad = roadWidth != 0 && (modX < pathLower || modX > pathUpper || modZ < pathLower || modZ > pathUpper)
        val isWall = roadWidth != 0 && !isRoad && (modX == pathLower || modX == pathUpper || modZ == pathLower || modZ == pathUpper)

        return when {
            isWall -> cfg.wallBlock
            isRoad -> cfg.roadBlock
            else -> cfg.plotBlock
        }
    }

    override fun generateSurface(
        worldInfo: org.bukkit.generator.WorldInfo,
        random: java.util.Random,
        chunkX: Int,
        chunkZ: Int,
        chunkData: ChunkData
    ) {
        val cfg = config.sanitized()
        val minY = cfg.minGenHeight ?: worldInfo.minHeight
        val plotTop = cfg.plotHeight
        val wallTop = plotTop
        val grid = cfg.gridSize()
        val roadWidth = cfg.roadWidth
        val plotSize = cfg.plotSize

        val pathLower: Int
        val pathUpper: Int
        if (roadWidth == 0) {
            pathLower = -1
            pathUpper = -1
        } else if (roadWidth % 2 == 0) {
            pathLower = (roadWidth / 2) - 1
            pathUpper = pathLower + plotSize + 1
        } else {
            pathLower = roadWidth / 2
            pathUpper = pathLower + plotSize + 1
        }

        val startX = chunkX shl 4
        val startZ = chunkZ shl 4

        // Check if chunk is outside plot boundaries (if plotsPerSide is configured)
        val minCoord = if (cfg.plotsPerSide > 0) cfg.plotAreaMinCoordinate() else Int.MIN_VALUE
        val maxCoordExclusive = if (cfg.plotsPerSide > 0) cfg.plotAreaMaxExclusive() else Int.MAX_VALUE

        for (x in 0 until 16) {
            for (z in 0 until 16) {
                val worldX = startX + x
                val worldZ = startZ + z

                // Check if outside plot area
                val isOutside = worldX < minCoord || worldX >= maxCoordExclusive || worldZ < minCoord || worldZ >= maxCoordExclusive
                
                if (isOutside) {
                    // Outside plot area: just bedrock at bottom
                    if (minY < 0) {
                        chunkData.setBlock(x, minY, z, Material.BEDROCK)
                    }
                    continue
                }

                val modX = floorMod(worldX, grid)
                val modZ = floorMod(worldZ, grid)
                val isRoad = roadWidth != 0 && (modX < pathLower || modX > pathUpper || modZ < pathLower || modZ > pathUpper)
                val isWall = roadWidth != 0 && !isRoad && (modX == pathLower || modX == pathUpper || modZ == pathLower || modZ == pathUpper)

                val baseBlock = if (isRoad) cfg.roadBlock else cfg.plotBlock

                for (y in minY until plotTop) {
                    chunkData.setBlock(x, y, z, baseBlock)
                }

                if (isWall) {
                    for (y in minY until wallTop) {
                        chunkData.setBlock(x, y, z, cfg.wallBlock)
                    }
                }
            }
        }
    }

    override fun shouldGenerateNoise(): Boolean = false
    override fun shouldGenerateSurface(): Boolean = true
    @Deprecated("Required compatibility override for the current Bukkit/Paper generator API")
    override fun shouldGenerateBedrock(): Boolean = false
    override fun shouldGenerateCaves(): Boolean = false
    override fun shouldGenerateDecorations(): Boolean = false
    override fun shouldGenerateMobs(): Boolean = false
    @Deprecated("Required compatibility override for the current Bukkit/Paper generator API")
    override fun shouldGenerateStructures(): Boolean = false
}
