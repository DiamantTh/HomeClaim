package systems.diath.homeclaim.core.model

sealed interface AreaScope {
    fun contains(x: Int, y: Int, z: Int): Boolean
    fun overlaps(other: AreaScope): Boolean

    data object WholeWorld : AreaScope {
        override fun contains(x: Int, y: Int, z: Int): Boolean = true
        override fun overlaps(other: AreaScope): Boolean = true
    }

    data class Finite(val bounds: Bounds) : AreaScope {
        override fun contains(x: Int, y: Int, z: Int): Boolean = bounds.contains(x, y, z)

        override fun overlaps(other: AreaScope): Boolean = when (other) {
            WholeWorld -> true
            is Finite -> bounds.overlaps(other.bounds)
        }
    }
}

data class Area(
    val id: AreaId,
    val worldId: WorldId,
    val key: String,
    val scope: AreaScope,
    val priority: Int = 0,
    val metadata: Map<String, String> = emptyMap()
) {
    init {
        require(key.isNotBlank()) { "Area key cannot be blank" }
    }

    fun contains(x: Int, y: Int, z: Int): Boolean = scope.contains(x, y, z)
}

/** Immutable, platform-neutral area resolver suitable for replacement by an indexed runtime snapshot. */
class AreaResolver(areas: Collection<Area>) {
    private val byWorld: Map<WorldId, List<Area>> = areas
        .groupBy(Area::worldId)
        .mapValues { (_, worldAreas) -> worldAreas.sortedWith(AREA_ORDER) }

    init {
        byWorld.values.forEach(::validateSamePriorityOverlaps)
    }

    fun resolveArea(worldId: WorldId, x: Int, y: Int, z: Int): Area? =
        byWorld[worldId].orEmpty().firstOrNull { it.contains(x, y, z) }

    companion object {
        private val AREA_ORDER = compareByDescending<Area> { it.priority }
            .thenBy { it.id.value.toString() }

        fun requireNoEqualPriorityOverlap(candidate: Area, existing: Collection<Area>) {
            val conflict = existing.firstOrNull {
                it.worldId == candidate.worldId &&
                    it.priority == candidate.priority &&
                    it.scope.overlaps(candidate.scope)
            }
            require(conflict == null) {
                "Area '${candidate.key}' overlaps area '${conflict?.key}' at priority ${candidate.priority}"
            }
        }

        private fun validateSamePriorityOverlaps(areas: List<Area>) {
            areas.forEachIndexed { index, area ->
                val conflict = areas.drop(index + 1).firstOrNull {
                    it.priority == area.priority && it.scope.overlaps(area.scope)
                }
                require(conflict == null) {
                    "Areas '${area.key}' and '${conflict?.key}' overlap at priority ${area.priority}"
                }
            }
        }
    }
}
