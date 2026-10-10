package systems.diath.homeclaim.core.model

data class ZoneDefaults(
    val defaultFlags: Map<FlagKey, FlagValue> = emptyMap(),
    val defaultLimits: Map<LimitKey, LimitValue> = emptyMap(),
    val allowedTriggerBlocks: Set<String> = emptySet()
)

data class Zone(
    val id: ZoneId,
    /** Legacy display value; canonical spatial ownership is [areaId]. */
    @Deprecated("Use areaId and resolve its HomeClaimWorld")
    val world: String,
    val shape: RegionShape,
    val bounds: Bounds,
    val priority: Int,
    val defaults: ZoneDefaults = ZoneDefaults(),
    val lockedFlags: Set<FlagKey> = emptySet(),
    val tags: Set<String> = emptySet(),
    val areaId: AreaId = AreaId.legacyDefault(WorldId.fromLegacyName(world))
) {
    fun appliesTo(position: Position): Boolean = bounds.contains(position.x, position.y, position.z)
}
