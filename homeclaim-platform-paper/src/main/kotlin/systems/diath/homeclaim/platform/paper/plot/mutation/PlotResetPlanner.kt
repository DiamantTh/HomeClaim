package systems.diath.homeclaim.platform.paper.plot.mutation

import systems.diath.homeclaim.core.model.Bounds

/** Pure helper for planning interior reset work in column batches. */
object PlotResetPlanner {
    fun interiorColumns(bounds: Bounds): List<Pair<Int, Int>> {
        val coords = ArrayList<Pair<Int, Int>>((bounds.maxX - bounds.minX + 1) * (bounds.maxZ - bounds.minZ + 1))
        for (x in bounds.minX..bounds.maxX) {
            for (z in bounds.minZ..bounds.maxZ) {
                coords += x to z
            }
        }
        return coords
    }
}
