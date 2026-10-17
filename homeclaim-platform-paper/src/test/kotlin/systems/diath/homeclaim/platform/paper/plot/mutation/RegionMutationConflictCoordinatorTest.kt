package systems.diath.homeclaim.platform.paper.plot.mutation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.policy.CorrelationId
import java.util.UUID

class RegionMutationConflictCoordinatorTest {
    @Test
    fun `overlapping mutations conflict and release after completion`() {
        val coordinator = RegionMutationConflictCoordinator()
        val first = RegionId(UUID.randomUUID())
        val second = RegionId(UUID.randomUUID())

        val merge = assertInstanceOf(
            ReservationAttempt.Acquired::class.java,
            coordinator.reserve(RegionMutationOperation.MERGE, listOf(first, second))
        )
        val conflict = coordinator.reserve(RegionMutationOperation.BIOME, listOf(second))
        assertInstanceOf(ReservationAttempt.Conflict::class.java, conflict)
        assertEquals(1, coordinator.activeReservations().size)

        merge.lease.close()
        assertInstanceOf(ReservationAttempt.Acquired::class.java, coordinator.reserve(RegionMutationOperation.RESET, listOf(second)))
    }

    @Test
    fun `independent regions can reserve concurrently`() {
        val coordinator = RegionMutationConflictCoordinator()
        assertInstanceOf(ReservationAttempt.Acquired::class.java, coordinator.reserve(RegionMutationOperation.RESET, listOf(RegionId(UUID.randomUUID()))))
        assertInstanceOf(ReservationAttempt.Acquired::class.java, coordinator.reserve(RegionMutationOperation.BIOME, listOf(RegionId(UUID.randomUUID()))))
        assertEquals(2, coordinator.activeReservations().size)
    }

    @Test
    fun `transfer reserves both plots and blocks inverse transfers and resets`() {
        val coordinator = RegionMutationConflictCoordinator()
        val a = RegionId(UUID.randomUUID())
        val b = RegionId(UUID.randomUUID())
        val transfer = assertInstanceOf(ReservationAttempt.Acquired::class.java,
            coordinator.reserve(RegionMutationOperation.SWAP, listOf(a, b)))

        assertInstanceOf(ReservationAttempt.Conflict::class.java,
            coordinator.reserve(RegionMutationOperation.MOVE, listOf(b, a)))
        assertInstanceOf(ReservationAttempt.Conflict::class.java,
            coordinator.reserve(RegionMutationOperation.RESET, listOf(a)))
        transfer.lease.close()
        assertInstanceOf(ReservationAttempt.Acquired::class.java,
            coordinator.reserve(RegionMutationOperation.COPY, listOf(b, a)))
    }

    @Test
    fun `recovery required job blocks a new destructive mutation without creating runtime lock`() {
        val region = RegionId(UUID.randomUUID())
        val store = object : PlotMutationJobStore {
            override fun create(record: PlotMutationJobRecord) = Unit
            override fun markRunning(id: UUID) = Unit
            override fun updateProgress(id: UUID, completedUnits: Int, totalUnits: Int) = Unit
            override fun finish(id: UUID, status: PlotMutationJobStatus, completedUnits: Int, totalUnits: Int, error: String?) = Unit
            override fun markInterruptedAsRecoveryRequired() = 0
            override fun findById(id: UUID) = null
            override fun findRecoveryRequired(limit: Int) = listOf(
                PlotMutationJobRecord(
                    id = UUID.randomUUID(), correlationId = CorrelationId.newId(), operation = "RESET",
                    worldId = null, areaId = AreaId(UUID.randomUUID()), worldName = "plots",
                    regionIds = listOf(region), backend = "paper-native",
                    status = PlotMutationJobStatus.RECOVERY_REQUIRED, error = "partial world mutation"
                )
            )
        }
        val coordinator = RegionMutationConflictCoordinator(store)

        val attempt = coordinator.reserve(RegionMutationOperation.UNCLAIM, listOf(region))
        val conflict = assertInstanceOf(ReservationAttempt.Conflict::class.java, attempt).conflict
        assertInstanceOf(MutationConflict.RecoveryRequired::class.java, conflict)
        assertTrue(coordinator.activeReservations().isEmpty())
    }
}
