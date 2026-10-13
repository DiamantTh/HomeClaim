package systems.diath.homeclaim.core.policy

import systems.diath.homeclaim.core.model.*
import systems.diath.homeclaim.core.permission.CapabilityPermissionProvider
import systems.diath.homeclaim.core.permission.PermissionContext
import systems.diath.homeclaim.core.permission.PermissionResult
import systems.diath.homeclaim.core.permission.PolicyPermissions
import systems.diath.homeclaim.core.service.*
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Resolves scope, hard restrictions, resource policy, role defaults and capabilities in one place. */
class SimplePolicyService(
    private val regionService: RegionService,
    private val zoneService: ZoneService,
    private val roleResolver: (RegionId, PlayerId) -> RegionRole,
    private val flagProfileService: FlagProfileService? = null,
    private val entryDenyService: EntryDenyService? = null,
    private val areaService: AreaService? = null,
    private val permissionProvider: CapabilityPermissionProvider? = null
) : PolicyService {
    private val componentCooldowns = ConcurrentHashMap<Pair<RegionId, Pair<ComponentId, PlayerId>>, Instant>()

    override fun resolveRole(regionId: RegionId, playerId: PlayerId): RegionRole = roleResolver(regionId, playerId)

    override fun evaluate(
        playerId: PlayerId,
        action: Action,
        position: Position?,
        extraContext: Map<String, Any?>
    ): Decision = evaluateForActor(
        playerId, action, position,
        PolicyActorContext(kind = ActorKind.PLAYER, actorId = playerId.toString()),
        extraContext
    )

    override fun evaluateWithActor(
        playerId: PlayerId,
        action: Action,
        position: Position?,
        actor: PolicyActorContext,
        extraContext: Map<String, Any?>
    ): Decision = evaluateForActor(playerId, action, position, actor, extraContext)

    override fun evaluateForActor(
        playerId: PlayerId?,
        action: Action,
        position: Position?,
        actor: PolicyActorContext,
        extraContext: Map<String, Any?>
    ): Decision {
        val decisionContext = extraContext + mapOf(
            PolicyActorContext.EXTRA_KIND to actor.kind.name,
            PolicyActorContext.EXTRA_ID to actor.actorId,
            PolicyActorContext.EXTRA_SOURCE_MOD to actor.sourceMod
        )
        val resolvedArea = decisionContext["areaId"] as? AreaId ?: position?.let { pos ->
            areaService?.let { service ->
                pos.legacyWorldName?.let { service.resolveArea(it, pos.x, pos.y, pos.z)?.id }
                    ?: service.resolveArea(pos.worldId, pos.x, pos.y, pos.z)?.id
            }
        }
        if (position != null && areaService != null && resolvedArea == null) {
            return Decision(
                effect = DecisionEffect.NOT_APPLICABLE,
                reason = DecisionReason.NOT_APPLICABLE,
                detail = "No HomeClaim Area at position",
                context = DecisionContext(playerId, null, action, position.worldId, decisionContext),
                source = DecisionSource.SCOPE
            )
        }

        val regionId = decisionContext["regionId"] as? RegionId ?: position?.let {
            if (resolvedArea != null) regionService.getRegionAt(resolvedArea, it.x, it.y, it.z)
            else regionService.getRegionAt(it.worldId, it.x, it.y, it.z)
        }
        val region = regionId?.let(regionService::getRegionById)
        if (regionId == null || region == null) {
            return decision(
                DecisionEffect.DENY, DecisionReason.NO_REGION, "No region at position", playerId, null,
                action, position, decisionContext, DecisionSource.SCOPE, areaId = resolvedArea
            )
        }

        val role = if (playerId != null && actor.kind == ActorKind.PLAYER) {
            resolveRole(regionId, playerId)
        } else RegionRole.VISITOR
        val permissionContext = PermissionContext(
            worldId = position?.worldId,
            areaId = resolvedArea ?: region.areaId,
            resourceType = if (decisionContext["componentId"] != null) "component" else "region"
        )
        val protectionBypass = has(actor, PolicyPermissions.PROTECTION_BYPASS, permissionContext) || decisionContext["protectionBypass"] == true
        val entryBypass = has(actor, PolicyPermissions.ENTRY_BYPASS, permissionContext) || decisionContext["entryBypass"] == true
        val lockedBypass = protectionBypass || has(actor, PolicyPermissions.LOCKED_POLICY_BYPASS, permissionContext)

        if (protectionBypass) {
            return decision(
                DecisionEffect.ALLOW, DecisionReason.BYPASS_PROTECTION, null, playerId, regionId,
                action, position, decisionContext, DecisionSource.BYPASS, role, areaId = resolvedArea ?: region.areaId,
                trace = DecisionTrace(role = role.name, bypass = PolicyPermissions.PROTECTION_BYPASS,
                    permissionResult = PermissionResult.GRANTED)
            )
        }

        val playerName = decisionContext["playerName"]?.toString()
        val entryAction = action == Action.REGION_ENTER || action == Action.REGION_TELEPORT_ENTER
        if (role == RegionRole.BANNED && !(entryAction && entryBypass)) {
            return decision(
                DecisionEffect.DENY, DecisionReason.ROLE_BANNED, null, playerId, regionId, action,
                position, decisionContext, DecisionSource.ROLE, role, areaId = resolvedArea ?: region.areaId
            )
        }
        if (entryAction && entryBypass) {
            return decision(
                DecisionEffect.ALLOW, DecisionReason.BYPASS_ENTRY, null, playerId, regionId,
                action, position, decisionContext, DecisionSource.BYPASS, role,
                areaId = resolvedArea ?: region.areaId,
                trace = DecisionTrace(role = role.name, bypass = PolicyPermissions.ENTRY_BYPASS,
                    permissionResult = PermissionResult.GRANTED)
            )
        }
        if (entryAction && !entryBypass) {
            val deny = playerId?.let { entryDenyService?.findActiveDeny(regionId, it, playerName) }
            if (deny != null) {
                return decision(
                    DecisionEffect.DENY, DecisionReason.ENTRY_DENY,
                    "entry_deny_id=${deny.id.value};reason=${deny.reason}", playerId, regionId,
                    action, position, decisionContext, DecisionSource.POLICY, role,
                    areaId = resolvedArea ?: region.areaId,
                    trace = DecisionTrace(role = role.name, policyFlag = "entry_deny")
                )
            }
        }
        val profile = resolveProfile(decisionContext)
        val regionWithProfile = if (profile != null) region.copy(
            flags = region.flags + profile.flags,
            limits = region.limits + profile.limits
        ) else region
        val zones = position?.let { pos ->
            resolvedArea?.let { zoneService.getZonesAt(it, pos) } ?: zoneService.getZonesAt(pos.worldId, pos)
        } ?: emptyList()
        val policy = RegionPolicy.merge(zones, regionWithProfile)
        val actorDefaults = ModPolicyDefaults.from(decisionContext)
        if (!ModProtectionRules.isActorAllowed(regionWithProfile, actor, actorDefaults)) {
            return decision(
                DecisionEffect.DENY, DecisionReason.MOD_ACTOR_DENY,
                "actor=${actor.kind.name}${actor.actorId?.let { ":$it" } ?: ""}", playerId,
                regionId, action, position, decisionContext, DecisionSource.POLICY, role,
                areaId = resolvedArea ?: region.areaId
            )
        }

        if (entryAction) {
            return decision(
                DecisionEffect.ALLOW, DecisionReason.ALLOWED, null, playerId, regionId, action,
                position, decisionContext, DecisionSource.ROLE, role, areaId = resolvedArea ?: region.areaId,
                trace = DecisionTrace(role = role.name)
            )
        }

        val resolvedFlag = resolveFlag(policy, action)
        val flag = resolvedFlag?.key
        val effect = resolvedFlag?.effect ?: DecisionEffect.INHERIT
        val locked = resolvedFlag?.locked == true
        val capability = PolicyPermissions.forAction(action)
        val capabilityResult = check(actor, capability, permissionContext)

        // Explicit resource rules beat general permissions and roles. A locked rule is even
        // protected from role ownership; only the named policy bypass can supersede it.
        if (effect == DecisionEffect.DENY && !(locked && lockedBypass)) {
            return decision(
                DecisionEffect.DENY,
                if (flag == FlagCatalog.REDSTONE) DecisionReason.REDSTONE_DENY else DecisionReason.FLAG_DENY,
                "flag=${flag?.value}", playerId, regionId, action, position, decisionContext,
                DecisionSource.POLICY, role, areaId = resolvedArea ?: region.areaId,
                trace = DecisionTrace(role.name, flag?.value, locked, capability,
                    permissionResult = capabilityResult)
            )
        }
        if (effect == DecisionEffect.ALLOW) {
            return if (action == Action.COMPONENT_USE) {
                checkComponentCooldown(policy, regionId, playerId, action, position, decisionContext, role, DecisionSource.POLICY, resolvedArea ?: region.areaId)
            } else decision(
                DecisionEffect.ALLOW, DecisionReason.ALLOWED, null, playerId, regionId, action,
                position, decisionContext, DecisionSource.POLICY, role, areaId = resolvedArea ?: region.areaId,
                trace = DecisionTrace(role.name, flag?.value, locked, capability,
                    permissionResult = capabilityResult)
            )
        }
        if (effect == DecisionEffect.DENY && locked && lockedBypass) {
            return decision(
                DecisionEffect.ALLOW, DecisionReason.BYPASS_LOCKED_POLICY, null, playerId, regionId,
                action, position, decisionContext, DecisionSource.BYPASS, role,
                areaId = resolvedArea ?: region.areaId,
                trace = DecisionTrace(role.name, flag?.value, true, capability, PolicyPermissions.LOCKED_POLICY_BYPASS,
                    PermissionResult.GRANTED)
            )
        }

        if (role.allowsInheritedAction() || capabilityResult == PermissionResult.GRANTED) {
            val source = if (capabilityResult == PermissionResult.GRANTED && role == RegionRole.VISITOR) DecisionSource.PERMISSION else DecisionSource.ROLE
            return if (action == Action.COMPONENT_USE) {
                checkComponentCooldown(policy, regionId, playerId, action, position, decisionContext, role, source, resolvedArea ?: region.areaId)
            } else decision(
                DecisionEffect.ALLOW, DecisionReason.ALLOWED, null, playerId, regionId, action,
                position, decisionContext, source, role, areaId = resolvedArea ?: region.areaId,
                trace = DecisionTrace(role.name, flag?.value, locked, capability,
                    permissionResult = capabilityResult)
            )
        }
        return decision(
            DecisionEffect.DENY, if (capabilityResult == PermissionResult.DENIED) DecisionReason.NO_PERMISSION else DecisionReason.ROLE_REQUIRED,
            "requires_role=${minimalAllowedRole(flag)}", playerId, regionId, action, position, decisionContext,
            if (capabilityResult == PermissionResult.DENIED) DecisionSource.PERMISSION else DecisionSource.ROLE,
            role, areaId = resolvedArea ?: region.areaId,
            trace = DecisionTrace(role.name, flag?.value, locked, capability,
                permissionResult = capabilityResult)
        )
    }

    private fun checkComponentCooldown(
        policy: EffectivePolicy, regionId: RegionId, playerId: PlayerId?, action: Action,
        position: Position?, extra: Map<String, Any?>, role: RegionRole,
        source: DecisionSource, areaId: AreaId
    ): Decision {
        val componentId = extra["componentId"] as? ComponentId
        val cooldownMs = (policy.limits[LimitCatalog.COMPONENT_COOLDOWN_MS] as? PolicyValue.IntValue)?.value ?: 0
        if (cooldownMs > 0 && componentId != null && playerId != null) {
            val key = regionId to (componentId to playerId)
            val now = Instant.now()
            val until = componentCooldowns[key]
            if (until != null && until.isAfter(now)) {
                return decision(DecisionEffect.DENY, DecisionReason.COOLDOWN_ACTIVE,
                    "wait_ms=${until.toEpochMilli() - now.toEpochMilli()}", playerId, regionId,
                    action, position, extra, DecisionSource.POLICY, role, areaId = areaId,
                    trace = DecisionTrace(role = role.name, policyFlag = FlagCatalog.COMPONENT_USE.value))
            }
            componentCooldowns[key] = now.plusMillis(cooldownMs.toLong())
        }
        return decision(DecisionEffect.ALLOW, DecisionReason.ALLOWED, null, playerId, regionId,
            action, position, extra, source, role, areaId = areaId,
            trace = DecisionTrace(role = role.name, policyFlag = FlagCatalog.COMPONENT_USE.value))
    }

    private fun flagFor(action: Action): FlagKey? = when (action) {
        Action.REGION_BUILD -> FlagCatalog.BUILD
        Action.REGION_BREAK -> FlagCatalog.BREAK
        Action.REGION_INTERACT_BLOCK -> FlagCatalog.INTERACT_BLOCK
        Action.REGION_INTERACT_CONTAINER -> FlagCatalog.INTERACT_CONTAINER
        Action.REGION_REDSTONE -> FlagCatalog.REDSTONE
        Action.REGION_FIRE -> FlagCatalog.FIRE_SPREAD
        Action.REGION_EXPLOSION -> FlagCatalog.EXPLOSION_DAMAGE
        Action.PISTON_MOVE -> FlagCatalog.PISTON
        Action.LIQUID_FLOW -> FlagCatalog.LIQUID_FLOW
        Action.HOPPER_TRANSFER -> FlagCatalog.HOPPER_TRANSFER
        Action.DISPENSER_USE -> FlagCatalog.DISPENSER
        Action.DISPENSER_ENTITY_USE -> FlagCatalog.ENTITY_DAMAGE
        Action.BLOCK_GROW -> FlagCatalog.BUILD
        Action.FALLING_BLOCK -> FlagCatalog.BUILD
        Action.ENTITY_PLACE -> FlagCatalog.BUILD
        Action.PROJECTILE_LAUNCH -> FlagCatalog.PROJECTILE
        Action.HANGING_ENTITY -> FlagCatalog.HANGING_ENTITY
        Action.CAULDRON_CHANGE -> FlagCatalog.INTERACT_BLOCK
        Action.ITEM_DROP -> FlagCatalog.ITEM_DROP
        Action.ITEM_PICKUP -> FlagCatalog.ITEM_PICKUP
        Action.VEHICLE_MOVE -> FlagCatalog.VEHICLE_USE
        Action.REGION_PVP -> FlagCatalog.PVP
        Action.REGION_MOB_GRIEF -> FlagCatalog.MOB_GRIEF
        Action.REGION_ENTITY_DAMAGE -> FlagCatalog.ENTITY_DAMAGE
        Action.REGION_VEHICLE_USE -> FlagCatalog.VEHICLE_USE
        Action.COMPONENT_USE -> FlagCatalog.COMPONENT_USE
        else -> null
    }

    /**
     * Dedicated flags refine the older broad controls without silently changing existing
     * installations. Until a dedicated flag is explicitly configured, the action keeps
     * consulting the flag that governed it before the catalog was split. A locked legacy
     * effect remains authoritative so a local specialized rule cannot bypass an area rule.
     */
    private fun resolveFlag(policy: EffectivePolicy, action: Action): ResolvedFlag? {
        val dedicated = flagFor(action) ?: return null
        val dedicatedEffect = RegionPolicy.flagEffect(policy, dedicated)
        val legacy = legacyFlagFor(action)
        val legacyEffect = legacy?.let { RegionPolicy.flagEffect(policy, it) } ?: DecisionEffect.INHERIT
        val legacyLocked = legacy != null && legacy in policy.locked

        if (legacyLocked && legacyEffect != DecisionEffect.INHERIT) {
            return ResolvedFlag(legacy, legacyEffect, locked = true)
        }
        if (dedicatedEffect != DecisionEffect.INHERIT) {
            return ResolvedFlag(dedicated, dedicatedEffect, dedicated in policy.locked)
        }
        return legacy?.let { ResolvedFlag(it, legacyEffect, it in policy.locked) }
            ?: ResolvedFlag(dedicated, dedicatedEffect, dedicated in policy.locked)
    }

    private fun legacyFlagFor(action: Action): FlagKey? = when (action) {
        Action.PISTON_MOVE,
        Action.LIQUID_FLOW,
        Action.DISPENSER_USE -> FlagCatalog.BUILD
        Action.HOPPER_TRANSFER,
        Action.ITEM_DROP,
        Action.ITEM_PICKUP -> FlagCatalog.INTERACT_CONTAINER
        Action.PROJECTILE_LAUNCH -> FlagCatalog.ENTITY_DAMAGE
        Action.HANGING_ENTITY -> FlagCatalog.BUILD
        else -> null
    }

    private data class ResolvedFlag(
        val key: FlagKey,
        val effect: DecisionEffect,
        val locked: Boolean
    )

    private fun minimalAllowedRole(flag: FlagKey?): String = when (flag) {
        FlagCatalog.INTERACT_CONTAINER,
        FlagCatalog.HOPPER_TRANSFER,
        FlagCatalog.ITEM_DROP,
        FlagCatalog.ITEM_PICKUP -> RegionRole.TRUSTED.name
        else -> RegionRole.MEMBER.name
    }

    private fun has(actor: PolicyActorContext, permission: String, context: PermissionContext): Boolean =
        check(actor, permission, context) == PermissionResult.GRANTED

    private fun check(actor: PolicyActorContext, permission: String, context: PermissionContext): PermissionResult =
        permissionProvider?.check(actor, permission, context) ?: PermissionResult.UNDEFINED

    private fun resolveProfile(extra: Map<String, Any?>): FlagProfile? = when (val profile = extra["flagProfile"]) {
        is FlagProfile -> profile
        is String -> flagProfileService?.getProfile(profile)
        else -> null
    }

    private fun decision(
        effect: DecisionEffect, reason: String, detail: String?, playerId: PlayerId?, regionId: RegionId?,
        action: Action, position: Position?, extra: Map<String, Any?>, source: DecisionSource,
        role: RegionRole? = null, areaId: AreaId? = null, trace: DecisionTrace = DecisionTrace()
    ) = Decision(
        effect, reason, detail,
        DecisionContext(playerId, regionId, action, position?.worldId, extra, areaId),
        source, trace.copy(role = trace.role ?: role?.name)
    )
}
