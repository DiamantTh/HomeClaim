package systems.diath.homeclaim.platform.paper.protection

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.policy.Action
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.ActionResourceRef
import systems.diath.homeclaim.core.policy.DecisionEffect
import systems.diath.homeclaim.core.policy.DecisionReason
import systems.diath.homeclaim.core.service.AuditEntry
import systems.diath.homeclaim.core.service.AuditTaxonomy
import systems.diath.homeclaim.core.service.AuditStatus
import systems.diath.homeclaim.platform.paper.EntryForceGrant
import systems.diath.homeclaim.platform.paper.EntryForceRegistry

internal class MovementProtectionListener(private val adapter: PaperProtectionAdapter) : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) = protectionEvent(event, "player-teleport-entry") {
        val to = event.to
        val fromPosition = event.from.toHomeClaimPosition()
        val targetPosition = to.toHomeClaimPosition()
        if (fromPosition == targetPosition || sameBoundary(adapter.resolveEndpoint(fromPosition), adapter.resolveEndpoint(targetPosition))) {
            return@protectionEvent
        }
        evaluateEntry(
            player = event.player,
            source = fromPosition,
            target = targetPosition,
            action = Action.REGION_TELEPORT_ENTER,
            eventName = "PlayerTeleportEvent",
            cause = event.cause.name,
            cancel = { event.isCancelled = true }
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) = protectionEvent(event, "player-move-entry") {
        // Vehicle movement is checked at its Paper vehicle/entity movement event instead.
        if (event.player.vehicle != null) return@protectionEvent
        val to = event.to
        val from = event.from
        if (from.world == to.world && from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) {
            return@protectionEvent
        }
        val fromPosition = from.toHomeClaimPosition()
        val targetPosition = to.toHomeClaimPosition()
        val source = adapterEndpoint(fromPosition)
        val target = adapterEndpoint(targetPosition)
        if (sameBoundary(source, target)) return@protectionEvent
        evaluateEntry(
            player = event.player,
            source = fromPosition,
            target = targetPosition,
            action = Action.REGION_ENTER,
            eventName = "PlayerMoveEvent",
            cause = null,
            cancel = { event.to = from }
        )
    }

    private fun evaluateEntry(
        player: Player,
        source: systems.diath.homeclaim.core.model.Position,
        target: systems.diath.homeclaim.core.model.Position,
        action: Action,
        eventName: String,
        cause: String?,
        cancel: () -> Unit
    ) {
        val targetEndpoint = adapterEndpoint(target)
        val request = adapter.request(
            actor = ActionActor.Player(player.uniqueId),
            action = action.key,
            source = source,
            target = target,
            event = eventName,
            playerName = player.name,
            attributes = buildMap {
                cause?.let { put("cause", it) }
                put("playerName", player.name)
            }
        ).copy(target = targetEndpoint)
        var decision = adapter.check(request, checkBothEnds = false)
        var forceGrant: EntryForceGrant? = null
        val regionId = (targetEndpoint.resource as? ActionResourceRef.Region)?.regionId
        if (decision.effect == DecisionEffect.DENY && decision.reason != DecisionReason.NO_REGION &&
            decision.reason != DecisionReason.NOT_APPLICABLE && decision.source != systems.diath.homeclaim.core.policy.DecisionSource.BYPASS
        ) {
            val grant = EntryForceRegistry.consume(player.uniqueId, regionId)
            forceGrant = grant
            if (grant != null) {
                val forcedRequest = request.copy(context = request.context.copy(
                    entryBypass = true,
                    attributes = request.context.attributes + mapOf(
                        "forceEntryGrantId" to grant.id.toString(),
                        "forceEntryGrantedBy" to grant.grantedBy.toString(),
                        "forceEntryReason" to grant.reason
                    )
                ))
                decision = adapter.check(forcedRequest, checkBothEnds = false)
                auditForceEntryUse(player, target, regionId, grant, eventName)
            }
        }
        var deniedRequest = request
        if (decision.effect != DecisionEffect.DENY && player.vehicle != null) {
            val vehicleRequest = adapter.request(
                actor = ActionActor.Player(player.uniqueId),
                action = Action.VEHICLE_MOVE.key,
                source = source,
                target = target,
                event = "$eventName:vehicle",
                playerName = player.name,
                correlationId = request.correlationId,
                attributes = mapOf("vehicleType" to player.vehicle!!.type.name)
            )
            val vehicleDecision = adapter.check(vehicleRequest)
            if (vehicleDecision.effect == DecisionEffect.DENY) {
                deniedRequest = vehicleRequest
                decision = vehicleDecision
            }
        }
        if (decision.effect == DecisionEffect.DENY) {
            cancel()
            adapter.deny(player, decision)
            adapter.auditDenied(deniedRequest, decision)
        } else if (decision.effect == DecisionEffect.ALLOW && forceGrant != null) {
            // The force grant itself is the auditable operation; routine allowed movement is filtered.
            adapter.audit(
                AuditEntry.fromDecision(request, decision, AuditStatus.ALLOWED)
            )
        }
    }

    private fun adapterEndpoint(position: systems.diath.homeclaim.core.model.Position) = adapter.resolveEndpoint(position)

    private fun sameBoundary(
        source: systems.diath.homeclaim.core.policy.ActionEndpoint,
        target: systems.diath.homeclaim.core.policy.ActionEndpoint
    ): Boolean = source.worldId == target.worldId && source.areaId == target.areaId && source.resource == target.resource

    private fun auditForceEntryUse(
        player: Player,
        position: systems.diath.homeclaim.core.model.Position,
        regionId: RegionId?,
        grant: EntryForceGrant,
        transport: String
    ) {
        adapter.audit(
            AuditEntry(
                actorId = player.uniqueId,
                targetId = regionId?.value,
                category = AuditTaxonomy.Category.REGION,
                action = AuditTaxonomy.Action.ENTRY_FORCE_USED,
                payload = mapOf(
                    "grantId" to grant.id.toString(),
                    "grantedBy" to grant.grantedBy.toString(),
                    "reason" to grant.reason,
                    "transport" to transport,
                    "world" to position.legacyWorldName,
                    "x" to position.x,
                    "y" to position.y,
                    "z" to position.z
                )
            )
        )
    }
}
