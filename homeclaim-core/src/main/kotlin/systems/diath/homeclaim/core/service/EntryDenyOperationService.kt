package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.EntryDenyId
import systems.diath.homeclaim.core.model.EntryDenyRule
import systems.diath.homeclaim.core.model.EntryDenyTargetType
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.HomeClaimOperationActions
import java.time.Instant
import java.util.UUID

/** One actor-aware domain path for entry-deny rule changes and moderation. */
class EntryDenyOperationService(
    private val entryDenies: EntryDenyService,
    private val regions: RegionService,
    private val audit: AuditService? = null,
    private val authorizer: RegionMutationAuthorizer = RegionMutationAuthorizer.SYSTEM_ONLY
) {
    fun create(
        context: RegionMutationContext,
        regionId: RegionId,
        targetType: EntryDenyTargetType,
        targetValue: String,
        reason: String,
        expiresAt: Instant? = null
    ): OperationResult<EntryDenyRule> {
        val region = regions.getRegionById(regionId)
        val request = context.actionRequest(HomeClaimOperationActions.ENTRY_DENY_CHANGE, regionId, region, mapOf(
            "operation" to "CREATE",
            "targetType" to targetType.name,
            "targetValue" to targetValue
        ))
        audit?.append(AuditEntries.operationResult(request, AuditStatus.ATTEMPTED, targetCount = 1))
        if (region == null) return reject(request, OperationStatus.NOT_FOUND, "REGION_NOT_FOUND")
        if (!authorizer.isAuthorized(context.actor, region, RegionMutationCapability.ENTRY_DENY_CHANGE)) {
            return reject(request, OperationStatus.DENIED, "ENTRY_DENY_CHANGE_DENIED")
        }
        if (reason.isBlank()) return reject(request, OperationStatus.INVALID, "ENTRY_DENY_REASON_REQUIRED")
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            return reject(request, OperationStatus.INVALID, "ENTRY_DENY_EXPIRY_MUST_BE_IN_FUTURE")
        }
        return try {
            val rule = entryDenies.createRule(regionId, targetType, targetValue, reason, legacyActorId(context.actor), expiresAt)
            audit?.append(AuditEntries.operationResult(request.copy(context = request.context.copy(
                attributes = request.context.attributes + ("entryDenyId" to rule.id.value.toString())
            )), AuditStatus.COMPLETED, targetCount = 1))
            OperationResult.success(rule)
        } catch (failure: IllegalArgumentException) {
            reject(request, OperationStatus.INVALID, failure.message ?: "INVALID_ENTRY_DENY")
        } catch (failure: Exception) {
            reject(request, OperationStatus.FAILED, failure.message ?: "ENTRY_DENY_CREATE_FAILED")
        }
    }

    fun report(
        context: RegionMutationContext,
        regionId: RegionId,
        id: EntryDenyId,
        reason: String
    ): OperationResult<EntryDenyRule> = mutateExisting(context, regionId, id, "REPORT", reason) { actorId ->
        entryDenies.reportRule(id, actorId, reason)
    }

    fun revoke(
        context: RegionMutationContext,
        regionId: RegionId,
        id: EntryDenyId,
        reason: String
    ): OperationResult<EntryDenyRule> = mutateExisting(context, regionId, id, "REVOKE", reason) { actorId ->
        entryDenies.revokeRule(id, actorId, reason)
    }

    private fun mutateExisting(
        context: RegionMutationContext,
        regionId: RegionId,
        id: EntryDenyId,
        operation: String,
        reason: String,
        mutate: (PlayerId) -> Boolean
    ): OperationResult<EntryDenyRule> {
        val current = entryDenies.getRule(id)
            ?: return reject(context.actionRequest(HomeClaimOperationActions.ENTRY_DENY_CHANGE, regionId,
                regions.getRegionById(regionId), mapOf("operation" to operation, "entryDenyId" to id.value.toString())),
                OperationStatus.NOT_FOUND, "ENTRY_DENY_NOT_FOUND")
        val region = regions.getRegionById(regionId)
        val request = context.actionRequest(HomeClaimOperationActions.ENTRY_DENY_CHANGE, regionId, region, mapOf(
            "operation" to operation,
            "entryDenyId" to id.value.toString()
        ))
        audit?.append(AuditEntries.operationResult(request, AuditStatus.ATTEMPTED, targetCount = 1))
        if (current.regionId != regionId) return reject(request, OperationStatus.NOT_FOUND, "ENTRY_DENY_NOT_FOUND")
        if (region == null) return reject(request, OperationStatus.NOT_FOUND, "REGION_NOT_FOUND")
        if (!authorizer.isAuthorized(context.actor, region, RegionMutationCapability.ENTRY_DENY_CHANGE)) {
            return reject(request, OperationStatus.DENIED, "ENTRY_DENY_CHANGE_DENIED")
        }
        if (operation == "REPORT" && reason.isBlank()) return reject(request, OperationStatus.INVALID, "ENTRY_DENY_REPORT_REASON_REQUIRED")
        return try {
            if (!mutate(legacyActorId(context.actor))) return reject(request, OperationStatus.CONFLICT, "ENTRY_DENY_ALREADY_UPDATED")
            val updated = entryDenies.getRule(id)
                ?: return reject(request, OperationStatus.FAILED, "ENTRY_DENY_NOT_VISIBLE_AFTER_UPDATE")
            audit?.append(AuditEntries.operationResult(request, AuditStatus.COMPLETED, targetCount = 1))
            OperationResult.success(updated)
        } catch (failure: IllegalArgumentException) {
            reject(request, OperationStatus.INVALID, failure.message ?: "INVALID_ENTRY_DENY")
        } catch (failure: Exception) {
            reject(request, OperationStatus.FAILED, failure.message ?: "ENTRY_DENY_UPDATE_FAILED")
        }
    }

    private fun legacyActorId(actor: ActionActor): PlayerId = when (actor) {
        is ActionActor.Player -> actor.playerId
        is ActionActor.Entity -> actor.entityId
        is ActionActor.Automation -> UUID.nameUUIDFromBytes("${actor.id}:${actor.source.orEmpty()}".toByteArray())
        is ActionActor.System -> UUID(0, 0) // Legacy schema stores a UUID; typed audit retains SYSTEM identity.
    }

    private fun <T> reject(
        request: systems.diath.homeclaim.core.policy.ActionRequest,
        status: OperationStatus,
        detail: String
    ): OperationResult<T> {
        audit?.append(AuditEntries.operationResult(
            request,
            if (status == OperationStatus.DENIED) AuditStatus.DENIED else AuditStatus.FAILED,
            error = detail,
            targetCount = if (request.regionId == null) null else 1
        ))
        return OperationResult.rejected(status, detail)
    }
}
