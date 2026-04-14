package systems.diath.homeclaim.platform.paper.plot

import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class PlotWorldChunkGeneratorTest {

    @Test
    fun `generator separates road wall and plot columns for even road widths`() {
        val config = PlotWorldConfig(
            worldName = "plots",
            plotSize = 16,
            roadWidth = 4,
            plotHeight = 4,
            minGenHeight = 0,
            plotBlock = Material.GRASS_BLOCK,
            roadBlock = Material.STONE,
            wallBlock = Material.DIAMOND_BLOCK
        )
        val generator = PlotWorldChunkGenerator(config)

        assertEquals(Material.STONE, generator.getBlockAt(0, 1, 2))
        assertEquals(Material.DIAMOND_BLOCK, generator.getBlockAt(1, 1, 2))
        assertEquals(Material.GRASS_BLOCK, generator.getBlockAt(2, 1, 2))
        assertEquals(Material.DIAMOND_BLOCK, generator.getBlockAt(18, 1, 2))
        assertEquals(Material.STONE, generator.getBlockAt(19, 1, 2))
    }

    @Test
    fun `generator returns bedrock and air outside configured plot area`() {
        val config = PlotWorldConfig(
            worldName = "plots",
            plotSize = 4,
            roadWidth = 4,
            plotHeight = 4,
            minGenHeight = 0,
            plotsPerSide = 2
        )
        val generator = PlotWorldChunkGenerator(config)

        assertEquals(Material.BEDROCK, generator.getBlockAt(-100, 0, 0))
        assertEquals(Material.AIR, generator.getBlockAt(-100, 1, 0))
        assertNotEquals(Material.AIR, generator.getBlockAt(0, 1, 0))
    }

    @Test
    fun `plot area bounds stay consistent for odd plot counts`() {
        val config = PlotWorldConfig(
            worldName = "plots",
            plotSize = 4,
            roadWidth = 4,
            plotsPerSide = 3
        )

        assertEquals(-8, config.plotAreaMinCoordinate())
        assertEquals(16, config.plotAreaMaxExclusive())
        assertEquals(4.0, config.plotAreaCenterCoordinate(), 0.0001)
    }
}
