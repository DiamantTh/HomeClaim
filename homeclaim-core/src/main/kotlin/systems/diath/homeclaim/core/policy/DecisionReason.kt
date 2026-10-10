package systems.diath.homeclaim.core.policy

object DecisionReason {
    const val ALLOWED = "ALLOWED"
    const val NOT_APPLICABLE = "NOT_APPLICABLE"
    const val NO_REGION = "NO_REGION"
    const val NO_PERMISSION = "NO_PERMISSION"
    const val FLAG_DENY = "FLAG_DENY"
    const val ROLE_BANNED = "ROLE_BANNED"
    const val COOLDOWN_ACTIVE = "COOLDOWN_ACTIVE"
    const val ROLE_REQUIRED = "ROLE_REQUIRED"
    const val REDSTONE_DENY = "REDSTONE_DENY"
    const val MOD_ACTOR_DENY = "MOD_ACTOR_DENY"
    const val ENTRY_DENY = "ENTRY_DENY"
    const val BYPASS_PROTECTION = "BYPASS_PROTECTION"
    const val BYPASS_ENTRY = "BYPASS_ENTRY"
    const val BYPASS_LOCKED_POLICY = "BYPASS_LOCKED_POLICY"
    const val UNSUPPORTED_ACTION = "UNSUPPORTED_ACTION"
    const val INVALID_ACTION_REQUEST = "INVALID_ACTION_REQUEST"
}
