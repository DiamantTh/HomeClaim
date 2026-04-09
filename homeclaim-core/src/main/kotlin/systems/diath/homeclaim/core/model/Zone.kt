package systems.diath.homeclaim.core.model

data class ZoneDefaults(
    val defaultFlags: Map<FlagKey, FlagValue> = emptyMap(),
    val defaultLimits: Map<LimitKey, LimitValue> = emptyMap(),
    val allowedTriggerBlocks: Set<String> = emptySet()
)

data class Zone(
    val id: ZoneId,
    val world: WorldId,
    val shape: RegionShape,
    val bounds: Bounds,
    val priority: Int,
    val defaults: ZoneDefaults = ZoneDefaults(),
    val lockedFlags: Set<FlagKey> = emptySet(),
    val tags: Set<String> = emptySet()
) {
    fun appliesTo(position: Position): Boolean = bounds.contains(position.x, position.y, position.z)
}
