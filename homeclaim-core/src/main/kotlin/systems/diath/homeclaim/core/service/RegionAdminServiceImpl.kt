package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.FlagKey
import systems.diath.homeclaim.core.model.FlagValue
import systems.diath.homeclaim.core.model.LimitKey
import systems.diath.homeclaim.core.model.LimitValue
import systems.diath.homeclaim.core.model.PolicyValue
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.policy.FlagCatalog
import systems.diath.homeclaim.core.policy.LimitCatalog
import systems.diath.homeclaim.core.policy.FlagProfile
import systems.diath.homeclaim.core.policy.HomeClaimOperationActions
import systems.diath.homeclaim.core.store.JdbcFlagProfileService

class RegionAdminServiceImpl(
    private val regionService: RegionService,
    private val profileService: FlagProfileService,
    private val profileJdbc: JdbcFlagProfileService? = null,
    private val auditService: AuditService? = null,
    private val authorizer: RegionMutationAuthorizer = RegionMutationAuthorizer.SYSTEM_ONLY
) : RegionAdminService {
    @Synchronized
    override fun mutateFlag(
        context: RegionMutationContext,
        regionId: RegionId,
        key: FlagKey,
        value: FlagValue?
    ): OperationResult<Region> {
        val region = regionService.getRegionById(regionId)
        val action = HomeClaimOperationActions.FLAG_CHANGE
        val request = context.actionRequest(action, regionId, region, mapOf(
            "flag" to key.value,
            "value" to (value?.toString() ?: "INHERIT")
        ))
        auditService?.append(AuditEntries.operationResult(request, AuditStatus.ATTEMPTED, targetCount = 1))
        fun reject(status: OperationStatus, detail: String) = finishRejected<Region>(request, status, detail)
        if (region == null) return reject(OperationStatus.NOT_FOUND, "REGION_NOT_FOUND")
        if (!authorizer.isAuthorized(context.actor, region, RegionMutationCapability.FLAG_CHANGE)) {
            return reject(OperationStatus.DENIED, "FLAG_CHANGE_DENIED")
        }
        if (key !in FlagCatalog.flags) return reject(OperationStatus.INVALID, "UNKNOWN_FLAG:${key.value}")
        if (value != null && value !is PolicyValue.Bool) return reject(OperationStatus.INVALID, "FLAG_REQUIRES_BOOLEAN")
        if (region.flags[key] == value || (value == null && key !in region.flags)) {
            auditService?.append(AuditEntries.operationResult(request.copy(context = request.context.copy(
                attributes = request.context.attributes + ("changed" to "false")
            )), AuditStatus.COMPLETED, targetCount = 1))
            return OperationResult.success(region, "UNCHANGED")
        }
        return persistRegionMutation(request) {
            region.copy(flags = if (value == null) region.flags - key else region.flags + (key to value))
        }
    }

    @Synchronized
    override fun mutateLimit(
        context: RegionMutationContext,
        regionId: RegionId,
        key: LimitKey,
        value: LimitValue?
    ): OperationResult<Region> {
        val region = regionService.getRegionById(regionId)
        val action = HomeClaimOperationActions.LIMIT_CHANGE
        val request = context.actionRequest(action, regionId, region, mapOf(
            "limit" to key.value,
            "value" to (value?.toString() ?: "INHERIT")
        ))
        auditService?.append(AuditEntries.operationResult(request, AuditStatus.ATTEMPTED, targetCount = 1))
        fun reject(status: OperationStatus, detail: String) = finishRejected<Region>(request, status, detail)
        if (region == null) return reject(OperationStatus.NOT_FOUND, "REGION_NOT_FOUND")
        if (!authorizer.isAuthorized(context.actor, region, RegionMutationCapability.LIMIT_CHANGE)) {
            return reject(OperationStatus.DENIED, "LIMIT_CHANGE_DENIED")
        }
        if (key !in LimitCatalog.limits) return reject(OperationStatus.INVALID, "UNKNOWN_LIMIT:${key.value}")
        if (value != null && (value !is PolicyValue.IntValue || value.value < -1)) {
            return reject(OperationStatus.INVALID, "LIMIT_REQUIRES_INTEGER_AT_LEAST_MINUS_ONE")
        }
        if (region.limits[key] == value || (value == null && key !in region.limits)) {
            auditService?.append(AuditEntries.operationResult(request.copy(context = request.context.copy(
                attributes = request.context.attributes + ("changed" to "false")
            )), AuditStatus.COMPLETED, targetCount = 1))
            return OperationResult.success(region, "UNCHANGED")
        }
        return persistRegionMutation(request) {
            region.copy(limits = if (value == null) region.limits - key else region.limits + (key to value))
        }
    }

    @Synchronized
    override fun applyProfile(
        context: RegionMutationContext,
        regionId: RegionId,
        profileName: String
    ): OperationResult<Region> {
        val region = regionService.getRegionById(regionId)
        val request = context.actionRequest(
            HomeClaimOperationActions.PROFILE_CHANGE,
            regionId,
            region,
            mapOf("profile" to profileName)
        )
        auditService?.append(AuditEntries.operationResult(request, AuditStatus.ATTEMPTED, targetCount = 1))
        fun reject(status: OperationStatus, detail: String) = finishRejected<Region>(request, status, detail)
        if (region == null) return reject(OperationStatus.NOT_FOUND, "REGION_NOT_FOUND")
        if (!authorizer.isAuthorized(context.actor, region, RegionMutationCapability.PROFILE_CHANGE)) {
            return reject(OperationStatus.DENIED, "PROFILE_CHANGE_DENIED")
        }
        val profile = profileService.getProfile(profileName) ?: return reject(OperationStatus.NOT_FOUND, "PROFILE_NOT_FOUND")
        validateProfile(profile)?.let { return reject(OperationStatus.INVALID, it) }
        val updated = region.copy(flags = region.flags + profile.flags, limits = region.limits + profile.limits)
        if (updated == region) {
            auditService?.append(AuditEntries.operationResult(request.copy(context = request.context.copy(
                attributes = request.context.attributes + ("changed" to "false")
            )), AuditStatus.COMPLETED, targetCount = 1))
            return OperationResult.success(region, "UNCHANGED")
        }
        return persistRegionMutation(request) { updated }
    }

    @Synchronized
    override fun mutateProfile(context: RegionMutationContext, profile: FlagProfile): OperationResult<FlagProfile> {
        val request = context.actionRequest(
            HomeClaimOperationActions.PROFILE_CHANGE,
            null,
            attributes = mapOf("profile" to profile.name, "scope" to "GLOBAL")
        )
        auditService?.append(AuditEntries.operationResult(request, AuditStatus.ATTEMPTED))
        fun reject(status: OperationStatus, detail: String) = finishRejected<FlagProfile>(request, status, detail)
        if (!authorizer.isAuthorized(context.actor, null, RegionMutationCapability.GLOBAL_PROFILE_CHANGE)) {
            return reject(OperationStatus.DENIED, "PROFILE_CHANGE_DENIED")
        }
        validateProfile(profile)?.let { return reject(OperationStatus.INVALID, it) }
        return try {
            when {
                profileJdbc != null -> profileJdbc.upsert(profile)
                profileService is systems.diath.homeclaim.core.store.InMemoryFlagProfileService -> profileService.register(profile)
                else -> return reject(OperationStatus.FAILED, "PROFILE_STORE_READ_ONLY")
            }
            auditService?.append(AuditEntries.operationResult(request, AuditStatus.COMPLETED))
            OperationResult.success(profile)
        } catch (failure: Exception) {
            finishRejected(request, OperationStatus.FAILED, failure.message ?: "PROFILE_PERSISTENCE_FAILED")
        }
    }

    @Synchronized
    override fun setPrice(context: RegionMutationContext, regionId: RegionId, price: Double): OperationResult<Region> {
        val region = regionService.getRegionById(regionId)
        val request = context.actionRequest(
            HomeClaimOperationActions.PRICE_CHANGE,
            regionId,
            region,
            mapOf("price" to price.toString())
        )
        auditService?.append(AuditEntries.operationResult(request, AuditStatus.ATTEMPTED, targetCount = 1))
        fun reject(status: OperationStatus, detail: String) = finishRejected<Region>(request, status, detail)
        if (region == null) return reject(OperationStatus.NOT_FOUND, "REGION_NOT_FOUND")
        if (!authorizer.isAuthorized(context.actor, region, RegionMutationCapability.PRICE_CHANGE)) {
            return reject(OperationStatus.DENIED, "PRICE_CHANGE_DENIED")
        }
        if (!price.isFinite() || price < 0.0) return reject(OperationStatus.INVALID, "PRICE_MUST_BE_FINITE_AND_NON_NEGATIVE")
        if (region.price == price) {
            auditService?.append(AuditEntries.operationResult(request.copy(context = request.context.copy(
                attributes = request.context.attributes + ("changed" to "false")
            )), AuditStatus.COMPLETED, targetCount = 1))
            return OperationResult.success(region, "UNCHANGED")
        }
        return persistRegionMutation(request) { region.copy(price = price) }
    }

    private fun validateProfile(profile: FlagProfile): String? {
        if (profile.name.isBlank() || profile.name.length > 64 || profile.name.any(Char::isISOControl)) {
            return "INVALID_PROFILE_NAME"
        }
        if (profile.flags.keys.any { it !in FlagCatalog.flags } || profile.flags.values.any { it !is PolicyValue.Bool }) {
            return "INVALID_PROFILE_FLAGS"
        }
        if (profile.limits.keys.any { it !in LimitCatalog.limits } || profile.limits.values.any {
                it !is PolicyValue.IntValue || it.value < -1
            }) {
            return "INVALID_PROFILE_LIMITS"
        }
        return null
    }

    private fun persistRegionMutation(
        request: systems.diath.homeclaim.core.policy.ActionRequest,
        update: () -> Region
    ): OperationResult<Region> = try {
        val updated = update()
        MutationAuditScope.withinOperation { regionService.updateRegion(updated) }
        val completed = request.copy(context = request.context.copy(
            attributes = request.context.attributes + ("changed" to "true")
        ))
        auditService?.append(AuditEntries.operationResult(completed, AuditStatus.COMPLETED, targetCount = 1))
        OperationResult.success(updated)
    } catch (failure: Exception) {
        finishRejected(request, OperationStatus.FAILED, failure.message ?: "REGION_PERSISTENCE_FAILED")
    }

    private fun <T> finishRejected(
        request: systems.diath.homeclaim.core.policy.ActionRequest,
        status: OperationStatus,
        detail: String
    ): OperationResult<T> {
        auditService?.append(AuditEntries.operationResult(
            request,
            if (status == OperationStatus.DENIED) AuditStatus.DENIED else AuditStatus.FAILED,
            error = detail,
            targetCount = if (request.regionId == null) null else 1
        ))
        return OperationResult.rejected(status, detail)
    }

    @Deprecated("Use mutateFlag with an actor-aware context")
    override fun upsertFlag(regionId: RegionId, key: FlagKey, value: FlagValue) {
        val region = regionService.getRegionById(regionId)
            ?: throw NoSuchElementException("Region ${regionId.value} does not exist")
        regionService.updateRegion(region.copy(flags = region.flags + (key to value)))
        auditService?.append(AuditEntries.flagUpsert(regionId.value, key.value, value.toString()))
    }

    @Deprecated("Use mutateLimit with an actor-aware context")
    override fun upsertLimit(regionId: RegionId, key: LimitKey, value: LimitValue) {
        val region = regionService.getRegionById(regionId)
            ?: throw NoSuchElementException("Region ${regionId.value} does not exist")
        regionService.updateRegion(region.copy(limits = region.limits + (key to value)))
        auditService?.append(AuditEntries.limitUpsert(regionId.value, key.value, value.toString()))
    }

    @Deprecated("Use actor-aware applyProfile")
    override fun applyProfile(regionId: RegionId, profileName: String) {
        val profile = profileService.getProfile(profileName)
            ?: throw NoSuchElementException("Flag profile '$profileName' does not exist")
        val region = regionService.getRegionById(regionId)
            ?: throw NoSuchElementException("Region ${regionId.value} does not exist")
        val updated = region.copy(
            flags = region.flags + profile.flags,
            limits = region.limits + profile.limits
        )
        regionService.updateRegion(updated)
        auditService?.append(
            AuditEntries.profileApplied(regionId.value, profileName, profile.flags.size, profile.limits.size)
        )
    }

    @Deprecated("Use mutateProfile with an actor-aware context")
    override fun upsertProfile(profile: FlagProfile) {
        when {
            profileJdbc != null -> profileJdbc.upsert(profile)
            profileService is systems.diath.homeclaim.core.store.InMemoryFlagProfileService -> profileService.register(profile)
            else -> throw IllegalStateException("Flag profile service does not support mutations")
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
