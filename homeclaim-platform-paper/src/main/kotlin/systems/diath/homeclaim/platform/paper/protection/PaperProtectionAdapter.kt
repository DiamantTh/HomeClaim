package systems.diath.homeclaim.platform.paper.protection

import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.Event
import org.bukkit.projectiles.BlockProjectileSource
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.platform.PolicyGuard
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.ActionContext
import systems.diath.homeclaim.core.policy.ActionEndpoint
import systems.diath.homeclaim.core.policy.ActionKey
import systems.diath.homeclaim.core.policy.ActionRequest
import systems.diath.homeclaim.core.policy.BulkActionRequest
import systems.diath.homeclaim.core.policy.ModPolicyDefaults
import systems.diath.homeclaim.core.policy.CorrelationId
import systems.diath.homeclaim.core.policy.Decision
import systems.diath.homeclaim.core.policy.DecisionEffect
import systems.diath.homeclaim.core.service.AuditEntries
import systems.diath.homeclaim.core.service.AuditPolicy
import systems.diath.homeclaim.core.service.AuditService

/** Paper-only event translation and reaction; all rights and policy precedence remain in core. */
internal class PaperProtectionAdapter(
    private val policyGuard: PolicyGuard,
    private val auditService: AuditService?
) {
    fun request(
        actor: ActionActor,
        action: ActionKey,
        source: Position? = null,
        target: Position,
        event: String,
        playerName: String? = null,
        attributes: Map<String, String> = emptyMap(),
        correlationId: CorrelationId = CorrelationId.newId()
    ): ActionRequest = ActionRequest(
        actor = actor,
        actionKey = action,
        source = source?.let { policyGuard.resolveEndpointAt(it) },
        target = policyGuard.resolveEndpointAt(target),
        context = ActionContext(
            platform = "paper",
            eventName = event,
            playerName = playerName,
            attributes = attributes,
            modPolicyDefaults = nativePolicyDefaults(actor)
        ),
        correlationId = correlationId
    )

    fun check(request: ActionRequest, checkBothEnds: Boolean = true): Decision =
        if (checkBothEnds && request.source != null) policyGuard.checkAcross(request) else policyGuard.check(request)

    fun resolveEndpoint(position: Position): ActionEndpoint = policyGuard.resolveEndpointAt(position)

    fun bulkRequest(
        actor: ActionActor,
        action: ActionKey,
        source: Position? = null,
        targets: List<Position>,
        event: String,
        attributes: Map<String, String> = emptyMap(),
        correlationId: CorrelationId = CorrelationId.newId()
    ): BulkActionRequest = BulkActionRequest(
        action = ActionRequest(
            actor = actor,
            actionKey = action,
            source = source?.let(policyGuard::resolveEndpointAt),
            target = null,
            context = ActionContext(
                platform = "paper",
                eventName = event,
                attributes = attributes,
                modPolicyDefaults = nativePolicyDefaults(actor)
            ),
            correlationId = correlationId
        ),
        targets = targets.map(policyGuard::resolveEndpointAt)
    )

    fun checkBulk(request: BulkActionRequest) = policyGuard.checkBulk(request)

    fun auditDenied(request: ActionRequest, decision: Decision) {
        if (decision.effect != DecisionEffect.DENY) return
        append(AuditEntries.actionResult(request, decision, systems.diath.homeclaim.core.service.AuditStatus.DENIED))
    }

    fun auditBulk(request: BulkActionRequest, result: systems.diath.homeclaim.core.policy.BulkActionResult) {
        AuditEntries.bulkActionResult(request, result)?.let(::append)
    }

    fun audit(entry: systems.diath.homeclaim.core.service.AuditEntry) = append(entry)

    fun deny(player: Player, decision: Decision) {
        val message = if (decision.detail == null) {
            protectionI18n.msg("policy.denied", policyReasonMessage(decision.reason))
        } else {
            protectionI18n.msg("policy.denied.detail", policyReasonMessage(decision.reason), decision.detail)
        }
        player.sendMessage(message)
        player.sendActionBar(Component.text(message))
    }

    private fun append(entry: systems.diath.homeclaim.core.service.AuditEntry) {
        if (AuditPolicy.shouldPersist(entry)) auditService?.append(entry)
    }
}

/** Native Paper observations proceed to resource flags; explicit region metadata can still deny them. */
private fun nativePolicyDefaults(actor: ActionActor): ModPolicyDefaults? = when (actor) {
    is ActionActor.System -> ModPolicyDefaults(allowServerTasks = true)
    is ActionActor.Automation -> if (actor.source?.startsWith("minecraft:") == true) {
        ModPolicyDefaults(allowAutomation = true)
    } else null
    is ActionActor.Player, is ActionActor.Entity -> null
}

internal fun Location.toHomeClaimPosition(): Position = Position(
    worldId = world?.uid?.let(::WorldId) ?: WorldId.fromLegacyName(world?.name ?: "unknown"),
    x = blockX,
    y = blockY,
    z = blockZ,
    legacyWorldName = world?.name
)

internal fun Block.toHomeClaimPosition(): Position = location.toHomeClaimPosition()

internal fun Entity.toActionActor(): ActionActor = when (this) {
    is Player -> ActionActor.Player(uniqueId)
    else -> ActionActor.Entity(uniqueId, type.name)
}

/** Keeps a projectile's cause as its shooter where Paper exposes one. */
internal fun Entity.protectionActionActor(): ActionActor = when (this) {
    is Projectile -> when (val source = shooter) {
        is Entity -> source.toActionActor()
        is BlockProjectileSource -> ActionActor.Automation(
            "dispenser:${source.block.toHomeClaimPosition()}",
            "minecraft:dispenser"
        )
        else -> toActionActor()
    }
    else -> toActionActor()
}

/** Uses the causal shooter's location when available, otherwise the projectile's own location. */
internal fun Entity.protectionActionSource(): Position = when (this) {
    is Projectile -> when (val source = shooter) {
        is Entity -> source.location.toHomeClaimPosition()
        is BlockProjectileSource -> source.block.toHomeClaimPosition()
        else -> location.toHomeClaimPosition()
    }
    else -> location.toHomeClaimPosition()
}

internal fun Block.relativePosition(face: org.bukkit.block.BlockFace): Position = getRelative(face).toHomeClaimPosition()

internal inline fun <E : Event> protectionEvent(event: E, name: String, action: () -> Unit) =
    systems.diath.homeclaim.platform.paper.util.SafeEventHandler.handle(event, name, block = action)

private val protectionI18n = systems.diath.homeclaim.platform.paper.I18n()

private fun policyReasonMessage(reason: String): String = when (reason) {
    systems.diath.homeclaim.core.policy.DecisionReason.NO_REGION -> protectionI18n.msg("policy.reason.no_region")
    systems.diath.homeclaim.core.policy.DecisionReason.NO_PERMISSION -> protectionI18n.msg("policy.reason.no_permission")
    systems.diath.homeclaim.core.policy.DecisionReason.FLAG_DENY -> protectionI18n.msg("policy.reason.flag_deny")
    systems.diath.homeclaim.core.policy.DecisionReason.ROLE_BANNED -> protectionI18n.msg("policy.reason.role_banned")
    systems.diath.homeclaim.core.policy.DecisionReason.COOLDOWN_ACTIVE -> protectionI18n.msg("policy.reason.cooldown_active")
    systems.diath.homeclaim.core.policy.DecisionReason.ROLE_REQUIRED -> protectionI18n.msg("policy.reason.role_required")
    systems.diath.homeclaim.core.policy.DecisionReason.REDSTONE_DENY -> protectionI18n.msg("policy.reason.redstone_deny")
    systems.diath.homeclaim.core.policy.DecisionReason.MOD_ACTOR_DENY -> protectionI18n.msg("policy.reason.mod_actor_deny")
    systems.diath.homeclaim.core.policy.DecisionReason.ENTRY_DENY -> protectionI18n.msg("policy.reason.entry_deny")
    else -> protectionI18n.msg("policy.reason.unknown")
}
