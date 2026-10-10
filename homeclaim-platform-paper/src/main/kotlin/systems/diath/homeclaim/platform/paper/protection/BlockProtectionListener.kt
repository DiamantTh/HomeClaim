package systems.diath.homeclaim.platform.paper.protection

import org.bukkit.block.Container
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockMultiPlaceEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import systems.diath.homeclaim.core.policy.Action
import systems.diath.homeclaim.core.policy.ActionActor

internal class BlockProtectionListener(
    private val adapter: PaperProtectionAdapter
) : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onMultiPlace(event: BlockMultiPlaceEvent) = protectionEvent(event, "block-multi-place") {
        val targets = buildList {
            add(event.blockPlaced.toHomeClaimPosition())
            event.replacedBlockStates.forEach { add(it.block.toHomeClaimPosition()) }
        }.distinct()
        val bulk = adapter.bulkRequest(
            actor = ActionActor.Player(event.player.uniqueId),
            action = Action.REGION_BUILD.key,
            targets = targets,
            event = "BlockMultiPlaceEvent",
            attributes = mapOf("block" to event.blockPlaced.type.name)
        )
        val result = adapter.checkBulk(bulk)
        if (!result.allowed) {
            event.isCancelled = true
            adapter.auditBulk(bulk, result)
            result.decisions.firstOrNull { it.effect == systems.diath.homeclaim.core.policy.DecisionEffect.DENY }
                ?.let { adapter.deny(event.player, it) }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) = protectionEvent(event, "block-place") {
        if (event is BlockMultiPlaceEvent) return@protectionEvent
        val target = event.blockPlaced.toHomeClaimPosition()
        val request = adapter.request(
            actor = ActionActor.Player(event.player.uniqueId),
            action = Action.REGION_BUILD.key,
            target = target,
            event = "BlockPlaceEvent",
            attributes = mapOf("block" to event.blockPlaced.type.name)
        )
        val decision = adapter.check(request, checkBothEnds = false)
        if (decision.effect == systems.diath.homeclaim.core.policy.DecisionEffect.DENY) {
            event.isCancelled = true
            adapter.deny(event.player, decision)
            adapter.auditDenied(request, decision)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) = protectionEvent(event, "block-break") {
        val target = event.block.toHomeClaimPosition()
        val request = adapter.request(
            actor = ActionActor.Player(event.player.uniqueId),
            action = Action.REGION_BREAK.key,
            target = target,
            event = "BlockBreakEvent",
            attributes = mapOf("block" to event.block.type.name)
        )
        val decision = adapter.check(request, checkBothEnds = false)
        if (decision.effect == systems.diath.homeclaim.core.policy.DecisionEffect.DENY) {
            event.isCancelled = true
            adapter.deny(event.player, decision)
            adapter.auditDenied(request, decision)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) = protectionEvent(event, "block-interact") {
        val block = event.clickedBlock ?: return@protectionEvent
        val position = block.toHomeClaimPosition()
        val action = if (block.state is Container) Action.REGION_INTERACT_CONTAINER else Action.REGION_INTERACT_BLOCK
        val request = adapter.request(
            actor = ActionActor.Player(event.player.uniqueId),
            action = action.key,
            target = position,
            event = "PlayerInteractEvent",
            attributes = mapOf("target" to block.type.name, "interaction" to event.action.name)
        )
        val decision = adapter.check(request, checkBothEnds = false)
        if (decision.effect == systems.diath.homeclaim.core.policy.DecisionEffect.DENY) {
            event.isCancelled = true
            adapter.deny(event.player, decision)
            adapter.auditDenied(request, decision)
            return@protectionEvent
        }

    }
}
