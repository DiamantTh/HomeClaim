package systems.diath.homeclaim.platform.paper.protection

import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import org.bukkit.block.data.Directional
import org.bukkit.entity.Animals
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockDispenseEvent
import org.bukkit.event.block.BlockDispenseArmorEvent
import org.bukkit.event.block.BlockFertilizeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockRedstoneEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.block.CauldronLevelChangeEvent
import org.bukkit.event.block.SpongeAbsorbEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.block.BlockState
import org.bukkit.entity.Entity
import systems.diath.homeclaim.core.policy.Action
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.ActionEndpoint
import systems.diath.homeclaim.core.policy.ActionRequest
import systems.diath.homeclaim.core.policy.BulkActionRequest
import systems.diath.homeclaim.core.policy.BulkActionResult
import systems.diath.homeclaim.core.policy.CorrelationId
import systems.diath.homeclaim.core.policy.DecisionEffect

internal class AutomationProtectionListener(private val adapter: PaperProtectionAdapter) : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) = protectionEvent(event, "piston-extend") {
        checkPiston(
            piston = event.block,
            movedBlocks = event.blocks,
            direction = event.direction,
            retract = false,
            eventName = "BlockPistonExtendEvent",
            cancel = { event.isCancelled = true }
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) = protectionEvent(event, "piston-retract") {
        checkPiston(
            piston = event.block,
            movedBlocks = event.blocks,
            direction = event.direction,
            retract = true,
            eventName = "BlockPistonRetractEvent",
            cancel = { event.isCancelled = true }
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onLiquidFlow(event: BlockFromToEvent) = protectionEvent(event, "liquid-flow") {
        checkAcross(
            actor = ActionActor.Automation("liquid:${event.block.type.name.lowercase()}", "minecraft:liquid"),
            action = Action.LIQUID_FLOW,
            source = event.block.toHomeClaimPosition(),
            target = event.toBlock.toHomeClaimPosition(),
            eventName = "BlockFromToEvent",
            cancel = { event.isCancelled = true },
            attributes = mapOf("sourceType" to event.block.type.name)
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onInventoryMove(event: InventoryMoveItemEvent) = protectionEvent(event, "inventory-move") {
        val source = event.source.protectionPosition() ?: return@protectionEvent
        val target = event.destination.protectionPosition() ?: return@protectionEvent
        checkAcross(
            actor = ActionActor.Automation("inventory-transfer", "minecraft:hopper"),
            action = Action.HOPPER_TRANSFER,
            source = source,
            target = target,
            eventName = "InventoryMoveItemEvent",
            cancel = { event.isCancelled = true },
            attributes = mapOf("item" to event.item.type.name)
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onDispenser(event: BlockDispenseEvent) = protectionEvent(event, "dispenser-use") {
        val dispenser = event.block
        val facing = (dispenser.blockData as? Directional)?.facing ?: BlockFace.SELF
        val target = (event as? BlockDispenseArmorEvent)?.targetEntity?.location?.toHomeClaimPosition()
            ?: if (facing == BlockFace.SELF) dispenser.toHomeClaimPosition() else dispenser.relativePosition(facing)
        checkAcross(
            actor = ActionActor.Automation("dispenser:${dispenser.location.toHomeClaimPosition()}", "minecraft:dispenser"),
            action = if (event is BlockDispenseArmorEvent) Action.DISPENSER_ENTITY_USE else Action.DISPENSER_USE,
            source = dispenser.toHomeClaimPosition(),
            target = target,
            eventName = "BlockDispenseEvent",
            cancel = { event.isCancelled = true },
            attributes = mapOf("item" to event.item.type.name, "block" to dispenser.type.name)
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) = protectionEvent(event, "bucket-empty") {
        checkAcross(
            actor = ActionActor.Player(event.player.uniqueId),
            action = Action.LIQUID_FLOW,
            source = event.blockClicked.toHomeClaimPosition(),
            target = event.block.toHomeClaimPosition(),
            eventName = "PlayerBucketEmptyEvent",
            cancel = { event.isCancelled = true },
            player = event.player,
            attributes = mapOf("bucket" to event.bucket.name)
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) = protectionEvent(event, "bucket-fill") {
        checkAcross(
            actor = ActionActor.Player(event.player.uniqueId),
            action = Action.LIQUID_FLOW,
            source = event.blockClicked.toHomeClaimPosition(),
            target = event.block.toHomeClaimPosition(),
            eventName = "PlayerBucketFillEvent",
            cancel = { event.isCancelled = true },
            player = event.player,
            attributes = mapOf("bucket" to event.bucket.name)
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onSpongeAbsorb(event: SpongeAbsorbEvent) = protectionEvent(event, "sponge-absorb") {
        if (event.blocks.isEmpty()) return@protectionEvent
        val bulk = adapter.bulkRequest(
            actor = ActionActor.Automation("sponge:${event.block.location.toHomeClaimPosition()}", "minecraft:sponge"),
            action = Action.LIQUID_FLOW.key,
            source = event.block.toHomeClaimPosition(),
            targets = event.blocks.map { it.block.toHomeClaimPosition() },
            event = "SpongeAbsorbEvent"
        )
        val result = adapter.checkBulk(bulk)
        val deniedPositions = result.decisions.mapNotNull { decision ->
            if (decision.effect == DecisionEffect.DENY) decision.context.actionRequest?.target?.position else null
        }.toHashSet()
        if (deniedPositions.isNotEmpty()) {
            event.blocks.removeIf { it.block.toHomeClaimPosition() in deniedPositions }
            adapter.auditBulk(bulk, result)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onIgnite(event: BlockIgniteEvent) = protectionEvent(event, "block-ignite") {
        val actor = event.player?.let { ActionActor.Player(it.uniqueId) }
            ?: event.ignitingEntity?.toActionActor()
            ?: ActionActor.System("fire:${event.cause.name.lowercase()}")
        checkAcross(
            actor = actor,
            action = Action.REGION_FIRE,
            source = event.ignitingBlock?.toHomeClaimPosition() ?: event.ignitingEntity?.location?.toHomeClaimPosition(),
            target = event.block.toHomeClaimPosition(),
            eventName = "BlockIgniteEvent",
            cancel = { event.isCancelled = true },
            player = event.player,
            attributes = mapOf("cause" to event.cause.name)
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onBurn(event: BlockBurnEvent) = protectionEvent(event, "block-burn") {
        checkAcross(
            actor = ActionActor.System("fire-burn"),
            action = Action.REGION_FIRE,
            source = event.ignitingBlock?.toHomeClaimPosition(),
            target = event.block.toHomeClaimPosition(),
            eventName = "BlockBurnEvent",
            cancel = { event.isCancelled = true }
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onForm(event: BlockFormEvent) = protectionEvent(event, "block-form") {
        val request = adapter.request(
            actor = ActionActor.System("block-form"),
            action = Action.BLOCK_GROW.key,
            target = event.block.toHomeClaimPosition(),
            event = "BlockFormEvent",
            attributes = mapOf("newType" to event.newState.type.name)
        )
        val decision = adapter.check(request, checkBothEnds = false)
        if (decision.effect == DecisionEffect.DENY) {
            event.isCancelled = true
            adapter.auditDenied(request, decision)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onSpread(event: BlockSpreadEvent) = protectionEvent(event, "block-spread") {
        val fire = event.source.type.name.contains("FIRE") || event.source.type.name == "LAVA"
        checkAcross(
            actor = ActionActor.System("block-spread"),
            action = if (fire) Action.REGION_FIRE else Action.BLOCK_GROW,
            source = event.source.toHomeClaimPosition(),
            target = event.block.toHomeClaimPosition(),
            eventName = "BlockSpreadEvent",
            cancel = { event.isCancelled = true },
            attributes = mapOf("sourceType" to event.source.type.name)
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onGrow(event: BlockGrowEvent) = protectionEvent(event, "block-grow") {
        if (event is org.bukkit.event.block.BlockSpreadEvent) return@protectionEvent
        val request = adapter.request(
            actor = ActionActor.System("natural-growth"),
            action = Action.BLOCK_GROW.key,
            target = event.block.toHomeClaimPosition(),
            event = event.eventName,
            attributes = mapOf("newType" to event.newState.type.name)
        )
        val decision = adapter.check(request, checkBothEnds = false)
        if (decision.effect == DecisionEffect.DENY) {
            event.isCancelled = true
            adapter.auditDenied(request, decision)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onStructureGrow(event: StructureGrowEvent) = protectionEvent(event, "structure-grow") {
        evaluateGrowth(
            actor = event.player?.let { ActionActor.Player(it.uniqueId) } ?: ActionActor.System("structure-growth"),
            source = event.location.toHomeClaimPosition(),
            targets = event.blocks.map { it.location.toHomeClaimPosition() },
            event = "StructureGrowEvent",
            attributes = mapOf("treeType" to event.species.name, "bonemeal" to event.isFromBonemeal.toString()),
            cancel = { event.isCancelled = true }
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onFertilize(event: BlockFertilizeEvent) = protectionEvent(event, "block-fertilize") {
        evaluateGrowth(
            actor = event.player?.let { ActionActor.Player(it.uniqueId) } ?: ActionActor.System("fertilizer"),
            source = event.block.toHomeClaimPosition(),
            targets = event.blocks.map { it.location.toHomeClaimPosition() },
            event = "BlockFertilizeEvent",
            attributes = mapOf("fertilizer" to (event.player?.uniqueId?.toString() ?: "system")),
            cancel = { event.isCancelled = true }
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onPhysics(event: BlockPhysicsEvent) = protectionEvent(event, "block-physics") {
        val sourceBlock = event.sourceBlock
        val source = sourceBlock.toHomeClaimPosition()
        val target = event.block.toHomeClaimPosition()
        val sourceEndpoint = adapter.resolveEndpoint(source)
        val targetEndpoint = adapter.resolveEndpoint(target)
        if (sourceEndpoint.worldId == targetEndpoint.worldId && sourceEndpoint.areaId == targetEndpoint.areaId &&
            sourceEndpoint.resource == targetEndpoint.resource
        ) return@protectionEvent
        checkAcross(
            actor = ActionActor.System("block-physics"),
            action = Action.FALLING_BLOCK,
            source = source,
            target = target,
            eventName = "BlockPhysicsEvent",
            cancel = { event.isCancelled = true },
            attributes = mapOf("changedType" to event.changedType.name)
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) = protectionEvent(event, "entity-change-block") {
        val entity = event.entity
        if (entity is Player) return@protectionEvent
        val action = if (entity is FallingBlock) Action.FALLING_BLOCK else if (entity is Monster || entity is Animals) {
            Action.REGION_MOB_GRIEF
        } else return@protectionEvent
        val request = adapter.request(
            actor = entity.toActionActor(),
            action = action.key,
            source = entity.location.toHomeClaimPosition(),
            target = event.block.toHomeClaimPosition(),
            event = "EntityChangeBlockEvent",
            attributes = mapOf("entityType" to entity.type.name, "newType" to event.to.name)
        )
        val decision = adapter.check(request)
        if (decision.effect == DecisionEffect.DENY) {
            event.isCancelled = true
            adapter.auditDenied(request, decision)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onRedstone(event: BlockRedstoneEvent) = protectionEvent(event, "redstone-transition") {
        if (event.oldCurrent > 0 || event.newCurrent == 0) return@protectionEvent
        val request = adapter.request(
            actor = ActionActor.Automation("redstone", "minecraft:redstone"),
            action = Action.REGION_REDSTONE.key,
            target = event.block.toHomeClaimPosition(),
            event = "BlockRedstoneEvent",
            attributes = mapOf("oldCurrent" to event.oldCurrent.toString(), "newCurrent" to event.newCurrent.toString())
        )
        val decision = adapter.check(request, checkBothEnds = false)
        if (decision.effect == DecisionEffect.DENY) {
            event.newCurrent = 0
            adapter.auditDenied(request, decision)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onCauldronChange(event: CauldronLevelChangeEvent) = protectionEvent(event, "cauldron-change") {
        val actor = event.entity?.let { if (it is Player) ActionActor.Player(it.uniqueId) else it.toActionActor() }
            ?: ActionActor.System("cauldron:${event.reason.name.lowercase()}")
        val request = adapter.request(
            actor = actor,
            action = Action.CAULDRON_CHANGE.key,
            source = event.entity?.location?.toHomeClaimPosition(),
            target = event.block.toHomeClaimPosition(),
            event = "CauldronLevelChangeEvent",
            attributes = mapOf("cause" to event.reason.name, "newBlockData" to event.newState.blockData.asString)
        )
        val decision = adapter.check(request)
        if (decision.effect == DecisionEffect.DENY) {
            event.isCancelled = true
            adapter.auditDenied(request, decision)
        }
    }

    private fun checkPiston(
        piston: org.bukkit.block.Block,
        movedBlocks: List<org.bukkit.block.Block>,
        direction: BlockFace,
        retract: Boolean,
        eventName: String,
        cancel: () -> Unit
    ) {
        val correlation = CorrelationId.newId()
        val moveFace = if (retract) direction.oppositeFace else direction
        val pairs = movedBlocks.map { block -> block.toHomeClaimPosition() to block.relativePosition(moveFace) }
            .ifEmpty { listOf(piston.toHomeClaimPosition() to piston.relativePosition(direction)) }
        val decisions = pairs.map { (source, target) ->
            val request = adapter.request(
                actor = ActionActor.Automation("piston:${piston.toHomeClaimPosition()}", "minecraft:piston"),
                action = Action.PISTON_MOVE.key,
                source = source,
                target = target,
                event = eventName,
                attributes = mapOf("piston" to piston.type.name, "sticky" to (piston.blockData.material.name.contains("STICKY")).toString()),
                correlationId = correlation
            )
            // Each moved block has its own source boundary. Preserve both endpoints
            // so a piston cannot move a protected block across a denied boundary.
            adapter.check(request, checkBothEnds = true)
        }
        val bulk = BulkActionRequest(
            action = ActionRequest(
                actor = ActionActor.Automation("piston:${piston.toHomeClaimPosition()}", "minecraft:piston"),
                actionKey = Action.PISTON_MOVE.key,
                context = systems.diath.homeclaim.core.policy.ActionContext(platform = "paper", eventName = eventName),
                correlationId = correlation
            ),
            targets = pairs.map { (_, target) -> ActionEndpoint(target) }
        )
        val result = BulkActionResult(correlation, decisions)
        if (!result.allowed) {
            cancel()
            adapter.auditBulk(bulk, result)
        }
    }

    private fun checkAcross(
        actor: ActionActor,
        action: Action,
        source: systems.diath.homeclaim.core.model.Position?,
        target: systems.diath.homeclaim.core.model.Position,
        eventName: String,
        cancel: () -> Unit,
        player: Player? = null,
        attributes: Map<String, String> = emptyMap()
    ) {
        val request = adapter.request(actor, action.key, source, target, eventName, attributes = attributes)
        val decision = adapter.check(request)
        if (decision.effect == DecisionEffect.DENY) {
            cancel()
            player?.let { adapter.deny(it, decision) }
            adapter.auditDenied(request, decision)
        }
    }

    private fun evaluateGrowth(
        actor: ActionActor,
        source: systems.diath.homeclaim.core.model.Position,
        targets: List<systems.diath.homeclaim.core.model.Position>,
        event: String,
        attributes: Map<String, String>,
        cancel: () -> Unit
    ) {
        if (targets.isEmpty()) return
        val bulk = adapter.bulkRequest(
            actor = actor,
            action = Action.BLOCK_GROW.key,
            source = source,
            targets = targets,
            event = event,
            attributes = attributes
        )
        val result = adapter.checkBulk(bulk)
        if (!result.allowed) {
            cancel()
            adapter.auditBulk(bulk, result)
        }
    }
}

private fun Inventory.protectionPosition(): systems.diath.homeclaim.core.model.Position? {
    location?.let { return it.toHomeClaimPosition() }
    return when (val owner = holder) {
        is Entity -> owner.location.toHomeClaimPosition()
        is BlockState -> owner.block.toHomeClaimPosition()
        else -> null
    }
}
