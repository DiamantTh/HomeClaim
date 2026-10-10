package systems.diath.homeclaim.core.policy

import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Position

/**
 * Platform-neutral request envelope for policy evaluation.
 *
 * Platform and external adapters translate native events to this model,
 * while core decides based on shared logic.
 */
@Deprecated("Use ActionRequest with typed actor, endpoints, and correlation")
data class PolicyActionRequest(
    val action: Action,
    val position: Position,
    val actor: PolicyActorContext,
    val playerId: PlayerId? = null,
    val platform: String = "unknown",
    val extra: Map<String, Any?> = emptyMap(),
    /** Optional origin for older adapters; [position] remains their target position. */
    val source: Position? = null
)
