package systems.diath.homeclaim.core.policy

import systems.diath.homeclaim.core.model.FlagKey
import systems.diath.homeclaim.core.model.FlagValue
import systems.diath.homeclaim.core.model.LimitKey
import systems.diath.homeclaim.core.model.LimitValue
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.Zone

data class EffectivePolicy(
    val flags: Map<FlagKey, FlagValue>,
    val limits: Map<LimitKey, LimitValue>,
    val locked: Set<FlagKey>
)

object RegionPolicy {
    /**
     * Apply zones (ordered by priority) then region overrides, respecting locked flags.
     */
    fun merge(zones: List<Zone>, region: Region): EffectivePolicy {
        val sortedZones = zones.sortedByDescending { it.priority }
        val flags = mutableMapOf<FlagKey, FlagValue>()
        val limits = mutableMapOf<LimitKey, LimitValue>()
        val locked = mutableSetOf<FlagKey>()

        // First (highest priority) applicable zone wins; collection/SQL ordering is never a rule.
        for (zone in sortedZones) {
            for ((key, value) in zone.defaults.defaultFlags) flags.putIfAbsent(key, value)
            for ((key, value) in zone.defaults.defaultLimits) limits.putIfAbsent(key, value)
            locked += zone.lockedFlags
        }

        for ((k, v) in region.flags) {
            if (!locked.contains(k)) flags[k] = v
        }
        for ((k, v) in region.limits) {
            limits[k] = v
        }

        return EffectivePolicy(flags, limits, locked)
    }

    fun flagEffect(policy: EffectivePolicy, flag: FlagKey): DecisionEffect {
        val value = policy.flags[flag] as? systems.diath.homeclaim.core.model.PolicyValue.Bool
            ?: return DecisionEffect.INHERIT
        return if (value.allowed) DecisionEffect.ALLOW else DecisionEffect.DENY
    }
}
