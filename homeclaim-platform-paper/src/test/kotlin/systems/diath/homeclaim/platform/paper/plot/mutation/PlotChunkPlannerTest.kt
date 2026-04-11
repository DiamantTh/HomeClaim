package systems.diath.homeclaim.platform.paper.plot.mutation

import org.junit.jupiter.api.Assertions.assertAll
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

    @Test
    fun `splits oversized chunk work into deterministic batches`() {
        val batches = PlotChunkPlanner.batchColumnsByChunk(
            listOf(
                18 to 0,
                0 to 0,
                17 to 0,
                1 to 0,
                2 to 0
            ),
            maxColumnsPerBatch = 2
        )

        assertAll(
            { assertEquals(3, batches.size) },
            { assertEquals(PlotChunkPlanner.ChunkKey(0, 0), batches[0].chunk) },
            { assertEquals(listOf(0 to 0, 1 to 0), batches[0].columns) },
            { assertEquals(listOf(2 to 0), batches[1].columns) },
            { assertEquals(PlotChunkPlanner.ChunkKey(1, 0), batches[2].chunk) },
            { assertEquals(listOf(17 to 0, 18 to 0), batches[2].columns) }
        )
    }

    @Test
    fun `removes duplicates and coerces invalid batch sizes`() {
        val batches = PlotChunkPlanner.batchColumnsByChunk(
            listOf(0 to 0, 0 to 0, 1 to 0),
            maxColumnsPerBatch = 0
        )

        assertEquals(2, batches.size)
        assertEquals(listOf(0 to 0), batches[0].columns)
        assertEquals(listOf(1 to 0), batches[1].columns)
    }
}
