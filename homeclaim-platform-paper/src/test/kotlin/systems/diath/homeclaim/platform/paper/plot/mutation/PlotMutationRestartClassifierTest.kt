package systems.diath.homeclaim.platform.paper.plot.mutation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.policy.CorrelationId
import java.util.UUID

class PlotMutationRestartClassifierTest {
    private fun job(operation: String, status: PlotMutationJobStatus, details: String? = null) = PlotMutationJobRecord(
        id = UUID.randomUUID(), correlationId = CorrelationId.newId(), operation = operation,
        worldId = null, areaId = AreaId(UUID.randomUUID()), worldName = "display",
        regionIds = listOf(RegionId(UUID.randomUUID())), backend = "test", status = status, details = details
    )

    @Test
    fun `queued work is failed cleanly because mutation never entered running`() {
        val result = PlotMutationRestartClassifier.classify(job("COPY", PlotMutationJobStatus.QUEUED,
            "format=homeclaim-transfer-v1\nphase=BACKING_UP"))
        assertEquals(PlotMutationJobStatus.FAILED, result.status)
        assertEquals("queued_before_execution", result.reason)
    }

    @Test
    fun `durable pre-write transfer phase is failed while write phases require recovery`() {
        val backingUp = PlotMutationRestartClassifier.classify(job("COPY", PlotMutationJobStatus.RUNNING,
            "format=homeclaim-transfer-v1\nphase=BACKING_UP"))
        val applying = PlotMutationRestartClassifier.classify(job("COPY", PlotMutationJobStatus.RUNNING,
            "format=homeclaim-transfer-v1\nphase=APPLYING_TARGET"))
        assertEquals(PlotMutationJobStatus.FAILED, backingUp.status)
        assertEquals(PlotMutationJobStatus.RECOVERY_REQUIRED, applying.status)
    }

    @Test
    fun `reset and restore are conservative when their persisted phase is missing or mutating`() {
        val legacyReset = PlotMutationRestartClassifier.classify(job("RESET_MANUAL", PlotMutationJobStatus.RUNNING))
        val resetting = PlotMutationRestartClassifier.classify(job("RESET_MANUAL", PlotMutationJobStatus.RUNNING,
            "format=homeclaim-reset-v1\nphase=RESETTING"))
        val restoring = PlotMutationRestartClassifier.classify(job("RESTORE", PlotMutationJobStatus.RUNNING,
            "format=homeclaim-restore-v1\nphase=RESTORING"))
        assertEquals(PlotMutationJobStatus.RECOVERY_REQUIRED, legacyReset.status)
        assertEquals(PlotMutationJobStatus.RECOVERY_REQUIRED, resetting.status)
        assertEquals(PlotMutationJobStatus.RECOVERY_REQUIRED, restoring.status)
    }

    @Test
    fun `restart during explicit recovery restores the operator decision to recoverable state`() {
        val result = PlotMutationRestartClassifier.classify(job("SWAP", PlotMutationJobStatus.RECOVERY_IN_PROGRESS,
            "format=homeclaim-transfer-v1\nphase=APPLYING_SECONDARY\nrecoveryAction=RESTORE_BOTH"))
        assertEquals(PlotMutationJobStatus.RECOVERY_REQUIRED, result.status)
        assertEquals("operator_recovery_interrupted", result.reason)
    }
}
