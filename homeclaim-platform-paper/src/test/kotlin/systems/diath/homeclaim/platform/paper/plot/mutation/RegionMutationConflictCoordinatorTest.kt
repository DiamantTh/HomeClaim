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

    @Test
    fun `explicit recovery may reserve its own blocked regions but not overlap another recovery`() {
        val region = RegionId(UUID.randomUUID())
        val jobId = UUID.randomUUID()
        val record = PlotMutationJobRecord(
            id = jobId, correlationId = CorrelationId.newId(), operation = "SWAP", worldId = null,
            areaId = AreaId(UUID.randomUUID()), worldName = "display", regionIds = listOf(region),
            backend = "paper", status = PlotMutationJobStatus.RECOVERY_REQUIRED
        )
        val store = object : PlotMutationJobStore {
            override fun create(record: PlotMutationJobRecord) = Unit
            override fun markRunning(id: UUID) = Unit
            override fun updateProgress(id: UUID, completedUnits: Int, totalUnits: Int) = Unit
            override fun finish(id: UUID, status: PlotMutationJobStatus, completedUnits: Int, totalUnits: Int, error: String?) = Unit
            override fun markInterruptedAsRecoveryRequired() = 0
            override fun findById(id: UUID) = record.takeIf { it.id == id }
            override fun findRecoveryRequired(limit: Int) = listOf(record)
        }
        val coordinator = RegionMutationConflictCoordinator(store)
        val actor = systems.diath.homeclaim.core.policy.ActionActor.System()
        val recovery = assertInstanceOf(ReservationAttempt.Acquired::class.java,
            coordinator.reserveRecovery(jobId, listOf(region), actor, record.correlationId))
        assertInstanceOf(ReservationAttempt.Conflict::class.java,
            coordinator.reserve(RegionMutationOperation.RESET, listOf(region)))
        recovery.lease.close()
        assertInstanceOf(ReservationAttempt.Acquired::class.java,
            coordinator.reserveRecovery(jobId, listOf(region), actor, record.correlationId))
    }

    @Test
    fun `recovery jobs created by a recovery action do not deadlock their parent lineage`() {
        val region = RegionId(UUID.randomUUID())
        val parentId = UUID.randomUUID()
        val childId = UUID.randomUUID()
        val parent = PlotMutationJobRecord(parentId, CorrelationId.newId(), "COPY", null,
            AreaId(UUID.randomUUID()), "display", listOf(region), "paper", PlotMutationJobStatus.RECOVERY_REQUIRED)
        val child = PlotMutationJobRecord(childId, CorrelationId.newId(), "RESTORE", null,
            parent.areaId, "display", listOf(region), "paper", PlotMutationJobStatus.RECOVERY_REQUIRED,
            details = "recoveryOfJobId=$parentId")
        val store = object : PlotMutationJobStore {
            override fun create(record: PlotMutationJobRecord) = Unit
            override fun markRunning(id: UUID) = Unit
            override fun updateProgress(id: UUID, completedUnits: Int, totalUnits: Int) = Unit
            override fun finish(id: UUID, status: PlotMutationJobStatus, completedUnits: Int, totalUnits: Int, error: String?) = Unit
            override fun markInterruptedAsRecoveryRequired() = 0
            override fun findById(id: UUID) = listOf(parent, child).firstOrNull { it.id == id }
            override fun findRecoveryRequired(limit: Int) = listOf(parent, child)
            override fun findRecoveryRequiredForRegions(regionIds: Collection<RegionId>) = listOf(parent, child)
        }
        val coordinator = RegionMutationConflictCoordinator(store)
        val recovery = assertInstanceOf(ReservationAttempt.Acquired::class.java,
            coordinator.reserveRecovery(childId, listOf(region), systems.diath.homeclaim.core.policy.ActionActor.System(), child.correlationId))
        recovery.lease.close()
    }
}
