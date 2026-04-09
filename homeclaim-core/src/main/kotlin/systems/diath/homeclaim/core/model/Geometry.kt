package systems.diath.homeclaim.core.model

/**
 * Bounding box used for fast pre-checks before detailed shape evaluation.
 */
data class Bounds(
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int,
    val minZ: Int,
    val maxZ: Int
) {
    fun contains(x: Int, y: Int, z: Int): Boolean {
        return x in minX..maxX && y in minY..maxY && z in minZ..maxZ
    }
    
    /**
     * 2D containment check (ignores Y axis).
     * Useful for plot lookups where height shouldn't matter.
     */
    fun contains2D(x: Int, z: Int): Boolean {
        return x in minX..maxX && z in minZ..maxZ
    }
}

enum class RegionShape {
    PLOT_GRID,
    CUBOID,
    POLYGON
}
