package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionOwnership
import systems.diath.homeclaim.core.model.RegionRoles
import systems.diath.homeclaim.core.policy.HomeClaimOperationActions

/**
 * Releases a single, unmerged plot without changing its physical world contents.
 *
 * Owner-authored presentation and access state is cleared so a later claimant does
 * not inherit it. Local policy, limits, components, ratings and entry-deny rules
 * deliberately remain region state; those have separate administrative contracts.
 */
class RegionUnclaimService(
    private val regions: RegionService,
    private val auditService: AuditService? = null,
    private val authorizer: RegionMutationAuthorizer = RegionMutationAuthorizer.SYSTEM_ONLY
) {
    fun validate(context: RegionMutationContext, regionId: RegionId): OperationResult<Region> {
        val region = regions.getRegionById(regionId)
            ?: return OperationResult.rejected(OperationStatus.NOT_FOUND, "REGION_NOT_FOUND")
        if (RegionOwnership.isUnclaimed(region.owner)) {
            return OperationResult.rejected(OperationStatus.CONFLICT, "REGION_ALREADY_UNCLAIMED")
        }
        if (region.mergeGroupId != null) {
            return OperationResult.rejected(OperationStatus.CONFLICT, "UNCLAIM_MERGED_REGION_REQUIRES_UNLINK")
        }
        if (!authorizer.isAuthorized(context.actor, region, RegionMutationCapability.UNCLAIM)) {
            return OperationResult.rejected(OperationStatus.DENIED, "UNCLAIM_DENIED")
        }
        return OperationResult.success(region)
    }

    fun unclaim(context: RegionMutationContext, regionId: RegionId): OperationResult<Region> {
        val initial = regions.getRegionById(regionId)
        val request = context.actionRequest(HomeClaimOperationActions.REGION_UNCLAIM, regionId, initial)
        auditService?.append(AuditEntries.operationResult(request, AuditStatus.ATTEMPTED, targetCount = 1))
        val validated = validate(context, regionId)
        if (!validated.succeeded) {
            val status = if (validated.status == OperationStatus.DENIED) AuditStatus.DENIED else AuditStatus.FAILED
            auditService?.append(AuditEntries.operationResult(request, status, validated.detail, 1))
            return validated
        }
        val region = requireNotNull(validated.value)
        val updated = region.copy(
            owner = RegionOwnership.UNCLAIMED_OWNER_ID,
            roles = RegionRoles(),
            home = null,
            metadata = region.metadata - "alias" - "description",
            price = 0.0
        )
        return try {
            MutationAuditScope.withinOperation { regions.updateRegion(updated) }
            auditService?.append(AuditEntries.operationResult(request, AuditStatus.COMPLETED, targetCount = 1))
            OperationResult.success(updated)
        } catch (failure: Exception) {
            val detail = failure.message ?: "UNCLAIM_PERSISTENCE_FAILED"
            auditService?.append(AuditEntries.operationResult(request, AuditStatus.FAILED, detail, 1))
            OperationResult.rejected(OperationStatus.FAILED, detail)
        }
    }
}
