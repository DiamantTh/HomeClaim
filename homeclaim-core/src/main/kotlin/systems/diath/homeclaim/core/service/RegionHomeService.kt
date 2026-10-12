package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionHome
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionOwnership
import systems.diath.homeclaim.core.policy.HomeClaimOperationActions

/** Actor-aware operation for the optional typed home of a plot or logical merge group. */
class RegionHomeService(
    private val regions: RegionService,
    private val auditService: AuditService? = null,
    private val authorizer: RegionMutationAuthorizer = RegionMutationAuthorizer.SYSTEM_ONLY
) {
    fun setHome(context: RegionMutationContext, regionId: RegionId, home: RegionHome): OperationResult<Region> {
        val region = regions.getRegionById(regionId)
        val request = context.actionRequest(HomeClaimOperationActions.REGION_HOME_SET, regionId, region, mapOf(
            "x" to home.x.toString(), "y" to home.y.toString(), "z" to home.z.toString()
        ))
        auditService?.append(AuditEntries.operationResult(request, AuditStatus.ATTEMPTED, targetCount = 1))
        fun reject(status: OperationStatus, detail: String): OperationResult<Region> {
            auditService?.append(AuditEntries.operationResult(request,
                if (status == OperationStatus.DENIED) AuditStatus.DENIED else AuditStatus.FAILED,
                error = detail, targetCount = 1))
            return OperationResult.rejected(status, detail)
        }
        if (region == null) return reject(OperationStatus.NOT_FOUND, "REGION_NOT_FOUND")
        if (RegionOwnership.isUnclaimed(region.owner)) return reject(OperationStatus.DENIED, "REGION_UNCLAIMED")
        if (!home.isFinite()) return reject(OperationStatus.INVALID, "HOME_COORDINATES_MUST_BE_FINITE")
        if (!authorizer.isAuthorized(context.actor, region, RegionMutationCapability.HOME_CHANGE)) {
            return reject(OperationStatus.DENIED, "HOME_CHANGE_DENIED")
        }
        val group = logicalGroup(region)
        if (group.none { it.bounds.contains(home.x.floorBlock(), home.y.floorBlock(), home.z.floorBlock()) }) {
            return reject(OperationStatus.INVALID, "HOME_OUTSIDE_LOGICAL_PLOT")
        }
        // The command-selected region is the explicit anchor. Clear competing group homes
        // so resolution never relies on list order after merge.
        val originals = group.associateBy { it.id }
        return try {
            MutationAuditScope.withinOperation {
                group.forEach { member ->
                    regions.updateRegion(member.copy(home = if (member.id == region.id) home else null))
                }
            }
            val updated = requireNotNull(regions.getRegionById(region.id))
            auditService?.append(AuditEntries.operationResult(request, AuditStatus.COMPLETED, targetCount = group.size))
            OperationResult.success(updated)
        } catch (failure: Exception) {
            runCatching { MutationAuditScope.withinOperation { originals.values.forEach(regions::updateRegion) } }
            reject(OperationStatus.FAILED, failure.message ?: "HOME_PERSISTENCE_FAILED")
        }
    }

    /** Returns the sole configured group home; ambiguous legacy state deliberately falls back. */
    fun resolveHome(region: Region): RegionHome? = logicalGroup(region).mapNotNull { it.home }.distinct().singleOrNull()

    /** Clear an anchor home which is no longer inside its physical plot after unlink. */
    fun reconcileUnlinked(regionsBeforeUnlink: Collection<Region>) {
        MutationAuditScope.withinOperation {
            regionsBeforeUnlink.forEach { original ->
                val current = regions.getRegionById(original.id) ?: return@forEach
                val home = current.home ?: return@forEach
                if (!current.bounds.contains(home.x.floorBlock(), home.y.floorBlock(), home.z.floorBlock())) {
                    regions.updateRegion(current.copy(home = null))
                }
            }
        }
    }

    private fun logicalGroup(region: Region): List<Region> = region.mergeGroupId?.let { groupId ->
        regions.listAllRegions().filter { it.areaId == region.areaId && it.mergeGroupId == groupId }
    }?.ifEmpty { listOf(region) } ?: listOf(region)

    private fun Double.floorBlock(): Int = kotlin.math.floor(this).toInt()
}
