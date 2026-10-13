package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionOwnership
import systems.diath.homeclaim.core.policy.ActionRequest
import systems.diath.homeclaim.core.policy.HomeClaimOperationActions

/** Validates and audits a Paper-executed biome mutation without placing Bukkit APIs in core. */
class RegionBiomeService(
    private val regions: RegionService,
    private val auditService: AuditService? = null,
    private val authorizer: RegionMutationAuthorizer = RegionMutationAuthorizer.SYSTEM_ONLY
) {
    fun prepare(context: RegionMutationContext, regionId: RegionId, biomeKey: String): OperationResult<RegionBiomePlan> {
        val region = regions.getRegionById(regionId)
        val request = context.actionRequest(HomeClaimOperationActions.REGION_BIOME_CHANGE, regionId, region, mapOf("biome" to biomeKey))
        auditService?.append(AuditEntries.operationResult(request, AuditStatus.ATTEMPTED, targetCount = 1))
        fun reject(status: OperationStatus, detail: String): OperationResult<RegionBiomePlan> {
            auditService?.append(AuditEntries.operationResult(request, if (status == OperationStatus.DENIED) AuditStatus.DENIED else AuditStatus.FAILED, detail, 1))
            return OperationResult.rejected(status, detail)
        }
        if (region == null) return reject(OperationStatus.NOT_FOUND, "REGION_NOT_FOUND")
        if (RegionOwnership.isUnclaimed(region.owner)) return reject(OperationStatus.DENIED, "REGION_UNCLAIMED")
        if (!authorizer.isAuthorized(context.actor, region, RegionMutationCapability.BIOME_CHANGE)) {
            return reject(OperationStatus.DENIED, "BIOME_CHANGE_DENIED")
        }
        val members = region.mergeGroupId?.let { groupId ->
            regions.listAllRegions().filter { it.areaId == region.areaId && it.mergeGroupId == groupId }
        }.orEmpty().ifEmpty { listOf(region) }
        if (members.any { it.areaId != region.areaId }) return reject(OperationStatus.CONFLICT, "BIOME_GROUP_CROSSES_AREA")
        return OperationResult.success(RegionBiomePlan(request, members, biomeKey))
    }

    fun completed(plan: RegionBiomePlan) {
        auditService?.append(AuditEntries.operationResult(plan.request, AuditStatus.COMPLETED, targetCount = plan.regions.size))
    }

    fun failed(plan: RegionBiomePlan, failure: Throwable) {
        auditService?.append(AuditEntries.operationResult(plan.request, AuditStatus.FAILED,
            failure.message ?: "BIOME_MUTATION_FAILED", plan.regions.size))
    }
}

data class RegionBiomePlan(val request: ActionRequest, val regions: List<Region>, val biomeKey: String)
