package systems.diath.homeclaim.platform.paper.plot.mutation

/** Pure helper for grouping column work by chunk, mainly for Folia-safe scheduling. */
internal object PlotChunkPlanner {
    data class ChunkKey(val x: Int, val z: Int)

    fun groupColumnsByChunk(columns: Collection<Pair<Int, Int>>): Map<ChunkKey, List<Pair<Int, Int>>> {
        return columns.groupBy { (x, z) ->
            ChunkKey(Math.floorDiv(x, 16), Math.floorDiv(z, 16))
        }
    }
}
