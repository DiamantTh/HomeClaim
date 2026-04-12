package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.policy.Decision

/**
 * Shared helpers for audit payload construction.
 *
 * Keeps adapter payload format consistent across platforms.
 */
object AuditPayloads {
    fun actionPayload(
        position: Position,
        platform: String = "unknown",
        extra: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> {
        return mapOf(
            "world" to position.world,
            "x" to position.x,
            "y" to position.y,
            "z" to position.z,
            "platform" to platform
        ) + extra
    }

    fun worldPayload(
        world: String,
        platform: String = "unknown",
        extra: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> {
        return mapOf(
            "world" to world,
            "platform" to platform
        ) + extra
    }

    fun deniedPolicyPayload(
        position: Position,
        decision: Decision,
        extra: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> {
        return mapOf(
            "world" to position.world,
            "x" to position.x,
            "y" to position.y,
            "z" to position.z,
            "reason" to decision.reason,
            "detail" to decision.detail,
            "action" to decision.context.action.name,
            "platform" to (decision.context.extra["platform"] ?: "unknown")
        ) + extra
    }
}
