package systems.diath.homeclaim.platform.paper.plot.mutation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionShape
import systems.diath.homeclaim.core.model.WorldId
import java.time.Instant
import java.util.UUID

class BiomeSnapshotV1Test {
    private fun region(bounds: Bounds) = Region(RegionId(UUID.randomUUID()), "test", RegionShape.CUBOID,
        bounds, UUID.randomUUID(), areaId = AreaId(UUID.randomUUID()))

    @Test
    fun `quart mapping floors negative coordinates`() {
        assertEquals(BiomeCell(-1, -2, -1), BiomeCell.fromBlock(-1, -5, -4))
        assertEquals(BiomeCell(-1, -1, -1), BiomeCell.fromBlock(-4, -1, -1))
        assertEquals(BiomeCell(-2, -2, -2), BiomeCell.fromBlock(-5, -8, -7))
    }

    @Test
    fun `scope includes only complete quart cells inside each region`() {
        val region = region(Bounds(-7, 2, -8, 7, 8, 17))
        val cells = BiomeCellScope.cells(listOf(region))
        assertEquals(setOf(-2, -1, 0, 1), cells.map { it.quartY }.toSet())
        assertEquals((-2..1).flatMap { qy -> listOf(BiomeCell(-1, qy, 2), BiomeCell(-1, qy, 3)) }.toSet(), cells.toSet())
        assertThrows(IllegalArgumentException::class.java) {
            BiomeCellScope.cells(listOf(region(Bounds(1, 2, 0, 2, 1, 2))))
        }
    }

    @Test
    fun `snapshot round trips typed identity and rejects corruption`() {
        val snapshot = BiomeSnapshotV1(UUID.randomUUID(), WorldId(UUID.randomUUID()), UUID.randomUUID(),
            AreaId(UUID.randomUUID()), listOf(RegionId(UUID.randomUUID())), Instant.parse("2026-11-02T00:00:00Z"),
            listOf(BiomeSnapshotCell(BiomeCell(-1, 4, 2), "minecraft:plains")))
        val bytes = snapshot.encode()
        assertEquals(snapshot, BiomeSnapshotV1.decode(bytes))
        val corrupt = bytes.clone().also { it[it.lastIndex - 4] = (it[it.lastIndex - 4].toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { BiomeSnapshotV1.decode(corrupt) }
    }
}
