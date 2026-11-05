package systems.diath.homeclaim.platform.paper.plot.mutation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BiomeChunkLoaderTest {
    @Test fun `required chunks floor negatives and deduplicate vertical cells`() {
        assertEquals(listOf(BiomeChunkLoader.Coordinate(-1,-1), BiomeChunkLoader.Coordinate(-2,0), BiomeChunkLoader.Coordinate(0,1)),
            BiomeChunkLoader.requiredChunks(listOf(BiomeCell(-1,-2,-1),BiomeCell(-1,8,-1),BiomeCell(-5,0,0),BiomeCell(0,0,4))))
    }
    @Test fun `scope fails at bounded chunk limit`() {
        assertEquals(256,BiomeChunkLoader.requiredChunks((0 until 256).map { BiomeCell(it*4,0,0) }).size)
        assertThrows(IllegalArgumentException::class.java) { BiomeChunkLoader.requiredChunks((0..256).map { BiomeCell(it*4,0,0) }) }
    }
}
