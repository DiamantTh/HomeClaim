package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.FlagKey
import systems.diath.homeclaim.core.model.FlagValue
import systems.diath.homeclaim.core.model.LimitKey
import systems.diath.homeclaim.core.model.LimitValue
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.policy.FlagProfile

interface RegionAdminService {
    fun upsertFlag(regionId: RegionId, key: FlagKey, value: FlagValue)
    fun upsertLimit(regionId: RegionId, key: LimitKey, value: LimitValue)
    fun applyProfile(regionId: RegionId, profileName: String)
    fun upsertProfile(profile: FlagProfile)
    fun listProfiles(): List<FlagProfile>
}
