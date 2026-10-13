package systems.diath.homeclaim.platform.paper.protection

import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.entity.PlayerLeashEntityEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerShearEntityEvent
import org.bukkit.projectiles.BlockProjectileSource
import systems.diath.homeclaim.core.policy.Action
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.ActionRequest
import systems.diath.homeclaim.core.policy.DecisionEffect
import systems.diath.homeclaim.core.policy.PolicyActorContext

internal class EntityProtectionListener(private val adapter: PaperProtectionAdapter) : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onEntityInteract(event: PlayerInteractEntityEvent) = protectionEvent(event, "player-entity-interact") {
        checkPlayerEntityAction(
            player = event.player,
            target = event.rightClicked,
            eventName = "PlayerInteractEntityEvent",
            action = Action.REGION_INTERACT_BLOCK,
            cancel = { event.isCancelled = true }
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onArmorStandManipulate(event: PlayerArmorStandManipulateEvent) = protectionEvent(event, "armor-stand-manipulate") {
        checkPlayerEntityAction(
            player = event.player,
            target = event.rightClicked,
            eventName = "PlayerArmorStandManipulateEvent",
            action = Action.HANGING_ENTITY,
            cancel = { event.isCancelled = true }
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityBlockInteract(event: org.bukkit.event.entity.EntityInteractEvent) = protectionEvent(event, "entity-block-interact") {
        val target = event.block.toHomeClaimPosition()
        val request = adapter.request(
            actor = event.entity.toActionActor(),
            action = Action.REGION_INTERACT_BLOCK.key,
            source = event.entity.location.toHomeClaimPosition(),
            target = target,
            event = "EntityInteractEvent",
            attributes = mapOf("entityType" to event.entity.type.name, "block" to event.block.type.name)
        )
        reject(event, request, adapter.check(request), event.entity as? Player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) = protectionEvent(event, "entity-damage") {
        val byEntity = event as? EntityDamageByEntityEvent
        val damager = byEntity?.damager
        val actor = damager?.protectionActionActor() ?: ActionActor.System("damage:${event.cause.name.lowercase()}")
        val source = damager?.protectionActionSource()
        val victim = event.entity
        val action = if (victim is Player && actor is ActionActor.Player) Action.REGION_PVP else Action.REGION_ENTITY_DAMAGE
        val request = adapter.request(
            actor = actor,
            action = action.key,
            source = source,
            target = victim.location.toHomeClaimPosition(),
            event = if (byEntity == null) "EntityDamageEvent" else "EntityDamageByEntityEvent",
            attributes = mapOf("cause" to event.cause.name, "victimType" to victim.type.name)
        )
        val decision = adapter.check(request)
        if (decision.effect == DecisionEffect.DENY) {
            event.isCancelled = true
            (actor as? ActionActor.Player)?.let { player ->
                victim.world.players.firstOrNull { it.uniqueId == player.playerId }?.let { adapter.deny(it, decision) }
            }
            adapter.auditDenied(request, decision)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityPlace(event: EntityPlaceEvent) = protectionEvent(event, "entity-place") {
        val actor = event.player?.let { ActionActor.Player(it.uniqueId) } ?: event.entity.toActionActor()
        val request = adapter.request(
            actor = actor,
            action = Action.ENTITY_PLACE.key,
            source = event.player?.location?.toHomeClaimPosition(),
            target = event.block.toHomeClaimPosition(),
            event = "EntityPlaceEvent",
            attributes = mapOf("entityType" to event.entity.type.name)
        )
        reject(event, request, adapter.check(request), event.player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onHangingPlace(event: HangingPlaceEvent) = protectionEvent(event, "hanging-place") {
        val request = adapter.request(
            actor = event.player?.let { ActionActor.Player(it.uniqueId) } ?: event.entity.toActionActor(),
            action = Action.HANGING_ENTITY.key,
            source = event.player?.location?.toHomeClaimPosition(),
            target = event.block.toHomeClaimPosition(),
            event = "HangingPlaceEvent",
            attributes = mapOf("entityType" to event.entity.type.name)
        )
        reject(event, request, adapter.check(request), event.player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onHangingBreak(event: HangingBreakEvent) = protectionEvent(event, "hanging-break") {
        val remover = (event as? org.bukkit.event.hanging.HangingBreakByEntityEvent)?.remover
        val request = adapter.request(
            actor = remover?.toActionActor() ?: ActionActor.System("hanging:${event.cause.name.lowercase()}"),
            action = Action.HANGING_ENTITY.key,
            source = remover?.location?.toHomeClaimPosition(),
            target = event.entity.location.toHomeClaimPosition(),
            event = "HangingBreakEvent",
            attributes = mapOf("cause" to event.cause.name, "entityType" to event.entity.type.name)
        )
        reject(event, request, adapter.check(request), remover as? Player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onLeash(event: PlayerLeashEntityEvent) = protectionEvent(event, "entity-leash") {
        val request = adapter.request(
            actor = ActionActor.Player(event.player.uniqueId),
            action = Action.HANGING_ENTITY.key,
            source = event.leashHolder.location.toHomeClaimPosition(),
            target = event.entity.location.toHomeClaimPosition(),
            event = "PlayerLeashEntityEvent",
            attributes = mapOf("entityType" to event.entity.type.name)
        )
        reject(event, request, adapter.check(request), event.player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onShear(event: PlayerShearEntityEvent) = protectionEvent(event, "entity-shear") {
        val request = adapter.request(
            actor = ActionActor.Player(event.player.uniqueId),
            action = Action.HANGING_ENTITY.key,
            source = event.player.location.toHomeClaimPosition(),
            target = event.entity.location.toHomeClaimPosition(),
            event = "PlayerShearEntityEvent",
            attributes = mapOf("entityType" to event.entity.type.name)
        )
        reject(event, request, adapter.check(request), event.player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onProjectileLaunch(event: ProjectileLaunchEvent) = protectionEvent(event, "projectile-launch") {
        val projectile = event.entity
        val shooter = projectile.shooter as? Entity
        val blockShooter = projectile.shooter as? BlockProjectileSource
        val request = adapter.request(
            actor = shooter?.toActionActor()
                ?: blockShooter?.let { ActionActor.Automation("dispenser:${it.block.toHomeClaimPosition()}", "minecraft:dispenser") }
                ?: ActionActor.System("projectile:${projectile.type.name.lowercase()}"),
            action = Action.PROJECTILE_LAUNCH.key,
            source = shooter?.location?.toHomeClaimPosition() ?: blockShooter?.block?.toHomeClaimPosition(),
            target = projectile.location.toHomeClaimPosition(),
            event = "ProjectileLaunchEvent",
            attributes = mapOf("projectileType" to projectile.type.name)
        )
        reject(event, request, adapter.check(request), shooter as? Player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerDrop(event: PlayerDropItemEvent) = protectionEvent(event, "player-item-drop") {
        val request = adapter.request(
            actor = ActionActor.Player(event.player.uniqueId),
            action = Action.ITEM_DROP.key,
            source = event.player.location.toHomeClaimPosition(),
            target = event.itemDrop.location.toHomeClaimPosition(),
            event = "PlayerDropItemEvent",
            attributes = mapOf("item" to event.itemDrop.itemStack.type.name)
        )
        reject(event, request, adapter.check(request), event.player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityPickup(event: EntityPickupItemEvent) = protectionEvent(event, "entity-item-pickup") {
        val request = adapter.request(
            actor = event.entity.toActionActor(),
            action = Action.ITEM_PICKUP.key,
            source = event.item.location.toHomeClaimPosition(),
            target = event.entity.location.toHomeClaimPosition(),
            event = "EntityPickupItemEvent",
            attributes = mapOf("item" to event.item.itemStack.type.name)
        )
        reject(event, request, adapter.check(request), event.entity as? Player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onInventoryPickup(event: InventoryPickupItemEvent) = protectionEvent(event, "inventory-item-pickup") {
        val inventoryPosition = event.inventory.location?.toHomeClaimPosition() ?: return@protectionEvent
        val request = adapter.request(
            actor = ActionActor.Automation("inventory-pickup", "minecraft:inventory"),
            action = Action.ITEM_PICKUP.key,
            source = event.item.location.toHomeClaimPosition(),
            target = inventoryPosition,
            event = "InventoryPickupItemEvent",
            attributes = mapOf("item" to event.item.itemStack.type.name)
        )
        reject(event, request, adapter.check(request), null)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockDrop(event: org.bukkit.event.block.BlockDropItemEvent) = protectionEvent(event, "block-item-drop") {
        val request = adapter.request(
            actor = ActionActor.Player(event.player.uniqueId),
            action = Action.ITEM_DROP.key,
            source = event.block.toHomeClaimPosition(),
            target = event.items.firstOrNull()?.location?.toHomeClaimPosition() ?: event.block.toHomeClaimPosition(),
            event = "BlockDropItemEvent",
            attributes = mapOf("block" to event.block.type.name, "itemCount" to event.items.size.toString())
        )
        reject(event, request, adapter.check(request), event.player)
    }

    private fun checkPlayerEntityAction(
        player: Player,
        target: Entity,
        eventName: String,
        action: Action,
        cancel: () -> Unit
    ) {
        val request = adapter.request(
            actor = ActionActor.Player(player.uniqueId),
            action = action.key,
            source = player.location.toHomeClaimPosition(),
            target = target.location.toHomeClaimPosition(),
            event = eventName,
            attributes = mapOf("entityType" to target.type.name)
        )
        val decision = adapter.check(request)
        if (decision.effect == DecisionEffect.DENY) {
            cancel()
            adapter.deny(player, decision)
            adapter.auditDenied(request, decision)
        }
    }

    private fun reject(
        event: org.bukkit.event.Cancellable,
        request: ActionRequest,
        decision: systems.diath.homeclaim.core.policy.Decision,
        player: Player?
    ) {
        if (decision.effect != DecisionEffect.DENY) return
        event.isCancelled = true
        player?.let { adapter.deny(it, decision) }
        adapter.auditDenied(request, decision)
    }
}
