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
    ): Decision {
        val resolvedArea = extraContext["areaId"] as? AreaId ?: position?.let { pos ->
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
                context = DecisionContext(playerId, null, action, position.worldId, extraContext),
                source = DecisionSource.SCOPE
            )
        }

        val regionId = extraContext["regionId"] as? RegionId ?: position?.let {
            if (resolvedArea != null) regionService.getRegionAt(resolvedArea, it.x, it.y, it.z)
            else regionService.getRegionAt(it.worldId, it.x, it.y, it.z)
        }
        val region = regionId?.let(regionService::getRegionById)
        if (regionId == null || region == null) {
            return decision(
                DecisionEffect.DENY, DecisionReason.NO_REGION, "No region at position", playerId, null,
                action, position, extraContext, DecisionSource.SCOPE, areaId = resolvedArea
            )
        }

        val role = resolveRole(regionId, playerId)
        val actor = PolicyActorContext.from(extraContext).let {
            if (it.kind == ActorKind.PLAYER && it.actorId == null) it.copy(actorId = playerId.toString()) else it
        }
        val permissionContext = PermissionContext(
            worldId = position?.worldId,
            areaId = resolvedArea ?: region.areaId,
            resourceType = if (extraContext["componentId"] != null) "component" else "region"
        )
        val protectionBypass = has(actor, PolicyPermissions.PROTECTION_BYPASS, permissionContext) || extraContext["protectionBypass"] == true
        val entryBypass = has(actor, PolicyPermissions.ENTRY_BYPASS, permissionContext) || extraContext["entryBypass"] == true
        val lockedBypass = protectionBypass || has(actor, PolicyPermissions.LOCKED_POLICY_BYPASS, permissionContext)

        if (protectionBypass) {
            return decision(
                DecisionEffect.ALLOW, DecisionReason.BYPASS_PROTECTION, null, playerId, regionId,
                action, position, extraContext, DecisionSource.BYPASS, role, areaId = resolvedArea ?: region.areaId,
                trace = DecisionTrace(role = role.name, bypass = PolicyPermissions.PROTECTION_BYPASS,
                    permissionResult = PermissionResult.GRANTED)
            )
        }

        val playerName = extraContext["playerName"]?.toString()
        val entryAction = action == Action.REGION_ENTER || action == Action.REGION_TELEPORT_ENTER
        if (role == RegionRole.BANNED && !(entryAction && entryBypass)) {
            return decision(
                DecisionEffect.DENY, DecisionReason.ROLE_BANNED, null, playerId, regionId, action,
                position, extraContext, DecisionSource.ROLE, role, areaId = resolvedArea ?: region.areaId
            )
        }
        if (entryAction && entryBypass) {
            return decision(
                DecisionEffect.ALLOW, DecisionReason.BYPASS_ENTRY, null, playerId, regionId,
                action, position, extraContext, DecisionSource.BYPASS, role,
                areaId = resolvedArea ?: region.areaId,
                trace = DecisionTrace(role = role.name, bypass = PolicyPermissions.ENTRY_BYPASS,
                    permissionResult = PermissionResult.GRANTED)
            )
        }
        if (entryAction && !entryBypass) {
            val deny = entryDenyService?.findActiveDeny(regionId, playerId, playerName)
            if (deny != null) {
                return decision(
                    DecisionEffect.DENY, DecisionReason.ENTRY_DENY,
                    "entry_deny_id=${deny.id.value};reason=${deny.reason}", playerId, regionId,
                    action, position, extraContext, DecisionSource.POLICY, role,
                    areaId = resolvedArea ?: region.areaId,
                    trace = DecisionTrace(role = role.name, policyFlag = "entry_deny")
                )
            }
        }
        val profile = resolveProfile(extraContext)
        val regionWithProfile = if (profile != null) region.copy(
            flags = region.flags + profile.flags,
            limits = region.limits + profile.limits
        ) else region
        val zones = position?.let { pos ->
            resolvedArea?.let { zoneService.getZonesAt(it, pos) } ?: zoneService.getZonesAt(pos.worldId, pos)
        } ?: emptyList()
        val policy = RegionPolicy.merge(zones, regionWithProfile)
        val actorDefaults = ModPolicyDefaults.from(extraContext)
        if (!ModProtectionRules.isActorAllowed(regionWithProfile, actor, actorDefaults)) {
            return decision(
                DecisionEffect.DENY, DecisionReason.MOD_ACTOR_DENY,
                "actor=${actor.kind.name}${actor.actorId?.let { ":$it" } ?: ""}", playerId,
                regionId, action, position, extraContext, DecisionSource.POLICY, role,
                areaId = resolvedArea ?: region.areaId
            )
        }

        if (entryAction) {
            return decision(
                DecisionEffect.ALLOW, DecisionReason.ALLOWED, null, playerId, regionId, action,
                position, extraContext, DecisionSource.ROLE, role, areaId = resolvedArea ?: region.areaId,
                trace = DecisionTrace(role = role.name)
            )
        }

        val flag = flagFor(action)
        val effect = flag?.let { RegionPolicy.flagEffect(policy, it) } ?: DecisionEffect.INHERIT
        val locked = flag != null && flag in policy.locked
        val capability = PolicyPermissions.forAction(action)
        val capabilityResult = check(actor, capability, permissionContext)

        // Explicit resource rules beat general permissions and roles. A locked rule is even
        // protected from role ownership; only the named policy bypass can supersede it.
        if (effect == DecisionEffect.DENY && !(locked && lockedBypass)) {
            return decision(
                DecisionEffect.DENY,
                if (flag == FlagCatalog.REDSTONE) DecisionReason.REDSTONE_DENY else DecisionReason.FLAG_DENY,
                "flag=${flag?.value}", playerId, regionId, action, position, extraContext,
                DecisionSource.POLICY, role, areaId = resolvedArea ?: region.areaId,
                trace = DecisionTrace(role.name, flag?.value, locked, capability,
                    permissionResult = capabilityResult)
            )
        }
        if (effect == DecisionEffect.ALLOW) {
            return if (action == Action.COMPONENT_USE) {
                checkComponentCooldown(policy, regionId, playerId, action, position, extraContext, role, DecisionSource.POLICY, resolvedArea ?: region.areaId)
            } else decision(
                DecisionEffect.ALLOW, DecisionReason.ALLOWED, null, playerId, regionId, action,
                position, extraContext, DecisionSource.POLICY, role, areaId = resolvedArea ?: region.areaId,
                trace = DecisionTrace(role.name, flag?.value, locked, capability,
                    permissionResult = capabilityResult)
            )
        }
        if (effect == DecisionEffect.DENY && locked && lockedBypass) {
            return decision(
                DecisionEffect.ALLOW, DecisionReason.BYPASS_LOCKED_POLICY, null, playerId, regionId,
                action, position, extraContext, DecisionSource.BYPASS, role,
                areaId = resolvedArea ?: region.areaId,
                trace = DecisionTrace(role.name, flag.value, true, capability, PolicyPermissions.LOCKED_POLICY_BYPASS,
                    PermissionResult.GRANTED)
            )
        }

        if (role.allowsInheritedAction() || capabilityResult == PermissionResult.GRANTED) {
            val source = if (capabilityResult == PermissionResult.GRANTED && role == RegionRole.VISITOR) DecisionSource.PERMISSION else DecisionSource.ROLE
            return if (action == Action.COMPONENT_USE) {
                checkComponentCooldown(policy, regionId, playerId, action, position, extraContext, role, source, resolvedArea ?: region.areaId)
            } else decision(
                DecisionEffect.ALLOW, DecisionReason.ALLOWED, null, playerId, regionId, action,
                position, extraContext, source, role, areaId = resolvedArea ?: region.areaId,
                trace = DecisionTrace(role.name, flag?.value, locked, capability,
                    permissionResult = capabilityResult)
            )
        }
        return decision(
            DecisionEffect.DENY, if (capabilityResult == PermissionResult.DENIED) DecisionReason.NO_PERMISSION else DecisionReason.ROLE_REQUIRED,
            "requires_role=${minimalAllowedRole(flag)}", playerId, regionId, action, position, extraContext,
            if (capabilityResult == PermissionResult.DENIED) DecisionSource.PERMISSION else DecisionSource.ROLE,
            role, areaId = resolvedArea ?: region.areaId,
            trace = DecisionTrace(role.name, flag?.value, locked, capability,
                permissionResult = capabilityResult)
        )
    }

    private fun checkComponentCooldown(
        policy: EffectivePolicy, regionId: RegionId, playerId: PlayerId, action: Action,
        position: Position?, extra: Map<String, Any?>, role: RegionRole,
        source: DecisionSource, areaId: AreaId
    ): Decision {
        val componentId = extra["componentId"] as? ComponentId
        val cooldownMs = (policy.limits[LimitCatalog.COMPONENT_COOLDOWN_MS] as? PolicyValue.IntValue)?.value ?: 0
        if (cooldownMs > 0 && componentId != null) {
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
        Action.PISTON_MOVE -> FlagCatalog.BUILD
        Action.LIQUID_FLOW -> FlagCatalog.BUILD
        Action.HOPPER_TRANSFER -> FlagCatalog.INTERACT_CONTAINER
        Action.DISPENSER_USE -> FlagCatalog.BUILD
        Action.DISPENSER_ENTITY_USE -> FlagCatalog.ENTITY_DAMAGE
        Action.BLOCK_GROW -> FlagCatalog.BUILD
        Action.FALLING_BLOCK -> FlagCatalog.BUILD
        Action.ENTITY_PLACE -> FlagCatalog.BUILD
        Action.PROJECTILE_LAUNCH -> FlagCatalog.ENTITY_DAMAGE
        Action.HANGING_ENTITY -> FlagCatalog.BUILD
        Action.CAULDRON_CHANGE -> FlagCatalog.INTERACT_BLOCK
        Action.ITEM_DROP -> FlagCatalog.INTERACT_CONTAINER
        Action.ITEM_PICKUP -> FlagCatalog.INTERACT_CONTAINER
        Action.VEHICLE_MOVE -> FlagCatalog.VEHICLE_USE
        Action.REGION_PVP -> FlagCatalog.PVP
        Action.REGION_MOB_GRIEF -> FlagCatalog.MOB_GRIEF
        Action.REGION_ENTITY_DAMAGE -> FlagCatalog.ENTITY_DAMAGE
        Action.REGION_VEHICLE_USE -> FlagCatalog.VEHICLE_USE
        Action.COMPONENT_USE -> FlagCatalog.COMPONENT_USE
        else -> null
    }

    private fun minimalAllowedRole(flag: FlagKey?): String =
        if (flag == FlagCatalog.INTERACT_CONTAINER) RegionRole.TRUSTED.name else RegionRole.MEMBER.name

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
        effect: DecisionEffect, reason: String, detail: String?, playerId: PlayerId, regionId: RegionId?,
        action: Action, position: Position?, extra: Map<String, Any?>, source: DecisionSource,
        role: RegionRole? = null, areaId: AreaId? = null, trace: DecisionTrace = DecisionTrace()
    ) = Decision(
        effect, reason, detail,
        DecisionContext(playerId, regionId, action, position?.worldId, extra, areaId),
        source, trace.copy(role = trace.role ?: role?.name)
    )
}
