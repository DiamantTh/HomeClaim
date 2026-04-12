package systems.diath.homeclaim.core.policy

import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Position

/**
 * Platform-neutral request envelope for policy evaluation.
 *
 * Adapters (Paper/Folia/Fabric/NeoForge) translate native events to this model,
 * while core decides based on shared logic.
 */
data class PolicyActionRequest(
    val action: Action,
    val position: Position,
    val actor: PolicyActorContext,
    val playerId: PlayerId? = null,
    val platform: String = "unknown",
    val extra: Map<String, Any?> = emptyMap()
)
