package systems.diath.homeclaim.platform.paper.plot.mutation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlotChunkPlannerTest {

    @Test
    fun `groups columns by positive and negative chunks`() {
        val grouped = PlotChunkPlanner.groupColumnsByChunk(
            listOf(
                0 to 0,
                15 to 15,
                16 to 0,
                -1 to -1,
                -17 to 32
            )
        )

        assertEquals(4, grouped.size)
        assertTrue((0 to 0) in grouped[PlotChunkPlanner.ChunkKey(0, 0)].orEmpty())
        assertTrue((15 to 15) in grouped[PlotChunkPlanner.ChunkKey(0, 0)].orEmpty())
        assertTrue((16 to 0) in grouped[PlotChunkPlanner.ChunkKey(1, 0)].orEmpty())
        assertTrue((-1 to -1) in grouped[PlotChunkPlanner.ChunkKey(-1, -1)].orEmpty())
        assertTrue((-17 to 32) in grouped[PlotChunkPlanner.ChunkKey(-2, 2)].orEmpty())
    }
}
