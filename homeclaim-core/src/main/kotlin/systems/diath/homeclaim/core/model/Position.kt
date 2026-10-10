package systems.diath.homeclaim.core.model

data class Position(
    val worldId: WorldId,
    val x: Int,
    val y: Int,
    val z: Int,
    val legacyWorldName: String? = null
) {
    /** Compatibility display/lookup name. New domain logic must use [worldId]. */
    @Deprecated("Use worldId; world names are not stable identities")
    val world: String get() = legacyWorldName ?: worldId.value.toString()

    @Deprecated("Use the WorldId constructor after resolving the Paper world binding")
    constructor(world: String, x: Int, y: Int, z: Int) : this(
        WorldId.fromLegacyName(world), x, y, z, legacyWorldName = world
    )
}
