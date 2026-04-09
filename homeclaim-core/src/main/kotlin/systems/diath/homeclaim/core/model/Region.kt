package systems.diath.homeclaim.core.model

data class Region(
    val id: RegionId,
    val world: WorldId,
    val shape: RegionShape,
    val bounds: Bounds,
    val owner: PlayerId,
    val roles: RegionRoles = RegionRoles(),
    val flags: Map<FlagKey, FlagValue> = emptyMap(),
    val limits: Map<LimitKey, LimitValue> = emptyMap(),
    val metadata: Map<String, String> = emptyMap(),
    val mergeGroupId: MergeGroupId? = null,
    val price: Double = 0.0  // Price for buying (0 = not for sale)
)
