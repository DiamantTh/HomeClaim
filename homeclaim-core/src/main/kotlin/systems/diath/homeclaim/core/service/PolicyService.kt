package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionRole
import systems.diath.homeclaim.core.policy.Decision
import systems.diath.homeclaim.core.policy.Action
import systems.diath.homeclaim.core.policy.PolicyActorContext
import systems.diath.homeclaim.core.policy.DecisionEffect
import systems.diath.homeclaim.core.policy.DecisionReason
import systems.diath.homeclaim.core.policy.DecisionSource
import systems.diath.homeclaim.core.policy.DecisionContext

interface PolicyService {
    fun evaluate(
        playerId: PlayerId,
        action: Action,
        position: Position?,
        extraContext: Map<String, Any?> = emptyMap()
    ): Decision

    fun evaluateWithActor(
        playerId: PlayerId,
        action: Action,
        position: Position?,
        actor: PolicyActorContext,
        extraContext: Map<String, Any?> = emptyMap()
    ): Decision {
        return evaluate(
            playerId = playerId,
            action = action,
            position = position,
            extraContext = extraContext + mapOf(
                PolicyActorContext.EXTRA_KIND to actor.kind.name,
                PolicyActorContext.EXTRA_ID to actor.actorId,
                PolicyActorContext.EXTRA_SOURCE_MOD to actor.sourceMod
            )
        )
    }

    /** Actor-aware evaluation without inventing a player identity for non-player causes. */
    fun evaluateForActor(
        playerId: PlayerId?,
        action: Action,
        position: Position?,
        actor: PolicyActorContext,
        extraContext: Map<String, Any?> = emptyMap()
    ): Decision {
        if (playerId != null) return evaluateWithActor(playerId, action, position, actor, extraContext)
        return Decision(
            effect = DecisionEffect.DENY,
            reason = DecisionReason.ACTOR_NOT_SUPPORTED,
            detail = "Policy service does not support non-player actor evaluation",
            context = DecisionContext(null, null, action, position?.worldId, extraContext),
            source = DecisionSource.SCOPE
        )
    }

    fun resolveRole(regionId: RegionId, playerId: PlayerId): RegionRole
}
