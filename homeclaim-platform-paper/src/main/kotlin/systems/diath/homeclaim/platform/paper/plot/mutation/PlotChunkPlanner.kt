package systems.diath.homeclaim.platform.paper.plot.mutation

/** Pure helpers for chunk-oriented column planning, mainly for Folia-safe scheduling. */
internal object PlotChunkPlanner {
    data class ChunkKey(val x: Int, val z: Int)

    data class ChunkBatch(
        val chunk: ChunkKey,
        val columns: List<Pair<Int, Int>>
    )

    fun groupColumnsByChunk(columns: Collection<Pair<Int, Int>>): Map<ChunkKey, List<Pair<Int, Int>>> {
        if (columns.isEmpty()) return emptyMap()

        val sortedColumns = columns
            .distinct()
            .sortedWith(
                compareBy<Pair<Int, Int>>(
                    { Math.floorDiv(it.first, 16) },
                    { Math.floorDiv(it.second, 16) },
                    { it.first },
                    { it.second }
                )
            )

        val grouped = linkedMapOf<ChunkKey, MutableList<Pair<Int, Int>>>()
        for ((x, z) in sortedColumns) {
            val key = ChunkKey(Math.floorDiv(x, 16), Math.floorDiv(z, 16))
            grouped.getOrPut(key) { mutableListOf() }.add(x to z)
        }
        return grouped
    }

    fun batchColumnsByChunk(columns: Collection<Pair<Int, Int>>, maxColumnsPerBatch: Int): List<ChunkBatch> {
        val batchSize = maxColumnsPerBatch.coerceAtLeast(1)
        return groupColumnsByChunk(columns).flatMap { (chunk, chunkColumns) ->
            chunkColumns.chunked(batchSize).map { batch ->
                ChunkBatch(chunk, batch)
            }
        }
    }
}
