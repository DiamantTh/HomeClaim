package systems.diath.homeclaim.platform.paper.plot.mutation

import systems.diath.homeclaim.core.model.Bounds

/**
 * Small, testable planner that only computes the border columns of a region.
 * It intentionally does not mutate the world directly.
 */
object PlotBorderPlanner {
    fun borderColumns(
        bounds: Bounds,
        includeNorth: Boolean = true,
        includeSouth: Boolean = true,
        includeWest: Boolean = true,
        includeEast: Boolean = true
    ): List<Pair<Int, Int>> {
        val coords = linkedSetOf<Pair<Int, Int>>()

        if (includeNorth) {
            for (x in bounds.minX..bounds.maxX) {
                coords += x to bounds.minZ
            }
        }
        if (includeSouth) {
            for (x in bounds.minX..bounds.maxX) {
                coords += x to bounds.maxZ
            }
        }
        if (includeWest) {
            for (z in bounds.minZ..bounds.maxZ) {
                coords += bounds.minX to z
            }
        }
        if (includeEast) {
            for (z in bounds.minZ..bounds.maxZ) {
                coords += bounds.maxX to z
            }
        }

        return coords.toList()
    }
}
