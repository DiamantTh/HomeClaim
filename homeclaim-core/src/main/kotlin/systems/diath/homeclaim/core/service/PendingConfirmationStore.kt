package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.ActionKey
import systems.diath.homeclaim.core.policy.CorrelationId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Short-lived, actor-bound confirmation state for a future destructive command. */
class PendingConfirmationStore(private val clock: Clock = Clock.systemUTC()) {
    private val pending = ConcurrentHashMap<String, PendingConfirmation>()

    fun create(actor: ActionActor, operation: ActionKey, targetSignature: String, stateSignature: String,
               lifetime: Duration = Duration.ofSeconds(30), correlationId: CorrelationId = CorrelationId.newId()): PendingConfirmation {
        require(!lifetime.isNegative && !lifetime.isZero) { "Confirmation lifetime must be positive" }
        val value = PendingConfirmation(actor.stableId ?: actor.kind, operation, targetSignature, stateSignature,
            clock.instant(), clock.instant().plus(lifetime), correlationId)
        pending[actorKey(actor)] = value
        return value
    }

    fun consume(actor: ActionActor, operation: ActionKey, targetSignature: String, stateSignature: String): PendingConfirmation? {
        val key = actorKey(actor)
        val value = pending.remove(key) ?: return null
        if (value.expiresAt <= clock.instant() || value.operation != operation || value.targetSignature != targetSignature || value.stateSignature != stateSignature) return null
        return value
    }

    fun invalidate(actor: ActionActor) { pending.remove(actorKey(actor)) }

    private fun actorKey(actor: ActionActor): String = "${actor.kind}:${actor.stableId ?: actor.kind}"
}

data class PendingConfirmation(
    val actorId: String,
    val operation: ActionKey,
    val targetSignature: String,
    val stateSignature: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val correlationId: CorrelationId
)
