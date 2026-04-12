package systems.diath.homeclaim.platform.paper

import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.platform.BlockEventContext
import systems.diath.homeclaim.core.platform.ComponentTriggerRequest
import systems.diath.homeclaim.core.platform.ComponentTriggerHandler
import systems.diath.homeclaim.core.platform.InteractEventContext
import systems.diath.homeclaim.core.platform.PolicyGuard
import systems.diath.homeclaim.core.policy.Action
import systems.diath.homeclaim.core.policy.ActorKind
import systems.diath.homeclaim.core.policy.DecisionReason
import systems.diath.homeclaim.core.policy.PolicyActionRequest
import systems.diath.homeclaim.core.policy.PolicyActorContext
import systems.diath.homeclaim.core.service.AuditEntry
import systems.diath.homeclaim.core.service.AuditService
import systems.diath.homeclaim.platform.paper.util.SafeEventHandler
import org.bukkit.block.Container
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockRedstoneEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.event.vehicle.VehicleDamageEvent
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Animals
import org.bukkit.entity.Monster

class PaperPolicyListener(
    private val policyGuard: PolicyGuard,
    private val componentHandler: ComponentTriggerHandler,
    private val auditService: AuditService? = null
) : Listener {

    @EventHandler(ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) = SafeEventHandler.handle(event, "onBlockPlace") {
        val position = event.blockPlaced.position()
        val decision = policyGuard.onBlockPlace(
            BlockEventContext(
                playerId = event.player.uniqueId,
                position = position,
                blockType = event.blockPlaced.type.name
            )
        )
        if (!decision.allowed) {
            event.isCancelled = true
            event.player.deny(decision.reason, decision.detail)
            auditService?.append(
                AuditEntry(
                    actorId = event.player.uniqueId,
                    targetId = null,
                    category = "BLOCK",
                    action = "PLACE_DENIED",
                    payload = mapOf(
                        "block" to event.blockPlaced.type.name,
                        "world" to position.world,
                        "x" to position.x,
                        "y" to position.y,
                        "z" to position.z,
                        "reason" to decision.reason
                    )
                )
            )
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) = SafeEventHandler.handle(event, "onBlockBreak") {
        val position = event.block.position()
        val decision = policyGuard.onBlockBreak(
            BlockEventContext(
                playerId = event.player.uniqueId,
                position = position,
                blockType = event.block.type.name
            )
        )
        if (!decision.allowed) {
            event.isCancelled = true
            event.player.deny(decision.reason, decision.detail)
            auditService?.append(
                AuditEntry(
                    actorId = event.player.uniqueId,
                    targetId = null,
                    category = "BLOCK",
                    action = "BREAK_DENIED",
                    payload = mapOf(
                        "block" to event.block.type.name,
                        "world" to position.world,
                        "x" to position.x,
                        "y" to position.y,
                        "z" to position.z,
                        "reason" to decision.reason
                    )
                )
            )
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) = SafeEventHandler.handle(event, "onInteract") {
        val block = event.clickedBlock ?: return@handle
        val position = block.position()
        val decision = policyGuard.onBlockInteract(
            InteractEventContext(
                playerId = event.player.uniqueId,
                position = position,
                targetType = block.type.name
            ),
            isContainer = block.state is Container
        )
        if (!decision.allowed) {
            event.isCancelled = true
            event.player.deny(decision.reason, decision.detail)
            return@handle
        }

        // Handle component triggers (e.g., stepping on pads).
        if (event.action.isPhysical()) {
            val triggerResult = componentHandler.onTrigger(
                ComponentTriggerRequest(
                    playerId = event.player.uniqueId,
                    position = position,
                    actor = PolicyActorContext(
                        kind = ActorKind.PLAYER,
                        actorId = event.player.uniqueId.toString(),
                        sourceMod = "paper"
                    ),
                    platform = "paper",
                    extra = mapOf("event" to "PlayerInteractEvent")
                )
            )
            val componentDecision = triggerResult.decision
            if (componentDecision != null && !componentDecision.allowed) {
                event.isCancelled = true
                event.player.deny(componentDecision.reason, componentDecision.detail)
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onIgnite(event: BlockIgniteEvent) = SafeEventHandler.handle(event, "onIgnite") {
        val pos = event.block.position()
        val actor = event.player?.uniqueId ?: event.ignitingEntity?.uniqueId ?: return@handle
        val actorKind = if (event.player != null) ActorKind.PLAYER else ActorKind.ENTITY
        val decision = policyGuard.check(
            PolicyActionRequest(
                action = Action.REGION_FIRE,
                position = pos,
                actor = PolicyActorContext(kind = actorKind, actorId = actor.toString(), sourceMod = "paper"),
                playerId = actor,
                platform = "paper",
                extra = mapOf("event" to "BlockIgniteEvent")
            )
        )
        if (!decision.allowed) {
            event.isCancelled = true
            event.player?.deny(decision.reason, decision.detail)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onExplosion(event: EntityExplodeEvent) = SafeEventHandler.handle(event, "onExplosion") {
        val pos = event.location.let { systems.diath.homeclaim.core.model.Position(it.world?.name ?: "unknown", it.blockX, it.blockY, it.blockZ) }
        val actor = (event.entity as? org.bukkit.entity.TNTPrimed)?.source?.uniqueId
            ?: (event.entity as? Projectile)?.shooter?.let { it as? Player }?.uniqueId
            ?: event.entity.uniqueId
        val actorKind = if ((event.entity as? org.bukkit.entity.TNTPrimed)?.source is Player ||
            (event.entity as? Projectile)?.shooter is Player
        ) ActorKind.PLAYER else ActorKind.ENTITY
        val decision = policyGuard.check(
            PolicyActionRequest(
                action = Action.REGION_EXPLOSION,
                position = pos,
                actor = PolicyActorContext(kind = actorKind, actorId = actor.toString(), sourceMod = "paper"),
                playerId = actor,
                platform = "paper",
                extra = mapOf("event" to "EntityExplodeEvent")
            )
        )
        if (!decision.allowed) {
            event.isCancelled = true
            val world = event.entity.world
            world.players.firstOrNull { it.uniqueId == actor }?.deny(decision.reason, decision.detail)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPvp(event: EntityDamageByEntityEvent) = SafeEventHandler.handle(event, "onPvp") {
        val victim = event.entity as? Player ?: return@handle
        val damagerPlayer = when (val d = event.damager) {
            is Player -> d
            is Projectile -> d.shooter as? Player
            else -> null
        } ?: return@handle
        
        CombatTracker.markInCombat(damagerPlayer)
        CombatTracker.markInCombat(victim)
        
        val loc = victim.location
        val position = systems.diath.homeclaim.core.model.Position(loc.world?.name ?: "unknown", loc.blockX, loc.blockY, loc.blockZ)
        val decision = policyGuard.check(
            PolicyActionRequest(
                action = Action.REGION_PVP,
                position = position,
                actor = PolicyActorContext(
                    kind = ActorKind.PLAYER,
                    actorId = damagerPlayer.uniqueId.toString(),
                    sourceMod = "paper"
                ),
                playerId = damagerPlayer.uniqueId,
                platform = "paper",
                extra = mapOf("event" to "EntityDamageByEntityEvent")
            )
        )
        if (!decision.allowed) {
            event.isCancelled = true
            damagerPlayer.deny(decision.reason, decision.detail)
            auditService?.append(
                AuditEntry(
                    actorId = damagerPlayer.uniqueId,
                    targetId = victim.uniqueId,
                    category = "PVP",
                    action = "PVP_DENIED",
                    payload = mapOf(
                        "world" to position.world,
                        "x" to position.x,
                        "y" to position.y,
                        "z" to position.z,
                        "reason" to decision.reason
                    )
                )
            )
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityDamage(event: EntityDamageEvent) = SafeEventHandler.handle(event, "onEntityDamage") {
        // Skip PvP (handled by onPvp)
        if (event is EntityDamageByEntityEvent) return@handle
        
        val entity = event.entity as? Player ?: return@handle
        val loc = entity.location
        val position = Position(loc.world?.name ?: "unknown", loc.blockX, loc.blockY, loc.blockZ)
        val decision = policyGuard.check(
            PolicyActionRequest(
                action = Action.REGION_ENTITY_DAMAGE,
                position = position,
                actor = PolicyActorContext(
                    kind = ActorKind.ENTITY,
                    actorId = entity.uniqueId.toString(),
                    sourceMod = "paper"
                ),
                playerId = entity.uniqueId,
                platform = "paper",
                extra = mapOf("cause" to event.cause.name, "event" to "EntityDamageEvent")
            )
        )
        if (!decision.allowed) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onRedstone(event: BlockRedstoneEvent) = SafeEventHandler.handle(event, "onRedstone", failSafe = false) {
        // Only check if power is being applied (going from 0 to >0)
        if (event.oldCurrent > 0 || event.newCurrent == 0) return@handle
        
        val pos = event.block.position()
        val decision = policyGuard.check(
            PolicyActionRequest(
                action = Action.REGION_REDSTONE,
                position = pos,
                actor = PolicyActorContext(
                    kind = ActorKind.AUTOMATION,
                    actorId = "redstone",
                    sourceMod = "minecraft:redstone"
                ),
                platform = "paper",
                extra = mapOf("oldCurrent" to event.oldCurrent, "newCurrent" to event.newCurrent, "event" to "BlockRedstoneEvent")
            )
        )
        if (!decision.allowed) {
            event.newCurrent = 0
            // Log redstone block (system action, no specific actor)
            auditService?.append(
                AuditEntry(
                    actorId = null,  // Redstone is a system action
                    targetId = null,
                    category = "REDSTONE",
                    action = "BLOCK_DENIED",
                    payload = mapOf(
                        "block" to event.block.type.name,
                        "world" to pos.world,
                        "x" to pos.x,
                        "y" to pos.y,
                        "z" to pos.z,
                        "reason" to decision.reason
                    )
                )
            )
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onMobGrief(event: EntityChangeBlockEvent) = SafeEventHandler.handle(event, "onMobGrief") {
        // Only check mobs (Enderman, Creeper explosions are handled separately)
        val entity = event.entity
        if (entity is Player) return@handle
        if (entity !is Monster && entity !is Animals) return@handle
        
        val pos = event.block.position()
        val decision = policyGuard.check(
            PolicyActionRequest(
                action = Action.REGION_MOB_GRIEF,
                position = pos,
                actor = PolicyActorContext(
                    kind = ActorKind.ENTITY,
                    actorId = entity.uniqueId.toString(),
                    sourceMod = "minecraft:mob"
                ),
                playerId = entity.uniqueId,
                platform = "paper",
                extra = mapOf("entityType" to entity.type.name, "to" to event.to.name, "event" to "EntityChangeBlockEvent")
            )
        )
        if (!decision.allowed) {
            event.isCancelled = true
            // Log mob grief block
            auditService?.append(
                AuditEntry(
                    actorId = null,  // Mob action, no player
                    targetId = null,
                    category = "MOB",
                    action = "GRIEF_DENIED",
                    payload = mapOf(
                        "entityType" to entity.type.name,
                        "block" to event.block.type.name,
                        "to" to event.to.name,
                        "world" to pos.world,
                        "x" to pos.x,
                        "y" to pos.y,
                        "z" to pos.z,
                        "reason" to decision.reason
                    )
                )
            )
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onVehicleEnter(event: VehicleEnterEvent) = SafeEventHandler.handle(event, "onVehicleEnter") {
        val player = event.entered as? Player ?: return@handle
        val loc = event.vehicle.location
        val position = Position(loc.world?.name ?: "unknown", loc.blockX, loc.blockY, loc.blockZ)
        val decision = policyGuard.check(
            PolicyActionRequest(
                action = Action.REGION_VEHICLE_USE,
                position = position,
                actor = PolicyActorContext(
                    kind = ActorKind.PLAYER,
                    actorId = player.uniqueId.toString(),
                    sourceMod = "paper"
                ),
                playerId = player.uniqueId,
                platform = "paper",
                extra = mapOf("vehicleType" to event.vehicle.type.name, "event" to "VehicleEnterEvent")
            )
        )
        if (!decision.allowed) {
            event.isCancelled = true
            player.deny(decision.reason, decision.detail)
            auditService?.append(
                AuditEntry(
                    actorId = player.uniqueId,
                    targetId = null,
                    category = "VEHICLE",
                    action = "ENTER_DENIED",
                    payload = mapOf(
                        "vehicleType" to event.vehicle.type.name,
                        "world" to position.world,
                        "x" to position.x,
                        "y" to position.y,
                        "z" to position.z,
                        "reason" to decision.reason
                    )
                )
            )
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onVehicleDamage(event: VehicleDamageEvent) = SafeEventHandler.handle(event, "onVehicleDamage") {
        val player = event.attacker as? Player ?: return@handle
        val loc = event.vehicle.location
        val position = Position(loc.world?.name ?: "unknown", loc.blockX, loc.blockY, loc.blockZ)
        val decision = policyGuard.check(
            PolicyActionRequest(
                action = Action.REGION_VEHICLE_USE,
                position = position,
                actor = PolicyActorContext(
                    kind = ActorKind.PLAYER,
                    actorId = player.uniqueId.toString(),
                    sourceMod = "paper"
                ),
                playerId = player.uniqueId,
                platform = "paper",
                extra = mapOf("vehicleType" to event.vehicle.type.name, "action" to "damage", "event" to "VehicleDamageEvent")
            )
        )
        if (!decision.allowed) {
            event.isCancelled = true
            player.deny(decision.reason, decision.detail)
        }
    }
}

private fun org.bukkit.block.Block.position(): Position {
    val loc = this.location
    return Position(
        world = loc.world?.name ?: "unknown",
        x = loc.blockX,
        y = loc.blockY,
        z = loc.blockZ
    )
}

private fun Player.deny(reason: String, detail: String? = null) {
    val reasonMessage = policyReasonMessage(reason)
    val message = if (detail != null) {
        policyI18n.msg("policy.denied.detail", reasonMessage, detail)
    } else {
        policyI18n.msg("policy.denied", reasonMessage)
    }
    sendMessage(message)
    sendActionBar(Component.text(message))
}

private val policyI18n = I18n()

private fun policyReasonMessage(reason: String): String = when (reason) {
    DecisionReason.NO_REGION -> policyI18n.msg("policy.reason.no_region")
    DecisionReason.NO_PERMISSION -> policyI18n.msg("policy.reason.no_permission")
    DecisionReason.FLAG_DENY -> policyI18n.msg("policy.reason.flag_deny")
    DecisionReason.ROLE_BANNED -> policyI18n.msg("policy.reason.role_banned")
    DecisionReason.COOLDOWN_ACTIVE -> policyI18n.msg("policy.reason.cooldown_active")
    DecisionReason.ROLE_REQUIRED -> policyI18n.msg("policy.reason.role_required")
    DecisionReason.REDSTONE_DENY -> policyI18n.msg("policy.reason.redstone_deny")
    DecisionReason.MOD_ACTOR_DENY -> policyI18n.msg("policy.reason.mod_actor_deny")
    else -> policyI18n.msg("policy.reason.unknown")
}

private fun org.bukkit.event.block.Action.isPhysical(): Boolean {
    return this == org.bukkit.event.block.Action.PHYSICAL
}
