package systems.diath.homeclaim.platform.paper

import systems.diath.homeclaim.core.model.ComponentState
import systems.diath.homeclaim.core.model.ComponentType
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.platform.ComponentTriggerHandler
import systems.diath.homeclaim.core.platform.ComponentTriggerRequest
import systems.diath.homeclaim.core.policy.ActorKind
import systems.diath.homeclaim.core.policy.DecisionReason
import systems.diath.homeclaim.core.policy.PolicyActorContext
import systems.diath.homeclaim.core.service.AuditEntries
import systems.diath.homeclaim.core.service.AuditPolicy
import systems.diath.homeclaim.core.service.AuditService
import systems.diath.homeclaim.core.service.AuditStatus
import systems.diath.homeclaim.core.service.AuditTaxonomy
import systems.diath.homeclaim.core.service.ComponentService
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.liftlink.LiftLinkPlanner
import systems.diath.homeclaim.platform.paper.util.SafeEventHandler
import systems.diath.homeclaim.platform.paper.protection.toHomeClaimPosition
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action as BlockAction
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.plugin.java.JavaPlugin
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import systems.diath.homeclaim.platform.paper.I18n
import systems.diath.homeclaim.core.policy.Decision

class PaperLiftLinkListener(
    private val componentTriggerHandler: ComponentTriggerHandler,
    private val componentService: ComponentService,
    private val regionService: RegionService,
    private val planner: LiftLinkPlanner,
    private val plugin: JavaPlugin,
    private val auditService: AuditService? = null
) : Listener {
    private val i18n = I18n()

    private val triggerBlocks = setOf(
        Material.LIGHT_WEIGHTED_PRESSURE_PLATE,
        Material.HEAVY_WEIGHTED_PRESSURE_PLATE,
        Material.STONE_PRESSURE_PLATE,
        Material.POLISHED_BLACKSTONE_PRESSURE_PLATE
    )

    @EventHandler(ignoreCancelled = true)
    fun onInteract(e: PlayerInteractEvent) = SafeEventHandler.handle(e, "LiftLink.onInteract") {
        if (e.action != BlockAction.PHYSICAL) return@handle
        val block = e.clickedBlock ?: return@handle
        if (!triggerBlocks.contains(block.type)) return@handle
        val player = e.player
        val pos = block.toHomeClaimPosition()
        val comp = componentService.getComponentAt(pos) ?: return@handle
        if (comp.state != ComponentState.ENABLED) return@handle
        when (comp.type) {
            ComponentType.ELEVATOR_PAD -> handleElevator(player, e, comp, pos)
            ComponentType.TELEPORT_PAD -> handleTeleport(player, e, comp, pos)
        }
    }

    private fun systems.diath.homeclaim.core.model.Position.toCenterLocation(world: org.bukkit.World) =
        org.bukkit.Location(world, x.toDouble() + 0.5, y.toDouble().coerceAtLeast(0.0), z.toDouble() + 0.5)
    
    private fun resolveDestination(
        player: Player,
        target: Position,
        permission: String,
        permissionMessage: String
    ): LiftLinkDestination? {
        val targetWorld = target.toPaperWorld()
        val targetWorldName = target.displayWorldName()
        if (targetWorld == null) {
            player.sendMessage(i18n.msg("liftlink.target.world.not.exists", targetWorldName))
            return null
        }

        val crossWorld = targetWorld.uid != player.world.uid
        if (crossWorld && !player.hasPermission(permission)) {
            player.sendMessage(i18n.msg(permissionMessage))
            return null
        }
        return LiftLinkDestination(
            location = target.toCenterLocation(targetWorld),
            worldName = targetWorld.name,
            crossWorld = crossWorld
        )
    }

    private data class LiftLinkDestination(
        val location: org.bukkit.Location,
        val worldName: String,
        val crossWorld: Boolean
    )

    private fun Position.displayWorldName(): String = legacyWorldName ?: worldId.value.toString()

    private fun Position.toPaperWorld(): World? =
        org.bukkit.Bukkit.getWorld(worldId.value) ?: legacyWorldName?.let { org.bukkit.Bukkit.getWorld(it) }

    private fun systems.diath.homeclaim.core.model.Component.elevatorConfig(): systems.diath.homeclaim.core.model.ElevatorConfig? {
        return (this.config as? systems.diath.homeclaim.core.model.ElevatorConfig)
    }

    private fun systems.diath.homeclaim.core.model.Component.teleportConfig(): systems.diath.homeclaim.core.model.TeleportConfig? {
        return (this.config as? systems.diath.homeclaim.core.model.TeleportConfig)
    }

    private fun handleElevator(player: Player, e: PlayerInteractEvent, comp: systems.diath.homeclaim.core.model.Component, pos: Position) {
        val decision = checkComponentUse(player, comp, pos) ?: return
        if (decision.effect == systems.diath.homeclaim.core.policy.DecisionEffect.DENY) {
            player.sendMessage(policyMessage(decision.reason, decision.detail))
            e.isCancelled = true
            return
        }
        val config = comp.elevatorConfig() ?: return
        val nearby = planner.findNearbyPads(comp.regionId, pos, config).filter { it.id != comp.id }
        if (nearby.isEmpty()) {
            player.sendMessage(i18n.msg("liftlink.elevator.no.target"))
            return
        }
        val target = nearby.first()
        
        val destination = resolveDestination(
            player,
            target.position,
            "homeclaim.elevator.crossworld",
            "liftlink.elevator.no.crossworld.perm"
        )
        if (destination == null) {
            e.isCancelled = true
            return
        }
        val successMessage = if (destination.crossWorld) {
            i18n.msg("liftlink.teleport.arrow") + " ${destination.worldName}"
        } else {
            i18n.msg("liftlink.teleported")
        }
        completeComponentTeleport(
            player, e, comp, pos, decision, destination.location, AuditTaxonomy.Action.ELEVATOR_USED,
            successMessage,
            mapOf("sourceId" to comp.id.value.toString(), "targetId" to target.id.value.toString(),
                "mode" to config.mode.name, "distance" to (target.position.y - pos.y))
        )
    }

    private fun handleTeleport(player: Player, e: PlayerInteractEvent, comp: systems.diath.homeclaim.core.model.Component, pos: Position) {
        val config = comp.teleportConfig() ?: return
        
        if (config.combatBlock == true && CombatTracker.isInCombat(player)) {
            val remaining = CombatTracker.getRemainingCombatMs(player) / 1000
            player.sendMessage(i18n.msg("liftlink.teleport.combat.blocked", remaining.toString()))
            e.isCancelled = true
            return
        }
        
        val decision = checkComponentUse(player, comp, pos) ?: return
        if (decision.effect == systems.diath.homeclaim.core.policy.DecisionEffect.DENY) {
            player.sendMessage(policyMessage(decision.reason, decision.detail))
            e.isCancelled = true
            return
        }

        // Reuse the central policy decision's role result for the sensor behavior.
        // This keeps component use and teleport behavior on the same membership resolver.
        val isVisitor = decision.trace.role == systems.diath.homeclaim.core.model.RegionRole.VISITOR.name
        if (isVisitor) {
            val sensorBlock = e.clickedBlock
            if (sensorBlock != null && BlockSensorRegistry.isValidSensor(sensorBlock)) {
                if (!BlockSensorRegistry.hasActiveSignal(sensorBlock)) {
                    player.sendMessage(i18n.msg("liftlink.teleport.unavailable"))
                    e.isCancelled = true
                    return
                }
            }
        }
        
        val targets = resolveTeleportTargets(comp, config)
        if (targets.isEmpty()) {
            player.sendMessage(i18n.msg("liftlink.teleport.no.target"))
            return
        }
        
        val destComp = targets.first()
        
        val destination = resolveDestination(
            player,
            destComp.position,
            "homeclaim.teleporter.crossworld",
            "liftlink.teleport.no.crossworld.perm"
        )
        if (destination == null) {
            e.isCancelled = true
            return
        }
        val successMessage = if (destination.crossWorld) {
            i18n.msg("liftlink.teleport.arrow") + " ${destination.worldName}"
        } else {
            i18n.msg("teleport.actionbar")
        }
        completeComponentTeleport(
            player, e, comp, pos, decision, destination.location, AuditTaxonomy.Action.TELEPORT_USED,
            successMessage,
            mapOf("sourceId" to comp.id.value.toString(), "targetId" to destComp.id.value.toString(),
                "linkId" to config.linkId.toString(), "scope" to config.withinScope.name)
        )
    }

    private fun completeComponentTeleport(
        player: Player,
        event: PlayerInteractEvent,
        component: systems.diath.homeclaim.core.model.Component,
        position: Position,
        decision: Decision,
        destination: org.bukkit.Location,
        action: String,
        successMessage: String,
        extra: Map<String, Any?>
    ) {
        event.isCancelled = true
        val future = runCatching { player.teleportAsync(destination) }
            .getOrElse { java.util.concurrent.CompletableFuture.failedFuture(it) }
        future.whenComplete { success, error ->
            val completion = TeleportCompletion.resolve(success, error)
            val succeeded = completion == TeleportCompletion.Succeeded
            val request = decision.context.actionRequest
            val failureDetail = (completion as? TeleportCompletion.Failed)?.detail
            val auditEntry = AuditEntries.componentUsed(
                actorId = player.uniqueId,
                targetId = component.id.value,
                action = action,
                position = position,
                platform = "paper",
                extra = extra + mapOf("result" to if (succeeded) "COMPLETED" else "FAILED")
            ).copy(
                correlationId = request?.correlationId,
                parentCorrelationId = request?.parentCorrelationId,
                actorKind = "PLAYER",
                actorRef = player.uniqueId.toString(),
                actionKey = request?.actionKey,
                worldId = decision.context.world ?: request?.worldId,
                areaId = decision.context.areaId ?: request?.areaId,
                source = request?.source,
                target = request?.target,
                role = decision.trace.role?.let { runCatching { systems.diath.homeclaim.core.model.RegionRole.valueOf(it) }.getOrNull() },
                policyResult = decision.effect,
                permissionResult = decision.trace.permissionResult,
                bypass = decision.trace.bypass,
                reason = decision.reason,
                status = if (succeeded) AuditStatus.COMPLETED else AuditStatus.FAILED,
                targetCount = 1,
                error = failureDetail
            )
            runCatching { auditService?.append(auditEntry) }.onFailure { auditError ->
                plugin.logger.log(java.util.logging.Level.SEVERE, "Could not audit LiftLink teleport for ${component.id.value}", auditError)
            }
            if (!succeeded) {
                plugin.logger.warning("LiftLink teleport failed for ${player.uniqueId}, component=${component.id.value}: $failureDetail")
            }
            runOnServerThread(player) {
                if (succeeded) {
                    player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(successMessage))
                } else {
                    player.sendMessage(i18n.msg("liftlink.teleport.failed"))
                }
            }
        }
    }

    private fun runOnServerThread(player: Player, action: () -> Unit) {
        val task = Runnable { if (player.isOnline) action() }
        runCatching {
            if (Bukkit.isPrimaryThread()) task.run()
            else plugin.server.scheduler.runTask(plugin, task)
        }.onFailure { error ->
            plugin.logger.log(java.util.logging.Level.WARNING, "Could not deliver LiftLink completion to ${player.uniqueId}", error)
        }
    }

    private fun checkComponentUse(
        player: Player,
        component: systems.diath.homeclaim.core.model.Component,
        position: Position
    ): systems.diath.homeclaim.core.policy.Decision? {
        val result = componentTriggerHandler.onTrigger(
            ComponentTriggerRequest(
                playerId = player.uniqueId,
                position = position,
                actor = PolicyActorContext(
                    kind = ActorKind.PLAYER,
                    actorId = player.uniqueId.toString(),
                    sourceMod = "paper"
                ),
                platform = "paper",
                extra = mapOf(
                    "event" to "PlayerInteractEvent:LiftLink",
                    "playerName" to player.name,
                    "componentType" to component.type.name
                )
            ),
            component
        )
        val decision = result.decision ?: return null
        if (decision.effect == systems.diath.homeclaim.core.policy.DecisionEffect.DENY) {
            decision.context.actionRequest?.let { request ->
                val entry = AuditEntries.actionResult(request, decision, AuditStatus.DENIED)
                if (AuditPolicy.shouldPersist(entry)) auditService?.append(entry)
            }
        }
        return decision
    }

    private fun policyMessage(reason: String, detail: String?): String {
        val reasonMessage = policyReasonMessage(reason)
        return if (detail != null) {
            i18n.msg("policy.denied.detail", reasonMessage, detail)
        } else {
            i18n.msg("policy.denied", reasonMessage)
        }
    }

    private fun policyReasonMessage(reason: String): String = when (reason) {
        DecisionReason.NO_REGION -> i18n.msg("policy.reason.no_region")
        DecisionReason.NO_PERMISSION -> i18n.msg("policy.reason.no_permission")
        DecisionReason.FLAG_DENY -> i18n.msg("policy.reason.flag_deny")
        DecisionReason.ROLE_BANNED -> i18n.msg("policy.reason.role_banned")
        DecisionReason.COOLDOWN_ACTIVE -> i18n.msg("policy.reason.cooldown_active")
        DecisionReason.ROLE_REQUIRED -> i18n.msg("policy.reason.role_required")
        DecisionReason.REDSTONE_DENY -> i18n.msg("policy.reason.redstone_deny")
        else -> i18n.msg("policy.reason.unknown")
    }

    private fun resolveTeleportTargets(
        source: systems.diath.homeclaim.core.model.Component,
        config: systems.diath.homeclaim.core.model.TeleportConfig
    ): List<systems.diath.homeclaim.core.model.Component> {
        val sameLink = componentService.listComponents(source.regionId)
            .filter { it.type == ComponentType.TELEPORT_PAD && (it.config as? systems.diath.homeclaim.core.model.TeleportConfig)?.linkId == config.linkId }
            .filter { it.id != source.id }

        val scoped = when (config.withinScope) {
            systems.diath.homeclaim.core.model.TeleportScope.REGION_ONLY -> sameLink.filter { it.regionId == source.regionId }
            systems.diath.homeclaim.core.model.TeleportScope.MERGE_GROUP -> {
                val srcRegion = regionService.getRegionById(source.regionId)
                val merge = srcRegion?.mergeGroupId
                if (merge == null) sameLink else sameLink.filter { regionService.getRegionById(it.regionId)?.mergeGroupId == merge }
            }
        }

        val targetList = if (config.linkMode == systems.diath.homeclaim.core.model.TeleportLinkMode.HUB && config.targets.isNotEmpty()) {
            val targetIds = config.targets.toSet()
            scoped.filter { targetIds.contains(it.id) }
        } else scoped

        return targetList.ifEmpty { emptyList() }
    }
}
