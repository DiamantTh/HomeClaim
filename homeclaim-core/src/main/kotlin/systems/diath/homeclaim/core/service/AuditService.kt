package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.RegionRole
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.permission.PermissionResult
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.ActionEndpoint
import systems.diath.homeclaim.core.policy.ActionKey
import systems.diath.homeclaim.core.policy.HomeClaimOperationActions
import systems.diath.homeclaim.core.policy.ActionRequest
import systems.diath.homeclaim.core.policy.CorrelationId
import systems.diath.homeclaim.core.policy.Decision
import systems.diath.homeclaim.core.policy.DecisionEffect
import java.time.Instant
import java.util.UUID

enum class AuditStatus { ATTEMPTED, ALLOWED, DENIED, COMPLETED, FAILED, ROLLED_BACK, ACKNOWLEDGED }

/** A sink is a persistence or output boundary; HomeClaim's JDBC sink is canonical. */
fun interface AuditSink {
    fun append(entry: AuditEntry)
}

interface AuditService : AuditSink

/** Suppresses repository-level compatibility audit while an actor-aware operation writes its lifecycle. */
object MutationAuditScope {
    private val depth = ThreadLocal.withInitial { 0 }
    val active: Boolean get() = depth.get() > 0

    fun <T> withinOperation(block: () -> T): T {
        depth.set(depth.get() + 1)
        return try {
            block()
        } finally {
            val next = depth.get() - 1
            if (next == 0) depth.remove() else depth.set(next)
        }
    }
}

data class AuditEntry(
    val actorId: PlayerId?,
    val targetId: UUID?,
    val category: String,
    val action: String,
    val payload: Map<String, Any?> = emptyMap(),
    val createdAt: Instant = Instant.now(),
    val auditId: UUID = UUID.randomUUID(),
    val correlationId: CorrelationId? = null,
    val parentCorrelationId: CorrelationId? = null,
    val actorKind: String? = null,
    val actorRef: String? = null,
    val actionKey: ActionKey? = null,
    val worldId: WorldId? = null,
    val areaId: AreaId? = null,
    val source: ActionEndpoint? = null,
    val target: ActionEndpoint? = null,
    val role: RegionRole? = null,
    val policyResult: DecisionEffect? = null,
    val permissionResult: PermissionResult? = null,
    val bypass: String? = null,
    val reason: String? = null,
    val status: AuditStatus = AuditStatus.COMPLETED,
    val targetCount: Int? = null,
    val error: String? = null
) {
    companion object {
        fun fromDecision(
            request: ActionRequest,
            decision: Decision,
            status: AuditStatus,
            error: String? = null,
            targetCount: Int? = null,
            createdAt: Instant = Instant.now()
        ): AuditEntry {
            val actor = request.actor as? ActionActor.Player
            return AuditEntry(
                actorId = actor?.playerId,
                targetId = (request.target?.resource as? systems.diath.homeclaim.core.policy.ActionResourceRef.Region)
                    ?.regionId?.value ?: decision.context.regionId?.value,
                category = AuditTaxonomy.Category.REGION,
                action = request.actionKey.value,
                payload = mapOf(
                    "decisionReason" to decision.reason,
                    "decisionDetail" to decision.detail,
                    "decisionSource" to decision.source.name,
                    "platform" to request.context.platform,
                    "event" to request.context.eventName,
                    "playerName" to request.context.playerName
                ) + request.context.attributes,
                createdAt = createdAt,
                correlationId = request.correlationId,
                parentCorrelationId = request.parentCorrelationId,
                actorKind = request.actor.kind,
                actorRef = request.actor.stableId,
                actionKey = request.actionKey,
                worldId = decision.context.world ?: request.worldId,
                areaId = decision.context.areaId,
                source = request.source,
                target = request.target,
                role = decision.trace.role?.let { runCatching { RegionRole.valueOf(it) }.getOrNull() },
                policyResult = decision.effect,
                permissionResult = decision.trace.permissionResult,
                bypass = decision.trace.bypass,
                reason = decision.reason,
                status = status,
                targetCount = targetCount,
                error = error
            )
        }
    }
}

object AuditPolicy {
    /** Excludes out-of-scope and routine allowed movement; persists denials and actual mutations. */
    fun shouldPersist(entry: AuditEntry): Boolean {
        if (entry.policyResult == DecisionEffect.NOT_APPLICABLE) return false
        val key = entry.actionKey?.value
        val movement = key == "homeclaim:region_enter" || key == "homeclaim:region_teleport_enter"
        if (movement && entry.status == AuditStatus.ALLOWED) return false
        if (entry.status == AuditStatus.DENIED) return true
        if (entry.status == AuditStatus.ALLOWED && key in ADMIN_ACTIONS) return true
        if (entry.status == AuditStatus.ALLOWED && key == "homeclaim:region_explosion" && (entry.targetCount ?: 0) > 0) return true
        return entry.status in setOf(AuditStatus.ATTEMPTED, AuditStatus.COMPLETED, AuditStatus.FAILED,
            AuditStatus.ROLLED_BACK, AuditStatus.ACKNOWLEDGED) && ((ActionKey.definitionOrNull(entry.actionKey ?: return true)?.mutating ?: true) ||
            key in ADMIN_ACTIONS)
    }

    private val ADMIN_ACTIONS = setOf(
        HomeClaimOperationActions.REGION_CLAIM.value,
        HomeClaimOperationActions.REGION_MERGE.value,
        HomeClaimOperationActions.REGION_UNLINK.value,
        HomeClaimOperationActions.REGION_RESET.value,
        HomeClaimOperationActions.REGION_BACKUP_CREATE.value,
        HomeClaimOperationActions.REGION_RESTORE.value,
        HomeClaimOperationActions.REGION_COPY.value,
        HomeClaimOperationActions.REGION_MOVE.value,
        HomeClaimOperationActions.REGION_SWAP.value,
        HomeClaimOperationActions.REGION_RECOVERY.value,
        HomeClaimOperationActions.REGION_RECOVERY_ACKNOWLEDGE.value,
        HomeClaimOperationActions.REGION_HOME_SET.value,
        HomeClaimOperationActions.REGION_UNCLAIM.value,
        HomeClaimOperationActions.REGION_BIOME_CHANGE.value,
        HomeClaimOperationActions.REGION_RATING.value,
        HomeClaimOperationActions.OWNERSHIP_TRANSFER.value,
        HomeClaimOperationActions.ROLE_CHANGE.value,
        HomeClaimOperationActions.FLAG_CHANGE.value,
        HomeClaimOperationActions.LIMIT_CHANGE.value,
        HomeClaimOperationActions.PROFILE_CHANGE.value,
        HomeClaimOperationActions.COMPONENT_CHANGE.value,
        HomeClaimOperationActions.ENTRY_DENY_CHANGE.value,
        HomeClaimOperationActions.PRICE_CHANGE.value,
        HomeClaimOperationActions.METADATA_CHANGE.value
    )
}
