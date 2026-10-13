package systems.diath.homeclaim.platform.paper.protection

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.PortalCreateEvent
import systems.diath.homeclaim.core.policy.Action
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.DecisionEffect

/** Portal blocks are ordinary protected construction targets; travel remains in the entry listener. */
internal class PortalProtectionListener(private val adapter: PaperProtectionAdapter) : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onPortalCreate(event: PortalCreateEvent) = protectionEvent(event, "portal-create") {
        if (event.blocks.isEmpty()) return@protectionEvent

        val cause = event.entity
        val actor = cause?.protectionActionActor()
            ?: ActionActor.System("portal-create:${event.reason.name.lowercase()}")
        val bulk = adapter.bulkRequest(
            actor = actor,
            action = Action.REGION_BUILD.key,
            source = cause?.protectionActionSource(),
            targets = event.blocks.map { it.block.toHomeClaimPosition() },
            event = "PortalCreateEvent",
            attributes = mapOf("reason" to event.reason.name, "causeType" to (cause?.type?.name ?: "SYSTEM"))
        )
        val result = adapter.checkBulk(bulk)
        if (!result.allowed) {
            event.isCancelled = true
            (cause as? Player)?.let { player ->
                result.decisions.firstOrNull { it.effect == DecisionEffect.DENY }?.let { decision ->
                    adapter.deny(player, decision)
                }
            }
            adapter.auditBulk(bulk, result)
        }
    }
}
