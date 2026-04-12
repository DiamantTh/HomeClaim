package systems.diath.homeclaim.core.policy

/**
 * Describes who/what is performing an action.
 *
 * This is important for modded servers where many interactions are not direct
 * player actions (e.g. fake players or automation networks).
 */
enum class ActorKind {
    PLAYER,
    FAKE_PLAYER,
    AUTOMATION,
    SERVER_TASK,
    ENTITY
}

data class PolicyActorContext(
    val kind: ActorKind = ActorKind.PLAYER,
    val actorId: String? = null,
    val sourceMod: String? = null
) {
    companion object {
        const val EXTRA_KIND = "actorKind"
        const val EXTRA_ID = "actorId"
        const val EXTRA_SOURCE_MOD = "sourceMod"

        fun from(extra: Map<String, Any?>): PolicyActorContext {
            val rawKind = extra[EXTRA_KIND]?.toString()?.uppercase()
            val kind = rawKind?.let { runCatching { ActorKind.valueOf(it) }.getOrNull() } ?: ActorKind.PLAYER
            return PolicyActorContext(
                kind = kind,
                actorId = extra[EXTRA_ID]?.toString(),
                sourceMod = extra[EXTRA_SOURCE_MOD]?.toString()
            )
        }
    }
}
