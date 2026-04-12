package systems.diath.homeclaim.core.policy

import systems.diath.homeclaim.core.model.Region

/**
 * Modded server hardening rules.
 *
 * Default stance is strict: automation/fake-player actions are denied unless a
 * region explicitly allows them.
 */
object ModProtectionRules {
    const val META_ALLOW_FAKE_PLAYERS = "mod.allow_fake_players"
    const val META_ALLOW_AUTOMATION = "mod.allow_automation"
    const val META_ALLOWED_ACTOR_IDS = "mod.allowed_actor_ids"

    fun isActorAllowed(
        region: Region,
        actor: PolicyActorContext,
        defaults: ModPolicyDefaults = ModPolicyDefaults()
    ): Boolean {
        if (actor.kind == ActorKind.PLAYER) return true

        val allowFake = region.metadata[META_ALLOW_FAKE_PLAYERS].asBool(default = defaults.allowFakePlayers)
        val allowAutomation = region.metadata[META_ALLOW_AUTOMATION].asBool(default = defaults.allowAutomation)
        val allowList = region.metadata[META_ALLOWED_ACTOR_IDS]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

        val actorInAllowList = actor.actorId != null && allowList.contains(actor.actorId)

        return when (actor.kind) {
            ActorKind.FAKE_PLAYER -> allowFake || actorInAllowList
            ActorKind.AUTOMATION -> allowAutomation || actorInAllowList
            ActorKind.SERVER_TASK -> defaults.allowServerTasks || actorInAllowList
            ActorKind.ENTITY -> defaults.allowEntities || actorInAllowList
            ActorKind.PLAYER -> true
        }
    }

    private fun String?.asBool(default: Boolean): Boolean {
        return when (this?.trim()?.lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> default
        }
    }
}
