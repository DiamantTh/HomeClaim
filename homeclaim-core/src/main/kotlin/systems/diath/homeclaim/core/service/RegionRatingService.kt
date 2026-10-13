package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.PlotRating
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionOwnership
import systems.diath.homeclaim.core.model.RegionRole
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.HomeClaimOperationActions
import systems.diath.homeclaim.core.store.RatingRepository

/** Canonical player rating operation for the existing one-rating-per-player repository model. */
class RegionRatingService(
    private val regions: RegionService,
    private val ratings: RatingRepository,
    private val auditService: AuditService? = null
) {
    fun rate(context: RegionMutationContext, regionId: RegionId, value: Int): OperationResult<PlotRating> {
        val region = regions.getRegionById(regionId)
        val request = context.actionRequest(HomeClaimOperationActions.REGION_RATING, regionId, region, mapOf("rating" to value.toString()))
        auditService?.append(AuditEntries.operationResult(request, AuditStatus.ATTEMPTED, targetCount = 1))
        fun reject(status: OperationStatus, detail: String): OperationResult<PlotRating> {
            auditService?.append(AuditEntries.operationResult(request, if (status == OperationStatus.DENIED) AuditStatus.DENIED else AuditStatus.FAILED, detail, 1))
            return OperationResult.rejected(status, detail)
        }
        if (region == null) return reject(OperationStatus.NOT_FOUND, "REGION_NOT_FOUND")
        if (value !in 1..5) return reject(OperationStatus.INVALID, "RATING_MUST_BE_BETWEEN_1_AND_5")
        val actor = context.actor as? ActionActor.Player ?: return reject(OperationStatus.DENIED, "RATING_REQUIRES_PLAYER_ACTOR")
        if (RegionOwnership.isUnclaimed(region.owner)) return reject(OperationStatus.CONFLICT, "REGION_UNCLAIMED")
        if (region.mergeGroupId != null) return reject(OperationStatus.CONFLICT, "RATING_MERGED_REGION_REQUIRES_STABLE_GROUP_IDENTITY")
        if (actor.playerId == region.owner) return reject(OperationStatus.DENIED, "OWNER_CANNOT_RATE_OWN_REGION")
        if (region.roles.resolve(actor.playerId, region.owner) == RegionRole.BANNED) return reject(OperationStatus.DENIED, "BANNED_ACTOR_CANNOT_RATE_REGION")
        return try {
            val existing = ratings.getRating(regionId, actor.playerId)
            val saved = ratings.saveRating(PlotRating(
                id = existing?.id ?: java.util.UUID.randomUUID(),
                regionId = regionId,
                raterId = actor.playerId,
                rating = value,
                comment = existing?.comment,
                createdAt = existing?.createdAt ?: java.time.Instant.now()
            ))
            auditService?.append(AuditEntries.operationResult(request, AuditStatus.COMPLETED, targetCount = 1))
            OperationResult.success(saved, if (existing == null) "CREATED" else "UPDATED")
        } catch (failure: Exception) {
            val detail = failure.message ?: "RATING_PERSISTENCE_FAILED"
            auditService?.append(AuditEntries.operationResult(request, AuditStatus.FAILED, detail, 1))
            OperationResult.rejected(OperationStatus.FAILED, detail)
        }
    }
}
