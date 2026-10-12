package systems.diath.homeclaim.core.model

data class Region(
    val id: RegionId,
    /** Legacy display value; canonical spatial ownership is [areaId]. */
    @Deprecated("Use areaId and resolve its HomeClaimWorld")
    val world: String,
    val shape: RegionShape,
    val bounds: Bounds,
    val owner: PlayerId,
    val roles: RegionRoles = RegionRoles(),
    val flags: Map<FlagKey, FlagValue> = emptyMap(),
    val limits: Map<LimitKey, LimitValue> = emptyMap(),
    val metadata: Map<String, String> = emptyMap(),
    /** Optional player-defined teleport location. Its world is derived from [areaId]. */
    val home: RegionHome? = null,
    val mergeGroupId: MergeGroupId? = null,
    val price: Double = 0.0,  // Price for buying (0 = not for sale)
    val areaId: AreaId = AreaId.legacyDefault(WorldId.fromLegacyName(world))
)
