package systems.diath.homeclaim.core.policy

/**
 * Global defaults for modded-actor protection behavior.
 *
 * Region metadata can still override these defaults per region.
 */
data class ModPolicyDefaults(
    val allowFakePlayers: Boolean = false,
    val allowAutomation: Boolean = false,
    val allowServerTasks: Boolean = false,
    val allowEntities: Boolean = false
) {
    companion object {
        const val EXTRA_KEY = "modPolicyDefaults"

        fun from(extra: Map<String, Any?>): ModPolicyDefaults {
            return extra[EXTRA_KEY] as? ModPolicyDefaults ?: ModPolicyDefaults()
        }
    }
}
