package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.economy.EconService
import systems.diath.homeclaim.core.model.MergeGroupId
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.RegionId

data class OperationLimitResult(
    val allowed: Boolean,
    val operation: String,
    val current: Int,
    val maximum: Int,
    val reason: String? = null
)

/** One domain entry point for claim and merge quotas; -1 means unlimited. */
class RegionOperationLimitService(
    private val regions: RegionService,
    private val maxOwnedRegions: Int = -1,
    private val maxMergedRegions: Int = -1
) {
    private val claimLock = Any()

    init {
        require(maxOwnedRegions >= -1) { "maxOwnedRegions must be -1 (unlimited) or non-negative" }
        require(maxMergedRegions >= -1) { "maxMergedRegions must be -1 (unlimited) or non-negative" }
    }

    fun evaluateClaim(ownerId: PlayerId, bypass: Boolean = false): OperationLimitResult {
        val current = regions.listRegionsByOwner(ownerId).size
        val allowed = bypass || maxOwnedRegions < 0 || current < maxOwnedRegions
        return OperationLimitResult(allowed, "claim", current, maxOwnedRegions,
            if (allowed) null else "CLAIM_LIMIT")
    }

    fun claimRegion(
        regionId: RegionId,
        ownerId: PlayerId,
        cost: Double,
        economy: EconService,
        bypass: Boolean = false
    ): Boolean = synchronized(claimLock) {
        if (!evaluateClaim(ownerId, bypass).allowed) return@synchronized false
        regions.claimRegion(regionId, ownerId, cost, economy)
    }

    fun evaluateMerge(regionIds: Collection<RegionId>, bypass: Boolean = false): OperationLimitResult {
        val merged = regionIds.toSet().mapNotNull(regions::getRegionById)
        val sameResource = merged.map { it.owner to it.areaId }.distinct().size <= 1
        val validTargets = sameResource && merged.size == regionIds.toSet().size
        val allowed = validTargets && (bypass || maxMergedRegions < 0 || merged.size <= maxMergedRegions)
        return OperationLimitResult(allowed, "merge", merged.size, maxMergedRegions,
            if (allowed) null else if (!sameResource) "MERGE_RESOURCE_MISMATCH" else "MERGE_LIMIT")
    }

    fun mergeRegions(regionIds: Collection<RegionId>, bypass: Boolean = false): MergeGroupId {
        require(evaluateMerge(regionIds, bypass).allowed) { "Merge rejected by operation limit or resource scope" }
        return regions.mergeRegions(regionIds)
    }
}
