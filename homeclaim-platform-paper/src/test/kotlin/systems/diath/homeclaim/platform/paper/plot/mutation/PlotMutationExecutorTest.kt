package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PlotMutationExecutorTest {
    @Test
    fun `known block states resolve for native mutation batches`() {
        assertEquals(Material.STONE, PlotMutationExecutor.resolveMaterial("STONE", "test-batch", 1, 64, 2))
    }

    @Test
    fun `unknown block states fail the mutation instead of being silently skipped`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            PlotMutationExecutor.resolveMaterial("NOT_A_BLOCK", "test-batch", 1, 64, 2)
        }

        assertEquals("Unknown block state 'NOT_A_BLOCK' in mutation batch 'test-batch' at 1,64,2", error.message)
    }
}
