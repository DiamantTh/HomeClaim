package systems.diath.homeclaim.core.platform

import systems.diath.homeclaim.core.model.*
import systems.diath.homeclaim.core.policy.*
import systems.diath.homeclaim.core.service.AreaService
import systems.diath.homeclaim.core.service.PolicyService
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.core.service.WorldAreaService
import java.util.UUID

/** Adapter boundary from a typed action request to the existing canonical policy service. */
class PolicyGuard(
    private val policyService: PolicyService,
    private val regionService: RegionService,
    private val areaService: AreaService? = null
) {
    fun check(request: ActionRequest): Decision {
        val action = ActionKey.toLegacy(request.actionKey)
        val location = request.target?.position ?: request.source?.position
        val actorId = (request.actor as? ActionActor.Player)?.playerId
        if (action == null) {
        return Decision(
                effect = DecisionEffect.DENY,
                reason = DecisionReason.UNSUPPORTED_ACTION,
                detail = "No HomeClaim policy is registered for ${request.actionKey.value}",
                context = DecisionContext(
                    actorId, null, Action.REGION_BUILD, request.worldId,
                    request.context.toLegacyMap(), actionKey = request.actionKey, actionRequest = request
                ),
                source = DecisionSource.POLICY
            )
        }

        val validationError = request.validationError()
        if (validationError != null || (location == null && request.target?.resource !is ActionResourceRef.Region)) {
            return Decision(
                effect = DecisionEffect.DENY,
                reason = DecisionReason.INVALID_ACTION_REQUEST,
                detail = validationError ?: "A policy position or region resource is required",
                context = DecisionContext(
                    actorId, null, action, request.worldId, request.context.toLegacyMap(),
                    actionKey = request.actionKey, actionRequest = request
                ),
                source = DecisionSource.SCOPE
            )
        }

        val target = request.target
        val source = request.source
        val policyEndpoint = target ?: source
        val targetRegionId = request.regionId ?: (policyEndpoint?.resource as? ActionResourceRef.Region)?.regionId
        val targetAreaId = request.areaId ?: policyEndpoint?.areaId
            ?: (policyEndpoint?.resource as? ActionResourceRef.Area)?.areaId
        val targetArea = targetAreaId?.let { areaService?.getArea(it) }
            ?: if (targetAreaId == null) policyEndpoint?.position?.let(::resolveArea) else null
        val areaId = targetAreaId ?: targetArea?.id
        if (areaService != null && location != null && areaId == null) {
            return notApplicable(request, actorId, action, location)
        }

        val regionId = targetRegionId ?: policyEndpoint?.position?.let { position ->
            areaId?.let { regionService.getRegionAt(it, position.x, position.y, position.z) }
                ?: if (areaService == null) regionService.getRegionAt(position.worldId, position.x, position.y, position.z) else null
        }
        val sourceAreaId = source?.areaId ?: (source?.resource as? ActionResourceRef.Area)?.areaId
            ?: source?.position?.let(::resolveArea)?.id
        val sourceRegionId = (source?.resource as? ActionResourceRef.Region)?.regionId
            ?: source?.position?.let { position ->
                sourceAreaId?.let { regionService.getRegionAt(it, position.x, position.y, position.z) }
                    ?: if (areaService == null) regionService.getRegionAt(position.worldId, position.x, position.y, position.z) else null
            }
        val context = request.context.toLegacyMap() + mapOf(
            "regionId" to regionId,
            "areaId" to areaId,
            PolicyActorContext.EXTRA_KIND to request.actor.toPolicyKind().name,
            PolicyActorContext.EXTRA_ID to request.actor.stableId,
            PolicyActorContext.EXTRA_SOURCE_MOD to (request.actor as? ActionActor.Automation)?.source
        )

        val canonicalWorldId = targetArea?.worldId ?: areaId?.let { areaService?.getArea(it)?.worldId }
        val policyLocation = location?.let { position ->
            canonicalWorldId?.let { position.copy(worldId = it) } ?: position
        }

        val decision = policyService.evaluateForActor(
            (request.actor as? ActionActor.Player)?.playerId,
            action,
            policyLocation,
            request.actor.toPolicyContext(),
            context
        )
        val resolvedRequest = request.copy(
            regionId = decision.context.regionId ?: regionId,
            areaId = decision.context.areaId ?: areaId,
            source = source?.copy(
                position = source.position?.let { canonicalizePosition(it, sourceAreaId) },
                areaId = sourceAreaId,
                resource = sourceRegionId?.let { ActionResourceRef.Region(it) } ?: source.resource
            ),
            target = request.target?.let { endpoint ->
                endpoint.copy(
                    position = endpoint.position?.let { canonicalizePosition(it, decision.context.areaId ?: areaId) },
                    areaId = decision.context.areaId ?: areaId,
                    resource = endpoint.resource ?: regionId?.let { ActionResourceRef.Region(it) }
                )
            }
        )
        return decision.copy(
            context = decision.context.copy(
                actionKey = request.actionKey,
                actionRequest = resolvedRequest
            )
        )
    }

    /**
     * Evaluates both ends of a cross-boundary action through the same policy service.
     * An unmanaged endpoint contributes NOT_APPLICABLE and cannot turn a managed endpoint
     * into an implicit allow or deny.
     */
    fun checkAcross(request: ActionRequest): Decision {
        val source = request.source ?: return check(request)
        val target = request.target ?: return check(request)
        if (source == target) return check(request)

        val sourceDecision = check(
            request.copy(source = null, target = source, areaId = null, regionId = null)
        )
        val targetDecision = check(
            request.copy(target = target, areaId = null, regionId = null)
        )
        return combineBoundaryDecisions(request, sourceDecision, targetDecision)
    }

    fun checkBulk(request: BulkActionRequest): BulkActionResult {
        val base = request.action.copy(correlationId = request.correlationId)
        val source = base.source
        val sourceDecision = source?.takeUnless { it == base.target }?.let {
            check(base.copy(source = null, target = it, areaId = null, regionId = null))
        }
        val decisionsByEndpoint = HashMap<ActionEndpoint, Decision>()
        val decisions = request.targets.map { target ->
            val targetDecision = decisionsByEndpoint.getOrPut(target) {
                check(base.copy(target = target, areaId = null, regionId = null))
            }
            if (sourceDecision == null) {
                targetDecision
            } else {
                combineBoundaryDecisions(base.copy(target = target), sourceDecision, targetDecision)
            }
        }
        return BulkActionResult(request.correlationId, decisions)
    }

    private fun combineBoundaryDecisions(
        request: ActionRequest,
        first: Decision,
        second: Decision
    ): Decision {
        val selected = when {
            first.effect == DecisionEffect.DENY -> first
            second.effect == DecisionEffect.DENY -> second
            first.effect == DecisionEffect.ALLOW -> first
            second.effect == DecisionEffect.ALLOW -> second
            else -> first
        }
        return selected.copy(
            context = selected.context.copy(actionRequest = request)
        )
    }

    fun check(action: Action, position: Position, extra: Map<String, Any?> = emptyMap()): Decision = check(
        ActionRequest(
            actor = (extra["playerId"] as? UUID)?.let { ActionActor.Player(it) } ?: extra.toActionActor(),
            actionKey = action.key,
            target = ActionEndpoint(position),
            context = ActionContext.fromLegacy(extra)
        )
    )

    fun onBlockPlace(ctx: BlockEventContext): Decision = check(
        ActionRequest(
            actor = ActionActor.Player(ctx.playerId),
            actionKey = Action.REGION_BUILD.key,
            target = ActionEndpoint(ctx.position),
            context = ActionContext(attributes = mapOf("blockType" to ctx.blockType), platform = "paper")
        )
    )

    fun onBlockBreak(ctx: BlockEventContext): Decision = check(
        ActionRequest(
            actor = ActionActor.Player(ctx.playerId),
            actionKey = Action.REGION_BREAK.key,
            target = ActionEndpoint(ctx.position),
            context = ActionContext(attributes = mapOf("blockType" to ctx.blockType), platform = "paper")
        )
    )

    fun onBlockInteract(ctx: InteractEventContext, isContainer: Boolean): Decision {
        val action = if (isContainer) Action.REGION_INTERACT_CONTAINER else Action.REGION_INTERACT_BLOCK
        return check(
            ActionRequest(
                actor = ActionActor.Player(ctx.playerId),
                actionKey = action.key,
                target = ActionEndpoint(ctx.position),
                context = ActionContext(
                    attributes = mapOf("targetType" to (ctx.targetType ?: "unknown")),
                    platform = "paper"
                )
            )
        )
    }

    fun onRegionEnter(
        playerId: UUID,
        position: Position,
        playerName: String? = null,
        teleport: Boolean = false,
        entryBypass: Boolean = false,
        extra: Map<String, Any?> = emptyMap(),
        source: Position? = null
    ): Decision {
        val action = if (teleport) Action.REGION_TELEPORT_ENTER else Action.REGION_ENTER
        return check(
            ActionRequest(
                actor = ActionActor.Player(playerId),
                actionKey = action.key,
                source = source?.let(::ActionEndpoint),
                target = ActionEndpoint(position),
                context = ActionContext.fromLegacy(extra).copy(
                    platform = extra["platform"]?.toString() ?: "paper",
                    playerName = playerName,
                    entryBypass = entryBypass || extra["entryBypass"] == true
                )
            )
        )
    }

    fun resolveRegionAt(position: Position): RegionId? {
        val area = resolveArea(position)
        if (area != null) return regionService.getRegionAt(area.id, position.x, position.y, position.z)
        if (areaService != null) return null
        return regionService.getRegionAt(position.worldId, position.x, position.y, position.z)
    }

    /** Resolves an endpoint once so event adapters can compare or reuse its spatial context. */
    fun resolveEndpointAt(position: Position): ActionEndpoint {
        val area = resolveArea(position)
        if (areaService != null && area == null) return ActionEndpoint(canonicalizePosition(position, null))
        val regionId = if (area != null) {
            regionService.getRegionAt(area.id, position.x, position.y, position.z)
        } else if (areaService == null) {
            regionService.getRegionAt(position.worldId, position.x, position.y, position.z)
        } else null
        return ActionEndpoint(
            position = canonicalizePosition(position, area?.id),
            resource = regionId?.let(ActionResourceRef::Region),
            areaId = area?.id
        )
    }

    private fun resolveArea(position: Position): Area? = areaService?.let { service ->
        val boundWorld = (service as? WorldAreaService)?.getWorldByPlatformId(position.worldId.value)
        boundWorld?.let { service.resolveArea(it.id, position.x, position.y, position.z) }
            ?: position.legacyWorldName?.let { service.resolveArea(it, position.x, position.y, position.z) }
            ?: service.resolveArea(position.worldId, position.x, position.y, position.z)
    }

    private fun canonicalizePosition(position: Position, areaId: AreaId?): Position {
        val worldService = areaService as? WorldAreaService
        val homeClaimWorldId = areaId?.let { areaService?.getArea(it)?.worldId }
            ?: worldService?.getWorldByPlatformId(position.worldId.value)?.id
            ?: position.legacyWorldName?.let { worldService?.getWorldByName(it)?.id }
            ?: return position
        return position.copy(worldId = homeClaimWorldId)
    }

    private fun notApplicable(request: ActionRequest, actorId: UUID?, action: Action, position: Position): Decision {
        val canonicalPosition = canonicalizePosition(position, null)
        val canonicalRequest = request.copy(
            source = request.source?.copy(position = request.source.position?.let { canonicalizePosition(it, request.source.areaId) }),
            target = request.target?.copy(position = request.target.position?.let { canonicalizePosition(it, request.target.areaId) })
        )
        return Decision(
            effect = DecisionEffect.NOT_APPLICABLE,
            reason = DecisionReason.NOT_APPLICABLE,
            detail = "No HomeClaim Area at position",
            context = DecisionContext(
                actorId, null, action, canonicalPosition.worldId, request.context.toLegacyMap(),
                actionKey = request.actionKey, actionRequest = canonicalRequest
            ),
            source = DecisionSource.SCOPE
        )
    }

    private fun Map<String, Any?>.toActionActor(): ActionActor = when (
        PolicyActorContext.from(this).kind
    ) {
        ActorKind.PLAYER -> (this[PolicyActorContext.EXTRA_ID] as? UUID)
            ?.let { ActionActor.Player(it) } ?: ActionActor.System("legacy-unknown-actor")
        ActorKind.FAKE_PLAYER -> ActionActor.Automation(
            this[PolicyActorContext.EXTRA_ID]?.toString() ?: "legacy-fake-player",
            this[PolicyActorContext.EXTRA_SOURCE_MOD]?.toString()
        )
        ActorKind.ENTITY -> (this[PolicyActorContext.EXTRA_ID]?.toString()?.let { runCatching { UUID.fromString(it) }.getOrNull() })
            ?.let { ActionActor.Entity(it, "unknown") } ?: ActionActor.System("legacy-entity")
        ActorKind.AUTOMATION -> ActionActor.Automation(
            this[PolicyActorContext.EXTRA_ID]?.toString() ?: "automation",
            this[PolicyActorContext.EXTRA_SOURCE_MOD]?.toString()
        )
        ActorKind.SERVER_TASK -> ActionActor.System(this[PolicyActorContext.EXTRA_ID]?.toString() ?: "server-task")
    }

    private fun ActionActor.toPolicyKind(): ActorKind = when (this) {
        is ActionActor.Player -> ActorKind.PLAYER
        is ActionActor.Entity -> ActorKind.ENTITY
        is ActionActor.Automation -> ActorKind.AUTOMATION
        is ActionActor.System -> ActorKind.SERVER_TASK
    }

    private fun ActionActor.toPolicyContext(): PolicyActorContext = PolicyActorContext(
        kind = toPolicyKind(),
        actorId = stableId,
        sourceMod = (this as? ActionActor.Automation)?.source
    )

}
