package systems.diath.homeclaim.core.service

import java.util.UUID
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.policy.Decision
import systems.diath.homeclaim.core.policy.ActionRequest
import systems.diath.homeclaim.core.policy.BulkActionRequest
import systems.diath.homeclaim.core.policy.BulkActionResult
import systems.diath.homeclaim.core.policy.DecisionEffect
import systems.diath.homeclaim.core.policy.ActionActor

/**
 * Factory helpers for canonical audit entries.
 */
object AuditEntries {
    fun denied(
        actorId: PlayerId?,
        targetId: UUID? = null,
        category: String,
        action: String,
        position: Position,
        decision: Decision,
        extra: Map<String, Any?> = emptyMap()
    ): AuditEntry {
        val payload = AuditPayloads.deniedPolicyPayload(position, decision, extra)
        val actionRequest = decision.context.actionRequest
        val typed = actionRequest?.let { AuditEntry.fromDecision(it, decision, AuditStatus.DENIED) }
        return (typed ?: AuditEntry(
            actorId = actorId,
            targetId = targetId,
            category = category,
            action = action,
            payload = payload
        )).copy(
            actorId = actorId,
            targetId = targetId ?: typed?.targetId,
            category = category,
            action = action,
            payload = payload,
            status = AuditStatus.DENIED,
            policyResult = decision.effect,
            reason = decision.reason
        )
    }

    fun actionResult(
        request: ActionRequest,
        decision: Decision,
        status: AuditStatus,
        error: String? = null
    ): AuditEntry = AuditEntry.fromDecision(
        decision.context.actionRequest ?: request,
        decision,
        status,
        error
    )

    /** Lifecycle hook for operations whose authorization/execution is still handled by legacy services. */
    fun operationResult(
        request: ActionRequest,
        status: AuditStatus,
        error: String? = null,
        targetCount: Int? = null
    ): AuditEntry {
        val actor = request.actor as? ActionActor.Player
        val targetId = request.target?.resource?.id
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        return AuditEntry(
            actorId = actor?.playerId,
            targetId = targetId,
            category = "OPERATION",
            action = request.actionKey.value,
            payload = request.context.attributes + mapOf(
                "platform" to request.context.platform,
                "event" to request.context.eventName
            ),
            correlationId = request.correlationId,
            parentCorrelationId = request.parentCorrelationId,
            actorKind = request.actor.kind,
            actorRef = request.actor.stableId,
            actionKey = request.actionKey,
            worldId = request.worldId,
            areaId = request.areaId ?: request.target?.areaId,
            source = request.source,
            target = request.target,
            status = status,
            targetCount = targetCount,
            error = error
        )
    }

    /** A bulk operation has one lifecycle record; individual targets remain available on the result. */
    fun bulkActionResult(request: BulkActionRequest, result: BulkActionResult): AuditEntry? {
        val representative = result.decisions.firstOrNull { it.effect == DecisionEffect.DENY }
            ?: result.decisions.firstOrNull()
            ?: return null
        // Bulk events are observed during authorization, before Paper applies the resulting
        // mutation. Record ALLOWED here; a listener cannot claim completion at this point.
        val status = if (result.deniedCount > 0) AuditStatus.DENIED else AuditStatus.ALLOWED
        val entry = AuditEntry.fromDecision(
            representative.context.actionRequest
                ?: request.action.copy(correlationId = request.correlationId),
            representative,
            status,
            targetCount = result.targetCount
        )
        return entry.copy(payload = entry.payload + mapOf(
            "targetCount" to result.targetCount,
            "allowedCount" to result.allowedCount,
            "deniedCount" to result.deniedCount,
            "notApplicableCount" to result.notApplicableCount
        ))
    }

    fun regionCreated(actorId: PlayerId?, targetId: UUID, world: String, bounds: String, shape: String): AuditEntry {
        return AuditEntry(
            actorId = actorId,
            targetId = targetId,
            category = AuditTaxonomy.Category.REGION,
            action = AuditTaxonomy.Action.CREATED,
            payload = AuditPayloads.worldPayload(world, extra = mapOf("bounds" to bounds, "shape" to shape))
        )
    }

    fun regionUpdated(actorId: PlayerId?, targetId: UUID, world: String, owner: String, ownerChanged: Boolean? = null): AuditEntry {
        val extra = mutableMapOf<String, Any?>("owner" to owner)
        if (ownerChanged != null) extra["ownerChanged"] = ownerChanged
        return AuditEntry(
            actorId = actorId,
            targetId = targetId,
            category = AuditTaxonomy.Category.REGION,
            action = AuditTaxonomy.Action.UPDATED,
            payload = AuditPayloads.worldPayload(world, extra = extra)
        )
    }

    fun regionDeleted(actorId: PlayerId?, targetId: UUID, world: String, owner: String): AuditEntry {
        return AuditEntry(
            actorId = actorId,
            targetId = targetId,
            category = AuditTaxonomy.Category.REGION,
            action = AuditTaxonomy.Action.DELETED,
            payload = AuditPayloads.worldPayload(world, extra = mapOf("owner" to owner))
        )
    }

    fun profileApplied(targetId: UUID, profileName: String, flagCount: Int, limitCount: Int): AuditEntry {
        return AuditEntry(
            actorId = null,
            targetId = targetId,
            category = AuditTaxonomy.Category.PROFILE,
            action = AuditTaxonomy.Action.APPLIED,
            payload = mapOf("profileName" to profileName, "flagCount" to flagCount, "limitCount" to limitCount)
        )
    }

    fun flagUpsert(targetId: UUID, key: String, value: String): AuditEntry {
        return AuditEntry(
            actorId = null,
            targetId = targetId,
            category = AuditTaxonomy.Category.FLAG,
            action = AuditTaxonomy.Action.UPSERT,
            payload = mapOf("key" to key, "value" to value)
        )
    }

    fun limitUpsert(targetId: UUID, key: String, value: String): AuditEntry {
        return AuditEntry(
            actorId = null,
            targetId = targetId,
            category = AuditTaxonomy.Category.LIMIT,
            action = AuditTaxonomy.Action.UPSERT,
            payload = mapOf("key" to key, "value" to value)
        )
    }

    fun componentUsed(
        actorId: PlayerId?,
        targetId: UUID,
        action: String,
        position: Position,
        platform: String,
        extra: Map<String, Any?> = emptyMap()
    ): AuditEntry {
        return AuditEntry(
            actorId = actorId,
            targetId = targetId,
            category = AuditTaxonomy.Category.COMPONENT,
            action = action,
            payload = AuditPayloads.actionPayload(position, platform, extra)
        )
    }

    fun plotImported(
        actorId: PlayerId?,
        targetId: UUID,
        world: String,
        platform: String,
        source: String,
        originalId: String
    ): AuditEntry {
        return AuditEntry(
            actorId = actorId,
            targetId = targetId,
            category = AuditTaxonomy.Category.IMPORT,
            action = AuditTaxonomy.Action.PLOT_IMPORTED,
            payload = AuditPayloads.worldPayload(
                world = world,
                platform = platform,
                extra = mapOf("source" to source, "originalId" to originalId)
            )
        )
    }
}
