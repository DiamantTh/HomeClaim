package systems.diath.homeclaim.platform.paper.protection

import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.projectiles.BlockProjectileSource
import systems.diath.homeclaim.core.policy.Action
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.BulkActionRequest
import systems.diath.homeclaim.core.policy.BulkActionResult
import systems.diath.homeclaim.core.policy.CorrelationId
import systems.diath.homeclaim.core.policy.DecisionEffect

internal class ExplosionProtectionListener(private val adapter: PaperProtectionAdapter) : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onEntityExplosion(event: EntityExplodeEvent) = protectionEvent(event, "entity-explosion") {
        val source = event.location.toHomeClaimPosition()
        val actor = event.entity.explosionActor()
        filterExplosion(
            blocks = event.blockList(),
            actor = actor,
            source = source,
            eventName = "EntityExplodeEvent",
            attributes = mapOf("entityType" to event.entity.type.name, "yield" to event.yield.toString())
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockExplosion(event: BlockExplodeEvent) = protectionEvent(event, "block-explosion") {
        filterExplosion(
            blocks = event.blockList(),
            actor = ActionActor.Automation("block-explosion:${event.block.toHomeClaimPosition()}", "minecraft:explosion"),
            source = event.block.toHomeClaimPosition(),
            eventName = "BlockExplodeEvent",
            attributes = mapOf("block" to event.block.type.name, "yield" to event.yield.toString())
        )
    }

    private fun filterExplosion(
        blocks: MutableList<Block>,
        actor: ActionActor,
        source: systems.diath.homeclaim.core.model.Position,
        eventName: String,
        attributes: Map<String, String>
    ) {
        if (blocks.isEmpty()) return
        val correlation = CorrelationId.newId()
        val targets = blocks.map { it.toHomeClaimPosition() }
        val bulk = adapter.bulkRequest(
            actor = actor,
            action = Action.REGION_EXPLOSION.key,
            source = source,
            targets = targets,
            event = eventName,
            attributes = attributes,
            correlationId = correlation
        )
        val result = adapter.checkBulk(bulk)
        if (result.deniedCount > 0) {
            val deniedPositions = result.decisions.mapNotNull { decision ->
                if (decision.effect == DecisionEffect.DENY) decision.context.actionRequest?.target?.position else null
            }.toHashSet()
            blocks.removeIf { it.toHomeClaimPosition() in deniedPositions }
        }
        // One correlated lifecycle record for the explosion, irrespective of its block count.
        if (result.deniedCount > 0 || result.allowedCount > 0) adapter.auditBulk(bulk, result)
    }
}

private fun Entity.explosionActor(): ActionActor = when (this) {
    is TNTPrimed -> source?.toActionActor() ?: toActionActor()
    is Projectile -> when (val projectileSource = shooter) {
        is Entity -> projectileSource.toActionActor()
        is BlockProjectileSource -> ActionActor.Automation(
            "dispenser:${projectileSource.block.toHomeClaimPosition()}",
            "minecraft:dispenser"
        )
        else -> toActionActor()
    }
    else -> toActionActor()
}
