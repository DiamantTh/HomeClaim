package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.FlagKey
import systems.diath.homeclaim.core.model.FlagValue
import systems.diath.homeclaim.core.model.LimitKey
import systems.diath.homeclaim.core.model.LimitValue
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.policy.FlagProfile
import systems.diath.homeclaim.core.model.Region

interface RegionAdminService {
    fun mutateFlag(context: RegionMutationContext, regionId: RegionId, key: FlagKey, value: FlagValue?): OperationResult<Region> =
        OperationResult.rejected(OperationStatus.FAILED, "ACTOR_AWARE_FLAG_OPERATION_UNAVAILABLE")
    fun mutateLimit(context: RegionMutationContext, regionId: RegionId, key: LimitKey, value: LimitValue?): OperationResult<Region> =
        OperationResult.rejected(OperationStatus.FAILED, "ACTOR_AWARE_LIMIT_OPERATION_UNAVAILABLE")
    fun applyProfile(context: RegionMutationContext, regionId: RegionId, profileName: String): OperationResult<Region> =
        OperationResult.rejected(OperationStatus.FAILED, "ACTOR_AWARE_PROFILE_OPERATION_UNAVAILABLE")
    fun mutateProfile(context: RegionMutationContext, profile: FlagProfile): OperationResult<FlagProfile> =
        OperationResult.rejected(OperationStatus.FAILED, "ACTOR_AWARE_PROFILE_OPERATION_UNAVAILABLE")
    fun setPrice(context: RegionMutationContext, regionId: RegionId, price: Double): OperationResult<Region> =
        OperationResult.rejected(OperationStatus.FAILED, "ACTOR_AWARE_PRICE_OPERATION_UNAVAILABLE")
    /** Changes the supported region presentation metadata (`alias` or `description`). */
    fun mutateMetadata(context: RegionMutationContext, regionId: RegionId, key: String, value: String?): OperationResult<Region> =
        OperationResult.rejected(OperationStatus.FAILED, "ACTOR_AWARE_METADATA_OPERATION_UNAVAILABLE")

    @Deprecated("Use actor-aware mutation methods")
    fun upsertFlag(regionId: RegionId, key: FlagKey, value: FlagValue)
    @Deprecated("Use actor-aware mutation methods")
    fun upsertLimit(regionId: RegionId, key: LimitKey, value: LimitValue)
    @Deprecated("Use actor-aware mutation methods")
    fun applyProfile(regionId: RegionId, profileName: String)
    @Deprecated("Use actor-aware mutation methods")
    fun upsertProfile(profile: FlagProfile)
    fun listProfiles(): List<FlagProfile>
}
