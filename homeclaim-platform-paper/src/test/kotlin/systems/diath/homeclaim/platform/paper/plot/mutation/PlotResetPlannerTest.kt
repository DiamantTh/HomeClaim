package systems.diath.homeclaim.platform.paper.plot.mutation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import systems.diath.homeclaim.core.model.Bounds

class PlotResetPlannerTest {

    @Test
    fun `returns all interior columns for plot bounds`() {
        val bounds = Bounds(minX = 5, maxX = 7, minY = -64, maxY = 319, minZ = 10, maxZ = 12)

        val columns = PlotResetPlanner.interiorColumns(bounds)

        assertEquals(9, columns.size)
        assertTrue((5 to 10) in columns)
        assertTrue((7 to 12) in columns)
    }
}
