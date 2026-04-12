package systems.diath.homeclaim.core.platform

import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.policy.Action
import systems.diath.homeclaim.core.policy.ActorKind
import systems.diath.homeclaim.core.policy.Decision
import systems.diath.homeclaim.core.policy.DecisionContext
import systems.diath.homeclaim.core.policy.DecisionReason
import systems.diath.homeclaim.core.policy.PolicyActorContext
import systems.diath.homeclaim.core.service.PolicyService
import systems.diath.homeclaim.core.service.RegionService
import java.util.UUID

class PolicyGuard(
    private val policyService: PolicyService,
    private val regionService: RegionService
    ) {
    fun check(action: Action, position: Position, extra: Map<String, Any?> = emptyMap()): Decision {
        val regionId = resolveRegion(position)
        val actor = PolicyActorContext.from(extra)
        val playerId = extra["playerId"] as? UUID ?: when (actor.kind) {
            ActorKind.PLAYER -> {
                return Decision(
                    allowed = false,
                    reason = DecisionReason.NO_PERMISSION,
                    detail = "Missing playerId for PLAYER actor",
                    context = DecisionContext(SYSTEM_ACTOR, regionId, action, position.world, extra)
                )
            }
            else -> SYSTEM_ACTOR
        }

        return policyService.evaluateWithActor(
            playerId = playerId,
            action = action,
            position = position,
            actor = actor,
            extraContext = extra + ("regionId" to regionId)
        )
    }

    fun onBlockPlace(ctx: BlockEventContext): Decision {
        val regionId = resolveRegion(ctx.position)
        return policyService.evaluateWithActor(
            playerId = ctx.playerId,
            action = Action.REGION_BUILD,
            position = ctx.position,
            actor = PolicyActorContext(kind = ActorKind.PLAYER, actorId = ctx.playerId.toString()),
            extraContext = mapOf(
                "regionId" to regionId,
                "blockType" to ctx.blockType
            )
        )
    }

    fun onBlockBreak(ctx: BlockEventContext): Decision {
        val regionId = resolveRegion(ctx.position)
        return policyService.evaluateWithActor(
            playerId = ctx.playerId,
            action = Action.REGION_BREAK,
            position = ctx.position,
            actor = PolicyActorContext(kind = ActorKind.PLAYER, actorId = ctx.playerId.toString()),
            extraContext = mapOf(
                "regionId" to regionId,
                "blockType" to ctx.blockType
            )
        )
    }

    fun onBlockInteract(ctx: InteractEventContext, isContainer: Boolean): Decision {
        val regionId = resolveRegion(ctx.position)
        val action = if (isContainer) Action.REGION_INTERACT_CONTAINER else Action.REGION_INTERACT_BLOCK
        return policyService.evaluateWithActor(
            playerId = ctx.playerId,
            action = action,
            position = ctx.position,
            actor = PolicyActorContext(kind = ActorKind.PLAYER, actorId = ctx.playerId.toString()),
            extraContext = mapOf(
                "regionId" to regionId,
                "targetType" to ctx.targetType
            )
        )
    }

    private fun resolveRegion(position: Position): RegionId? {
        return regionService.getRegionAt(position.world, position.x, position.y, position.z)
    }

    private companion object {
        val SYSTEM_ACTOR: UUID = UUID(0L, 0L)
    }
}
