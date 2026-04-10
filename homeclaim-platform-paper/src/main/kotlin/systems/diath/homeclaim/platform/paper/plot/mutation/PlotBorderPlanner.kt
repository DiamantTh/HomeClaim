package systems.diath.homeclaim.platform.paper.plot.mutation

import systems.diath.homeclaim.core.model.Bounds
import kotlin.math.max
import kotlin.math.min

data class SharedEdges(
    val north: Boolean = false,
    val south: Boolean = false,
    val west: Boolean = false,
    val east: Boolean = false
)

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

    fun sharedEdges(bounds: Bounds, others: Collection<Bounds>, maxGap: Int): SharedEdges {
        var north = false
        var south = false
        var west = false
        var east = false

        for (other in others) {
            if (isNorthNeighbor(bounds, other, maxGap)) north = true
            if (isSouthNeighbor(bounds, other, maxGap)) south = true
            if (isWestNeighbor(bounds, other, maxGap)) west = true
            if (isEastNeighbor(bounds, other, maxGap)) east = true
        }

        return SharedEdges(north = north, south = south, west = west, east = east)
    }

    fun mergeCorridorColumns(first: Bounds, second: Bounds, maxGap: Int): List<Pair<Int, Int>> {
        val coords = linkedSetOf<Pair<Int, Int>>()

        if (isEastNeighbor(first, second, maxGap)) {
            val zStart = max(first.minZ, second.minZ)
            val zEnd = min(first.maxZ, second.maxZ)
            for (x in first.maxX..second.minX) {
                for (z in zStart..zEnd) {
                    coords += x to z
                }
            }
        } else if (isWestNeighbor(first, second, maxGap)) {
            val zStart = max(first.minZ, second.minZ)
            val zEnd = min(first.maxZ, second.maxZ)
            for (x in second.maxX..first.minX) {
                for (z in zStart..zEnd) {
                    coords += x to z
                }
            }
        } else if (isSouthNeighbor(first, second, maxGap)) {
            val xStart = max(first.minX, second.minX)
            val xEnd = min(first.maxX, second.maxX)
            for (x in xStart..xEnd) {
                for (z in first.maxZ..second.minZ) {
                    coords += x to z
                }
            }
        } else if (isNorthNeighbor(first, second, maxGap)) {
            val xStart = max(first.minX, second.minX)
            val xEnd = min(first.maxX, second.maxX)
            for (x in xStart..xEnd) {
                for (z in second.maxZ..first.minZ) {
                    coords += x to z
                }
            }
        }

        return coords.toList()
    }

    private fun isEastNeighbor(bounds: Bounds, other: Bounds, maxGap: Int): Boolean {
        val gap = other.minX - bounds.maxX
        return gap in 1..maxGap && overlaps(bounds.minZ, bounds.maxZ, other.minZ, other.maxZ)
    }

    private fun isWestNeighbor(bounds: Bounds, other: Bounds, maxGap: Int): Boolean {
        val gap = bounds.minX - other.maxX
        return gap in 1..maxGap && overlaps(bounds.minZ, bounds.maxZ, other.minZ, other.maxZ)
    }

    private fun isSouthNeighbor(bounds: Bounds, other: Bounds, maxGap: Int): Boolean {
        val gap = other.minZ - bounds.maxZ
        return gap in 1..maxGap && overlaps(bounds.minX, bounds.maxX, other.minX, other.maxX)
    }

    private fun isNorthNeighbor(bounds: Bounds, other: Bounds, maxGap: Int): Boolean {
        val gap = bounds.minZ - other.maxZ
        return gap in 1..maxGap && overlaps(bounds.minX, bounds.maxX, other.minX, other.maxX)
    }

    private fun overlaps(firstMin: Int, firstMax: Int, secondMin: Int, secondMax: Int): Boolean {
        return firstMin <= secondMax && secondMin <= firstMax
    }
}
