package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.economy.EconService
import systems.diath.homeclaim.core.model.MergeGroupId
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.ActionRequest
import systems.diath.homeclaim.core.policy.ActionResourceRef
import systems.diath.homeclaim.core.policy.HomeClaimOperationActions
import java.util.UUID

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
    private val mergeLock = Any()

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

    @Deprecated("Use claim(ActionRequest, ...) to receive a structured domain result")
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

    /** Canonical claim mutation path for adapters; callback runs after validation and before persistence. */
    fun claim(
        request: ActionRequest,
        regionId: RegionId,
        ownerId: PlayerId,
        cost: Double,
        economy: EconService,
        bypass: Boolean = false,
        onAuthorized: () -> Unit = {}
    ): OperationResult<Region> = synchronized(claimLock) {
        val target = request.target
            ?: return@synchronized OperationResult.rejected(OperationStatus.INVALID, "CLAIM_TARGET_REQUIRED")
        if (request.actionKey != HomeClaimOperationActions.REGION_CLAIM ||
            target.resource != ActionResourceRef.Region(regionId) ||
            (request.regionId != null && request.regionId != regionId) ||
            (request.areaId != null && target.areaId != request.areaId) ||
            (request.actor as? ActionActor.Player)?.playerId != ownerId
        ) {
            return@synchronized OperationResult.rejected(OperationStatus.INVALID, "CLAIM_REQUEST_MISMATCH")
        }
        if (request.areaId == null || target.areaId != request.areaId) {
            return@synchronized OperationResult.rejected(OperationStatus.NOT_APPLICABLE, "CLAIM_AREA_UNRESOLVED")
        }
        if (cost < 0.0 || !cost.isFinite()) {
            return@synchronized OperationResult.rejected(OperationStatus.INVALID, "INVALID_CLAIM_COST")
        }
        val region = regions.getRegionById(regionId)
            ?: return@synchronized OperationResult.rejected(OperationStatus.NOT_FOUND, "REGION_NOT_FOUND")
        if (region.areaId != target.areaId || region.areaId != request.areaId) {
            return@synchronized OperationResult.rejected(OperationStatus.NOT_APPLICABLE, "AREA_MISMATCH")
        }
        if (region.owner != UNCLAIMED_OWNER) {
            return@synchronized OperationResult.rejected(OperationStatus.CONFLICT, "REGION_ALREADY_CLAIMED")
        }
        if (!evaluateClaim(ownerId, bypass).allowed) {
            return@synchronized OperationResult.rejected(OperationStatus.LIMIT_REACHED, "CLAIM_LIMIT")
        }

        onAuthorized()
        val claimedSuccessfully = runCatching { regions.claimRegion(regionId, ownerId, cost, economy) }
            .getOrElse { return@synchronized OperationResult.rejected(OperationStatus.FAILED, "CLAIM_EXECUTION_FAILED") }
        if (!claimedSuccessfully) {
            val latest = regions.getRegionById(regionId)
            if (latest == null) return@synchronized OperationResult.rejected(OperationStatus.NOT_FOUND, "REGION_NOT_FOUND")
            if (latest.owner != UNCLAIMED_OWNER) {
                return@synchronized OperationResult.rejected(OperationStatus.CONFLICT, "REGION_ALREADY_CLAIMED")
            }
            if (!evaluateClaim(ownerId, bypass).allowed) {
                return@synchronized OperationResult.rejected(OperationStatus.LIMIT_REACHED, "CLAIM_LIMIT")
            }
            return@synchronized OperationResult.rejected(OperationStatus.FAILED, "CLAIM_EXECUTION_FAILED")
        }
        val claimed = regions.getRegionById(regionId)
            ?: return@synchronized OperationResult.rejected(OperationStatus.FAILED, "CLAIM_RESULT_MISSING")
        if (claimed.owner != ownerId) {
            return@synchronized OperationResult.rejected(OperationStatus.FAILED, "CLAIM_OWNER_MISMATCH")
        }
        OperationResult.success(claimed)
    }

    fun evaluateMerge(regionIds: Collection<RegionId>, bypass: Boolean = false): OperationLimitResult {
        val merged = regionIds.toSet().mapNotNull(regions::getRegionById)
        val sameResource = merged.map { it.owner to it.areaId }.distinct().size <= 1
        val validTargets = sameResource && merged.size == regionIds.toSet().size
        val allowed = validTargets && (bypass || maxMergedRegions < 0 || merged.size <= maxMergedRegions)
        return OperationLimitResult(allowed, "merge", merged.size, maxMergedRegions,
            if (allowed) null else if (!sameResource) "MERGE_RESOURCE_MISMATCH" else "MERGE_LIMIT")
    }

    @Deprecated("Use merge(ActionRequest, ...) to receive a structured domain result")
    fun mergeRegions(regionIds: Collection<RegionId>, bypass: Boolean = false): MergeGroupId {
        require(evaluateMerge(regionIds, bypass).allowed) { "Merge rejected by operation limit or resource scope" }
        return regions.mergeRegions(regionIds)
    }

    /** Canonical merge mutation path for adapters; geometric adjacency stays at the domain boundary caller. */
    fun merge(
        request: ActionRequest,
        regionIds: Collection<RegionId>,
        bypass: Boolean = false,
        onAuthorized: () -> Unit = {}
    ): OperationResult<MergeGroupId> = synchronized(mergeLock) {
        val target = request.target
            ?: return@synchronized OperationResult.rejected(OperationStatus.INVALID, "MERGE_TARGET_REQUIRED")
        val requestRegion = target.resource as? ActionResourceRef.Region
            ?: return@synchronized OperationResult.rejected(OperationStatus.INVALID, "MERGE_REGION_TARGET_REQUIRED")
        if (request.actionKey != HomeClaimOperationActions.REGION_MERGE ||
            (request.regionId != null && request.regionId != requestRegion.regionId) ||
            request.areaId == null || target.areaId != request.areaId
        ) {
            return@synchronized OperationResult.rejected(OperationStatus.INVALID, "MERGE_REQUEST_MISMATCH")
        }
        val uniqueIds = regionIds.toSet()
        if (uniqueIds.size < 2) {
            return@synchronized OperationResult.rejected(OperationStatus.INVALID, "MERGE_REQUIRES_MULTIPLE_REGIONS")
        }
        val found = uniqueIds.mapNotNull(regions::getRegionById)
        if (found.size != uniqueIds.size) {
            return@synchronized OperationResult.rejected(OperationStatus.NOT_FOUND, "REGION_NOT_FOUND")
        }
        val anchor = found.firstOrNull { it.id == requestRegion.regionId }
            ?: return@synchronized OperationResult.rejected(OperationStatus.INVALID, "MERGE_ANCHOR_NOT_INCLUDED")
        if (anchor.areaId != request.areaId || found.any { it.areaId != anchor.areaId || it.owner != anchor.owner }) {
            return@synchronized OperationResult.rejected(OperationStatus.CONFLICT, "MERGE_RESOURCE_MISMATCH")
        }
        if (anchor.owner == UNCLAIMED_OWNER) {
            return@synchronized OperationResult.rejected(OperationStatus.CONFLICT, "UNCLAIMED_REGION")
        }
        if (!evaluateMerge(uniqueIds, bypass).allowed) {
            return@synchronized OperationResult.rejected(OperationStatus.LIMIT_REACHED, "MERGE_LIMIT")
        }

        onAuthorized()
        val groupId = runCatching { regions.mergeRegions(uniqueIds) }.getOrElse {
            return@synchronized OperationResult.rejected(OperationStatus.FAILED, "MERGE_EXECUTION_FAILED")
        }
        OperationResult.success(groupId)
    }

    private companion object {
        val UNCLAIMED_OWNER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
    }
}
