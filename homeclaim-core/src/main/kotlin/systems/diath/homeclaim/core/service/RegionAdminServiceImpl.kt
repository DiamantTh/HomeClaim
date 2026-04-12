package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.FlagKey
import systems.diath.homeclaim.core.model.FlagValue
import systems.diath.homeclaim.core.model.LimitKey
import systems.diath.homeclaim.core.model.LimitValue
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.policy.FlagProfile
import systems.diath.homeclaim.core.store.JdbcFlagLimitRepository
import systems.diath.homeclaim.core.store.JdbcFlagProfileService

class RegionAdminServiceImpl(
    private val regionService: RegionService,
    private val profileService: FlagProfileService,
    private val flagLimitRepo: JdbcFlagLimitRepository? = null,
    private val profileJdbc: JdbcFlagProfileService? = null,
    private val auditService: AuditService? = null
) : RegionAdminService {
    override fun upsertFlag(regionId: RegionId, key: FlagKey, value: FlagValue) {
        flagLimitRepo?.upsertFlag(regionId, key, value)
        regionService.getRegionById(regionId)?.let { region ->
            val updated = region.copy(flags = region.flags + (key to value))
            regionService.updateRegion(updated)
            
            // Audit log
            auditService?.append(AuditEntries.flagUpsert(regionId.value, key.value, value.toString()))
        }
    }

    override fun upsertLimit(regionId: RegionId, key: LimitKey, value: LimitValue) {
        flagLimitRepo?.upsertLimit(regionId, key, value)
        regionService.getRegionById(regionId)?.let { region ->
            val updated = region.copy(limits = region.limits + (key to value))
            regionService.updateRegion(updated)
            
            // Audit log
            auditService?.append(AuditEntries.limitUpsert(regionId.value, key.value, value.toString()))
        }
    }

    override fun applyProfile(regionId: RegionId, profileName: String) {
        val profile = profileService.getProfile(profileName) ?: return
        regionService.getRegionById(regionId)?.let { region ->
            val updated = region.copy(
                flags = region.flags + profile.flags,
                limits = region.limits + profile.limits
            )
            regionService.updateRegion(updated)
            
            // Audit log
            auditService?.append(
                AuditEntries.profileApplied(regionId.value, profileName, profile.flags.size, profile.limits.size)
            )
        }
    }

    override fun upsertProfile(profile: FlagProfile) {
        profileJdbc?.upsert(profile)
        if (profileService is systems.diath.homeclaim.core.store.InMemoryFlagProfileService) {
            profileService.register(profile)
        }
    }

    override fun listProfiles(): List<FlagProfile> {
        return when (profileService) {
            is systems.diath.homeclaim.core.store.InMemoryFlagProfileService -> profileService.getAll()
            is JdbcFlagProfileService -> profileService.listProfiles()
            else -> emptyList()
        }
    }
}
