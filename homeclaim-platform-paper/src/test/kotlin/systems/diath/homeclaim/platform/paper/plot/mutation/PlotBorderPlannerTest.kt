package systems.diath.homeclaim.platform.paper.plot.mutation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import systems.diath.homeclaim.core.model.Bounds

class PlotBorderPlannerTest {

    @Test
    fun `returns unique perimeter columns`() {
        val bounds = Bounds(minX = 10, maxX = 12, minY = -64, maxY = 319, minZ = 20, maxZ = 22)

        val result = PlotBorderPlanner.borderColumns(bounds)

        assertEquals(8, result.size)
        assertTrue((10 to 20) in result)
        assertTrue((12 to 22) in result)
        assertFalse((11 to 21) in result)
    }

    @Test
    fun `can omit selected edges`() {
        val bounds = Bounds(minX = 0, maxX = 2, minY = -64, maxY = 319, minZ = 0, maxZ = 2)

        val result = PlotBorderPlanner.borderColumns(
            bounds,
            includeNorth = false,
            includeWest = false
        )

        assertFalse((0 to 0) in result)
        assertFalse((1 to 0) in result)
        assertTrue((2 to 1) in result)
        assertTrue((1 to 2) in result)
    }
}
