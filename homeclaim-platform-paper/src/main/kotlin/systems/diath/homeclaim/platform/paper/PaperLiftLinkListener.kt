package systems.diath.homeclaim.platform.paper

import systems.diath.homeclaim.core.model.ComponentState
import systems.diath.homeclaim.core.model.ComponentType
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.ComponentId
import systems.diath.homeclaim.core.platform.PolicyGuard
import systems.diath.homeclaim.core.policy.Action
import systems.diath.homeclaim.core.policy.DecisionReason
import systems.diath.homeclaim.core.service.AuditEntry
import systems.diath.homeclaim.core.service.AuditPayloads
import systems.diath.homeclaim.core.service.AuditService
import systems.diath.homeclaim.core.service.AuditTaxonomy
import systems.diath.homeclaim.core.service.ComponentService
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.liftlink.LiftLinkPlanner
import systems.diath.homeclaim.platform.paper.util.SafeEventHandler
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action as BlockAction
import org.bukkit.event.player.PlayerInteractEvent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import systems.diath.homeclaim.platform.paper.I18n

class PaperLiftLinkListener(
    private val guard: PolicyGuard,
    private val componentService: ComponentService,
    private val regionService: RegionService,
    private val planner: LiftLinkPlanner,
    private val auditService: AuditService? = null
) : Listener {
    private val i18n = I18n()

    private val triggerBlocks = setOf(
        Material.LIGHT_WEIGHTED_PRESSURE_PLATE,
        Material.HEAVY_WEIGHTED_PRESSURE_PLATE,
        Material.STONE_PRESSURE_PLATE,
        Material.POLISHED_BLACKSTONE_PRESSURE_PLATE
    )

    @EventHandler
    fun onInteract(e: PlayerInteractEvent) = SafeEventHandler.handle(e, "LiftLink.onInteract") {
        if (e.action != BlockAction.PHYSICAL) return@handle
        val block = e.clickedBlock ?: return@handle
        if (!triggerBlocks.contains(block.type)) return@handle
        val player = e.player
        val pos = block.toPosition()
        val comp = componentService.getComponentAt(pos) ?: return@handle
        if (comp.state != ComponentState.ENABLED) return@handle
        when (comp.type) {
            ComponentType.ELEVATOR_PAD -> handleElevator(player, e, comp, pos)
            ComponentType.TELEPORT_PAD -> handleTeleport(player, e, comp, pos)
        }
    }

    private fun Block.toPosition(): Position = Position(
        world = this.world.name,
        x = this.x,
        y = this.y,
        z = this.z
    )

    private fun systems.diath.homeclaim.core.model.Position.toCenterLocation(world: org.bukkit.World) =
        org.bukkit.Location(world, x.toDouble() + 0.5, y.toDouble().coerceAtLeast(0.0), z.toDouble() + 0.5)
    
    /**
     * Teleportiere zu einer Position in einer anderen Welt (Cross-World)
     * Mit World-Checking und Permission-Validation
     */
    private fun teleportCrossWorld(player: org.bukkit.entity.Player, target: Position) {
        val targetWorld = org.bukkit.Bukkit.getWorld(target.world)
        if (targetWorld == null) {
            player.sendMessage(i18n.msg("liftlink.target.world.not.exists", target.world))
            return
        }
        
        // Prüfe Welt-Berechtigung wenn nötig
        if (hasWorldTeleportRestriction(player)) {
            player.sendMessage(i18n.msg("liftlink.no.world.access", target.world))
            return
        }
        
        val destLocation = target.toCenterLocation(targetWorld)
        player.teleportAsync(destLocation)
        player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(i18n.msg("liftlink.teleport.arrow") + " ${target.world}"))
    }
    
    /**
     * Prüfe ob Spieler in die Zielwelt teleportiert darf
     */
    private fun hasWorldTeleportRestriction(player: org.bukkit.entity.Player): Boolean {
        // Admins haben immer Zugriff
        if (player.hasPermission("homeclaim.admin")) return false
        
        // Optional: Implementiere Welt-Liste für Restricted Worlds
        // val restrictedWorlds = setOf("nether", "the_end")
        // return restrictedWorlds.contains(worldName.lowercase())
        
        return false // Standardmäßig: Alle Welten erlaubt
    }

    private fun systems.diath.homeclaim.core.model.Component.elevatorConfig(): systems.diath.homeclaim.core.model.ElevatorConfig? {
        return (this.config as? systems.diath.homeclaim.core.model.ElevatorConfig)
    }

    private fun systems.diath.homeclaim.core.model.Component.teleportConfig(): systems.diath.homeclaim.core.model.TeleportConfig? {
        return (this.config as? systems.diath.homeclaim.core.model.TeleportConfig)
    }

    private fun handleElevator(player: Player, e: PlayerInteractEvent, comp: systems.diath.homeclaim.core.model.Component, pos: Position) {
        val decision = guard.check(
            action = Action.COMPONENT_USE,
            position = pos,
            extra = mapOf(
                "playerId" to player.uniqueId,
                "componentId" to comp.id,
                "regionId" to comp.regionId
            )
        )
        if (!decision.allowed) {
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
        
        // Prüfe Cross-World Teleport
        val isCrossWorld = target.position.world != player.world.name
        if (isCrossWorld) {
            // Cross-World Teleport mit Permission-Check
            if (!player.hasPermission("homeclaim.elevator.crossworld")) {
                player.sendMessage(i18n.msg("liftlink.elevator.no.crossworld.perm"))
                e.isCancelled = true
                return
            }
            teleportCrossWorld(player, target.position)
        } else {
            // Same-World Teleport
            val dest = target.position.toCenterLocation(player.world)
            player.teleportAsync(dest)
            player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(i18n.msg("liftlink.teleported")))
        }
        
        auditService?.append(
            AuditEntry(
                actorId = player.uniqueId,
                targetId = comp.id.value,
                category = AuditTaxonomy.Category.COMPONENT,
                action = AuditTaxonomy.Action.ELEVATOR_USED,
                payload = AuditPayloads.actionPayload(
                    position = pos,
                    platform = "paper",
                    extra = mapOf(
                        "sourceId" to comp.id.value.toString(),
                        "targetId" to target.id.value.toString(),
                        "mode" to config.mode.name,
                        "distance" to (target.position.y - pos.y)
                    )
                )
            )
        )
        
        e.isCancelled = true
    }

    private fun handleTeleport(player: Player, e: PlayerInteractEvent, comp: systems.diath.homeclaim.core.model.Component, pos: Position) {
        val config = comp.teleportConfig() ?: return
        
        if (config.combatBlock == true && CombatTracker.isInCombat(player)) {
            val remaining = CombatTracker.getRemainingCombatMs(player) / 1000
            player.sendMessage(i18n.msg("liftlink.teleport.combat.blocked", remaining.toString()))
            e.isCancelled = true
            return
        }
        
        // Check sensor block access: only players without perms need active redstone signal
        val region = regionService.getRegionById(comp.regionId)
        val playerRole = region?.roles?.resolve(player.uniqueId, region.owner) 
            ?: systems.diath.homeclaim.core.model.RegionRole.VISITOR
        
        // If visitor/non-member, check redstone signal on sensor block
        if (playerRole == systems.diath.homeclaim.core.model.RegionRole.VISITOR) {
            val sensorBlock = e.clickedBlock
            if (sensorBlock != null && BlockSensorRegistry.isValidSensor(sensorBlock)) {
                if (!BlockSensorRegistry.hasActiveSignal(sensorBlock)) {
                    player.sendMessage(i18n.msg("liftlink.teleport.unavailable"))
                    e.isCancelled = true
                    return
                }
            }
        }
        
        val decision = guard.check(
            action = Action.COMPONENT_USE,
            position = pos,
            extra = mapOf(
                "playerId" to player.uniqueId,
                "componentId" to comp.id,
                "regionId" to comp.regionId
            )
        )
        if (!decision.allowed) {
            player.sendMessage(policyMessage(decision.reason, decision.detail))
            e.isCancelled = true
            return
        }
        
        val targets = resolveTeleportTargets(comp, config)
        if (targets.isEmpty()) {
            player.sendMessage(i18n.msg("liftlink.teleport.no.target"))
            return
        }
        
        val destComp = targets.first()
        
        // Prüfe Cross-World Teleport
        val isCrossWorld = destComp.position.world != player.world.name
        if (isCrossWorld) {
            // Cross-World Teleport mit Permission-Check
            if (!player.hasPermission("homeclaim.teleporter.crossworld")) {
                player.sendMessage(i18n.msg("liftlink.teleport.no.crossworld.perm"))
                e.isCancelled = true
                return
            }
            teleportCrossWorld(player, destComp.position)
        } else {
            // Same-World Teleport
            val dest = destComp.position.toCenterLocation(player.world)
            player.teleportAsync(dest)
            player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(i18n.msg("teleport.actionbar")))
        }
        
        auditService?.append(
            AuditEntry(
                actorId = player.uniqueId,
                targetId = comp.id.value,
                category = AuditTaxonomy.Category.COMPONENT,
                action = AuditTaxonomy.Action.TELEPORT_USED,
                payload = AuditPayloads.actionPayload(
                    position = pos,
                    platform = "paper",
                    extra = mapOf(
                        "sourceId" to comp.id.value.toString(),
                        "targetId" to destComp.id.value.toString(),
                        "linkId" to config.linkId.toString(),
                        "scope" to config.withinScope.name
                    )
                )
            )
        )
        
        e.isCancelled = true
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
