package systems.diath.homeclaim.core.policy

import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.RegionRole
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.PolicyValue
import systems.diath.homeclaim.core.service.FlagProfileService
import systems.diath.homeclaim.core.service.PolicyService
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.core.service.ZoneService
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal policy implementation: merges zones + region flags, evaluates basic build/break/interact/component actions.
 */
class SimplePolicyService(
    private val regionService: RegionService,
    private val zoneService: ZoneService,
    private val roleResolver: (RegionId, PlayerId) -> RegionRole,
    private val flagProfileService: FlagProfileService? = null
) : PolicyService {
    private val componentCooldowns = ConcurrentHashMap<Pair<RegionId, Pair<systems.diath.homeclaim.core.model.ComponentId, PlayerId>>, Instant>()

    override fun resolveRole(regionId: RegionId, playerId: PlayerId): RegionRole = roleResolver(regionId, playerId)

    override fun evaluate(
        playerId: PlayerId,
        action: Action,
        position: Position?,
        extraContext: Map<String, Any?>
    ): Decision {
        val regionId = extraContext["regionId"] as? RegionId ?: position?.let {
            regionService.getRegionAt(it.world, it.x, it.y, it.z)
        }

        val region = regionId?.let { id -> regionService.getRegionById(id) }
        if (regionId == null || region == null) {
            return Decision(
                allowed = false,
                reason = DecisionReason.NO_REGION,
                detail = "No region at position",
                context = DecisionContext(playerId, null, action, position?.world, extraContext)
            )
        }

        val prof = resolveProfile(extraContext)
        val regionWithProfile = if (prof != null) {
            region.copy(
                flags = region.flags + prof.flags,
                limits = region.limits + prof.limits
            )
        } else region

        val zones = position?.let { zoneService.getZonesAt(it.world, it) } ?: emptyList()
        val effective = RegionPolicy.merge(zones, regionWithProfile)
        val role = resolveRole(regionId, playerId)
        val actor = PolicyActorContext.from(extraContext)
        val defaults = ModPolicyDefaults.from(extraContext)

        if (!ModProtectionRules.isActorAllowed(regionWithProfile, actor, defaults)) {
            return Decision(
                allowed = false,
                reason = DecisionReason.MOD_ACTOR_DENY,
                detail = "actor=${actor.kind.name}${actor.actorId?.let { ":$it" } ?: ""}",
                context = DecisionContext(playerId, regionId, action, position?.world, extraContext)
            )
        }

        val (allowed, reason, detail) = when (action) {
            Action.REGION_BUILD -> checkFlag(effective, FlagCatalog.BUILD, role)
            Action.REGION_BREAK -> checkFlag(effective, FlagCatalog.BREAK, role)
            Action.REGION_INTERACT_BLOCK -> checkFlag(effective, FlagCatalog.INTERACT_BLOCK, role)
            Action.REGION_INTERACT_CONTAINER -> checkFlag(effective, FlagCatalog.INTERACT_CONTAINER, role)
            Action.REGION_REDSTONE -> checkFlag(effective, FlagCatalog.REDSTONE, role)
            Action.REGION_FIRE -> checkFlag(effective, FlagCatalog.FIRE_SPREAD, role)
            Action.REGION_EXPLOSION -> checkFlag(effective, FlagCatalog.EXPLOSION_DAMAGE, role)
            Action.REGION_PVP -> checkFlag(effective, FlagCatalog.PVP, role)
            Action.REGION_MOB_GRIEF -> checkFlag(effective, FlagCatalog.MOB_GRIEF, role)
            Action.REGION_ENTITY_DAMAGE -> checkFlag(effective, FlagCatalog.ENTITY_DAMAGE, role)
            Action.REGION_VEHICLE_USE -> checkFlag(effective, FlagCatalog.VEHICLE_USE, role)
            Action.COMPONENT_USE -> checkComponentUse(effective, role, regionId, playerId, extraContext)
            else -> Triple(true, DecisionReason.ALLOWED, null)
        }

        return Decision(
            allowed = allowed,
            reason = reason,
            detail = detail,
            context = DecisionContext(playerId, regionId, action, position?.world, extraContext)
        )
    }

    private fun checkComponentUse(
        policy: EffectivePolicy,
        role: RegionRole,
        regionId: RegionId,
        playerId: PlayerId,
        extraContext: Map<String, Any?>
    ): Triple<Boolean, String, String?> {
        val componentId = extraContext["componentId"] as? systems.diath.homeclaim.core.model.ComponentId
        val flagResult = checkFlag(policy, FlagCatalog.COMPONENT_USE, role)
        if (!flagResult.first) return Triple(flagResult.first, flagResult.second, null)

        val cooldownVal = policy.limits[LimitCatalog.COMPONENT_COOLDOWN_MS] as? PolicyValue.IntValue
        val cooldownMs = cooldownVal?.value ?: 0
        if (cooldownMs <= 0 || componentId == null) return Triple(true, DecisionReason.ALLOWED, null)

        val key = regionId to (componentId to playerId)
        val now = Instant.now()
        val until = componentCooldowns[key]
        if (until != null && until.isAfter(now)) {
            val remaining = until.toEpochMilli() - now.toEpochMilli()
            return Triple(false, DecisionReason.COOLDOWN_ACTIVE, "wait_ms=$remaining")
        }
        componentCooldowns[key] = now.plusMillis(cooldownMs.toLong())
        return Triple(true, DecisionReason.ALLOWED, null)
    }

    private fun checkFlag(
        policy: EffectivePolicy,
        flag: systems.diath.homeclaim.core.model.FlagKey,
        role: RegionRole
    ): Triple<Boolean, String, String?> {
        if (role == RegionRole.BANNED) return Triple(false, DecisionReason.ROLE_BANNED, null)

        val value = policy.flags[flag] as? PolicyValue.Bool
        val allowed = when (role) {
            RegionRole.OWNER -> true
            RegionRole.TRUSTED -> value?.allowed ?: true
            RegionRole.MEMBER -> value?.allowed ?: true
            RegionRole.VISITOR -> value?.allowed ?: false
            RegionRole.BANNED -> false
        }
        val reason = when {
            allowed -> DecisionReason.ALLOWED
            value?.allowed == false && flag == FlagCatalog.REDSTONE -> DecisionReason.REDSTONE_DENY
            value?.allowed == false -> DecisionReason.FLAG_DENY
            else -> DecisionReason.ROLE_REQUIRED
        }
        val detail = when {
            allowed -> null
            value?.allowed == false -> "flag=${flag.value}"
            else -> "requires_role=${minimalAllowedRole(flag)}"
        }
        return Triple(allowed, reason, detail)
    }

    private fun minimalAllowedRole(flag: systems.diath.homeclaim.core.model.FlagKey): String {
        // Default: allow from MEMBER and above when not explicitly set.
        return when (flag) {
            FlagCatalog.INTERACT_CONTAINER -> RegionRole.TRUSTED.name
            else -> RegionRole.MEMBER.name
        }
    }

    private fun resolveProfile(extraContext: Map<String, Any?>): FlagProfile? {
        val ctxProfile = extraContext["flagProfile"]
        return when (ctxProfile) {
            is FlagProfile -> ctxProfile
            is String -> flagProfileService?.getProfile(ctxProfile)
            else -> null
        }
    }
}
