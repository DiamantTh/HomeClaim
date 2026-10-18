package systems.diath.homeclaim.platform.paper.plot.mutation

import systems.diath.homeclaim.core.model.MergeGroupId
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.CorrelationId
import java.time.Instant
import java.util.UUID

/**
 * Coordinates destructive plot operations for one Paper process.
 *
 * Runtime reservations are deliberately ephemeral: a restart cannot leave an
 * in-memory lock behind. Persisted [PlotMutationJobStatus.RECOVERY_REQUIRED]
 * and RECOVERY_IN_PROGRESS records are checked separately before a new mutation starts, because they
 * describe an uncertain world/domain state rather than an active task.
 */
class RegionMutationConflictCoordinator(
    private val jobStore: PlotMutationJobStore? = null
) {
    private val monitor = Any()
    private val reservationsByRegion = mutableMapOf<RegionId, ActiveReservation>()

    fun reserve(
        operation: RegionMutationOperation,
        regionIds: Collection<RegionId>,
        mergeGroupId: MergeGroupId? = null,
        actor: ActionActor? = null,
        correlationId: CorrelationId = CorrelationId.newId()
    ): ReservationAttempt {
        val targets = regionIds.toSet()
        require(targets.isNotEmpty()) { "A mutation reservation requires at least one region" }

        val recoveryConflict = recoveryConflict(targets)
        if (recoveryConflict != null) return ReservationAttempt.Conflict(recoveryConflict)

        synchronized(monitor) {
            val active = targets.asSequence().mapNotNull(reservationsByRegion::get).firstOrNull()
            if (active != null) {
                return ReservationAttempt.Conflict(
                    MutationConflict.Active(active.operation, active.id, active.regionIds, active.correlationId)
                )
            }
            val reservation = ActiveReservation(
                id = UUID.randomUUID(),
                operation = operation,
                regionIds = targets,
                mergeGroupId = mergeGroupId,
                actor = actor,
                correlationId = correlationId,
                createdAt = Instant.now()
            )
            targets.forEach { reservationsByRegion[it] = reservation }
            return ReservationAttempt.Acquired(Lease(this, reservation))
        }
    }

    /** Acquires the same region-scoped lease while exempting only the job being recovered. */
    fun reserveRecovery(
        recoveryJobId: UUID,
        regionIds: Collection<RegionId>,
        actor: ActionActor,
        correlationId: CorrelationId
    ): ReservationAttempt {
        val targets = regionIds.toSet()
        require(targets.isNotEmpty()) { "A recovery reservation requires at least one region" }
        val store = jobStore ?: return ReservationAttempt.Conflict(MutationConflict.RecoveryLookupFailed("persistent job store unavailable"))
        val records = try {
            store.findRecoveryRequiredForRegions(targets)
        } catch (failure: Throwable) {
            return ReservationAttempt.Conflict(MutationConflict.RecoveryLookupFailed(failure.message ?: failure.javaClass.simpleName))
        }
        val own = records.firstOrNull { it.id == recoveryJobId && it.regionIds.any(targets::contains) }
        if (own == null) return ReservationAttempt.Conflict(MutationConflict.RecoveryJobUnavailable(recoveryJobId))
        val parentId = own.details.orEmpty().lineSequence().firstOrNull { it.startsWith("recoveryOfJobId=") }
            ?.substringAfter('=')?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val lineage = buildSet {
            add(recoveryJobId)
            parentId?.let(::add)
            records.filter { record ->
                val parent = record.details.orEmpty().lineSequence().firstOrNull { it.startsWith("recoveryOfJobId=") }
                    ?.substringAfter('=')
                parent == recoveryJobId.toString() || (parentId != null && parent == parentId.toString())
            }.forEach { add(it.id) }
        }
        val other = records.firstOrNull { it.id !in lineage && it.regionIds.any(targets::contains) }
        if (other != null) return ReservationAttempt.Conflict(
            MutationConflict.RecoveryRequired(other.id, other.operation, other.regionIds, other.correlationId, other.error)
        )
        synchronized(monitor) {
            val active = targets.asSequence().mapNotNull(reservationsByRegion::get).firstOrNull()
            if (active != null) return ReservationAttempt.Conflict(
                MutationConflict.Active(active.operation, active.id, active.regionIds, active.correlationId)
            )
            val reservation = ActiveReservation(
                id = UUID.randomUUID(), operation = RegionMutationOperation.RECOVERY,
                regionIds = targets, mergeGroupId = null, actor = actor, correlationId = correlationId,
                createdAt = Instant.now()
            )
            targets.forEach { reservationsByRegion[it] = reservation }
            return ReservationAttempt.Acquired(Lease(this, reservation))
        }
    }

    /** Checks durable recovery state without creating a runtime reservation. */
    fun checkAvailable(regionIds: Collection<RegionId>): MutationConflict? {
        val targets = regionIds.toSet()
        if (targets.isEmpty()) return null
        recoveryConflict(targets)?.let { return it }
        return synchronized(monitor) {
            targets.asSequence().mapNotNull(reservationsByRegion::get).firstOrNull()?.let {
                MutationConflict.Active(it.operation, it.id, it.regionIds, it.correlationId)
            }
        }
    }

    fun activeReservations(): List<ActiveReservation> = synchronized(monitor) {
        reservationsByRegion.values.distinctBy { it.id }
    }

    private fun recoveryConflict(targets: Set<RegionId>): MutationConflict? {
        val store = jobStore ?: return null
        val records = try {
                store.findRecoveryRequiredForRegions(targets)
        } catch (failure: Throwable) {
            return MutationConflict.RecoveryLookupFailed(failure.message ?: failure.javaClass.simpleName)
        }
        val record = records.firstOrNull { it.regionIds.any(targets::contains) } ?: return null
        return MutationConflict.RecoveryRequired(record.id, record.operation, record.regionIds, record.correlationId, record.error)
    }

    private fun release(reservation: ActiveReservation) = synchronized(monitor) {
        reservation.regionIds.forEach { regionId ->
            if (reservationsByRegion[regionId]?.id == reservation.id) reservationsByRegion.remove(regionId)
        }
    }

    class Lease internal constructor(
        private val owner: RegionMutationConflictCoordinator,
        val reservation: ActiveReservation
    ) : AutoCloseable {
        private var released = false

        override fun close() {
            if (!released) {
                released = true
                owner.release(reservation)
            }
        }
    }

}

enum class RegionMutationOperation { MERGE, UNLINK, RESET, RESTORE, COPY, MOVE, SWAP, BIOME, UNCLAIM, RECOVERY }

data class ActiveReservation(
    val id: UUID,
    val operation: RegionMutationOperation,
    val regionIds: Set<RegionId>,
    val mergeGroupId: MergeGroupId?,
    val actor: ActionActor?,
    val correlationId: CorrelationId,
    val createdAt: Instant
)

sealed interface ReservationAttempt {
    data class Acquired(val lease: RegionMutationConflictCoordinator.Lease) : ReservationAttempt
    data class Conflict(val conflict: MutationConflict) : ReservationAttempt
}

sealed interface MutationConflict {
    data class RecoveryJobUnavailable(val jobId: UUID) : MutationConflict

    data class Active(
        val operation: RegionMutationOperation,
        val reservationId: UUID,
        val regionIds: Set<RegionId>,
        val correlationId: CorrelationId
    ) : MutationConflict

    data class RecoveryRequired(
        val jobId: UUID,
        val operation: String,
        val regionIds: List<RegionId>,
        val correlationId: CorrelationId,
        val reason: String?
    ) : MutationConflict

    data class RecoveryLookupFailed(val detail: String) : MutationConflict
}
