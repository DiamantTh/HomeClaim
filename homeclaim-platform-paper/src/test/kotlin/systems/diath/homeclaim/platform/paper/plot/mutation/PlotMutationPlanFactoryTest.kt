package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import systems.diath.homeclaim.core.mutation.MutationReason
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.platform.paper.plot.PlotWorldChunkGenerator
import systems.diath.homeclaim.platform.paper.plot.PlotWorldConfig

class PlotMutationPlanFactoryTest {

    @Test
    fun `border batch plans block operations with preserved reason`() {
        val config = PlotWorldConfig(
            worldName = "plots",
            plotHeight = 2,
            minGenHeight = 0,
            wallBlock = Material.STONE,
            claimedBorderBlock = Material.GOLD_BLOCK
        )
        val style = PlotBorderStyle(fillMaterial = Material.STONE, capMaterial = Material.GOLD_BLOCK)

        val batch = PlotMutationPlanFactory.borderBatch(
            id = "border-1",
            world = "plots",
            bounds = Bounds(0, 1, 0, 2, 0, 1),
            config = config,
            style = style,
            reason = MutationReason.CLAIM
        )

        assertEquals("border-1", batch.id)
        assertEquals("plots", batch.world)
        assertEquals(MutationReason.CLAIM, batch.reason)
        assertEquals(8, batch.operations.size)
        assertTrue(batch.operations.any { it.blockStateId == Material.STONE.name })
        assertTrue(batch.operations.any { it.blockStateId == Material.GOLD_BLOCK.name })
    }

    @Test
    fun `reset batch includes generated floor and air clear operations`() {
        val config = PlotWorldConfig(
            worldName = "plots",
            plotHeight = 2,
            minGenHeight = 0,
            plotBlock = Material.GRASS_BLOCK
        )
        val generator = PlotWorldChunkGenerator(config)

        val batch = PlotMutationPlanFactory.resetBatch(
            id = "reset-1",
            world = "plots",
            columns = listOf(4 to 5),
            generator = generator,
            minY = 0,
            topY = 1,
            clearUntil = 3
        )

        assertEquals(MutationReason.RESET, batch.reason)
        assertEquals(4, batch.operations.size)
        assertEquals(Material.AIR.name, batch.operations.last().blockStateId)
        assertTrue(batch.operations.take(2).all { it.blockStateId != Material.AIR.name })
    }
}