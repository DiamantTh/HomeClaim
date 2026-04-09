package systems.diath.homeclaim.core.platform

import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.policy.Action
import systems.diath.homeclaim.core.policy.Decision
import systems.diath.homeclaim.core.policy.DecisionContext
import systems.diath.homeclaim.core.policy.DecisionReason
import systems.diath.homeclaim.core.service.PolicyService
import systems.diath.homeclaim.core.service.RegionService

class PolicyGuard(
    private val policyService: PolicyService,
    private val regionService: RegionService
    ) {
    fun check(action: Action, position: Position, extra: Map<String, Any?> = emptyMap()): Decision {
        val regionId = resolveRegion(position)
        return policyService.evaluate(
            playerId = extra["playerId"] as? java.util.UUID ?: return Decision(
                allowed = false,
                reason = DecisionReason.NO_REGION,
                detail = "Missing playerId",
                context = DecisionContext(java.util.UUID(0,0), regionId, action, position.world, extra)
            ),
            action = action,
            position = position,
            extraContext = extra + ("regionId" to regionId)
        )
    }

    fun onBlockPlace(ctx: BlockEventContext): Decision {
        val regionId = resolveRegion(ctx.position)
        return policyService.evaluate(
            playerId = ctx.playerId,
            action = Action.REGION_BUILD,
            position = ctx.position,
            extraContext = mapOf(
                "regionId" to regionId,
                "blockType" to ctx.blockType
            )
        )
    }

    fun onBlockBreak(ctx: BlockEventContext): Decision {
        val regionId = resolveRegion(ctx.position)
        return policyService.evaluate(
            playerId = ctx.playerId,
            action = Action.REGION_BREAK,
            position = ctx.position,
            extraContext = mapOf(
                "regionId" to regionId,
                "blockType" to ctx.blockType
            )
        )
    }

    fun onBlockInteract(ctx: InteractEventContext, isContainer: Boolean): Decision {
        val regionId = resolveRegion(ctx.position)
        val action = if (isContainer) Action.REGION_INTERACT_CONTAINER else Action.REGION_INTERACT_BLOCK
        return policyService.evaluate(
            playerId = ctx.playerId,
            action = action,
            position = ctx.position,
            extraContext = mapOf(
                "regionId" to regionId,
                "targetType" to ctx.targetType
            )
        )
    }

    private fun resolveRegion(position: Position): RegionId? {
        return regionService.getRegionAt(position.world, position.x, position.y, position.z)
    }
}
