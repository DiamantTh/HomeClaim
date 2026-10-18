package systems.diath.homeclaim.platform.paper.plot.mutation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.policy.CorrelationId
import java.util.UUID

class PlotMutationRecoveryPlannerTest {
    private fun job(operation: String, details: String, status: PlotMutationJobStatus = PlotMutationJobStatus.RECOVERY_REQUIRED) =
        PlotMutationJobRecord(UUID.randomUUID(), CorrelationId.newId(), operation, null, AreaId(UUID.randomUUID()),
            "display", listOf(RegionId(UUID.randomUUID()), RegionId(UUID.randomUUID())), "test", status, details = details)

    @Test
    fun `copy restores target only while move and swap restore both recorded originals`() {
        val source = UUID.randomUUID()
        val target = UUID.randomUUID()
        val sourceBackup = UUID.randomUUID()
        val targetBackup = UUID.randomUUID()
        val fields = "sourceRegion=$source\ntargetRegion=$target\nsourceBackup=$sourceBackup\ntargetSafetyBackup=$targetBackup"

        val copy = PlotMutationRecoveryPlanner.plan(job("COPY", fields), PlotMutationRecoveryAction.RESTORE_TARGET)
        val copyPlan = assertInstanceOf(PlotMutationRecoveryPlanResult.Ready::class.java, copy)
        assertEquals(listOf(PlotMutationRecoveryTarget(RegionId(target), targetBackup)), copyPlan.targets)
        assertInstanceOf(PlotMutationRecoveryPlanResult.Rejected::class.java,
            PlotMutationRecoveryPlanner.plan(job("MOVE", fields), PlotMutationRecoveryAction.RESTORE_TARGET))

        listOf("MOVE", "SWAP").forEach { operation ->
            val result = assertInstanceOf(PlotMutationRecoveryPlanResult.Ready::class.java,
                PlotMutationRecoveryPlanner.plan(job(operation, fields), PlotMutationRecoveryAction.RESTORE_BOTH))
            assertEquals(listOf(PlotMutationRecoveryTarget(RegionId(source), sourceBackup),
                PlotMutationRecoveryTarget(RegionId(target), targetBackup)), result.targets)
        }
    }

    @Test
    fun `reset and restore require their persisted original backup and acknowledgement never claims repair`() {
        val region = UUID.randomUUID()
        val backup = UUID.randomUUID()
        val reset = PlotMutationRecoveryPlanner.plan(job("RESET_MANUAL", "regionId=$region\nbackupId=$backup"),
            PlotMutationRecoveryAction.RESTORE_TARGET)
        assertEquals(listOf(PlotMutationRecoveryTarget(RegionId(region), backup)),
            assertInstanceOf(PlotMutationRecoveryPlanResult.Ready::class.java, reset).targets)
        val restore = PlotMutationRecoveryPlanner.plan(job("RESTORE", "sourceRegion=$region\nbackupId=$backup"),
            PlotMutationRecoveryAction.RESTORE_TARGET)
        assertEquals(listOf(PlotMutationRecoveryTarget(RegionId(region), backup)),
            assertInstanceOf(PlotMutationRecoveryPlanResult.Ready::class.java, restore).targets)
        val restoreSafety = UUID.randomUUID()
        val safeRestore = PlotMutationRecoveryPlanner.plan(
            job("RESTORE", "sourceRegion=$region\nbackupId=$backup\nrestoreSafetyBackupId=$restoreSafety"),
            PlotMutationRecoveryAction.RESTORE_TARGET
        )
        assertEquals(listOf(PlotMutationRecoveryTarget(RegionId(region), restoreSafety)),
            assertInstanceOf(PlotMutationRecoveryPlanResult.Ready::class.java, safeRestore).targets)
        val acknowledged = PlotMutationRecoveryPlanner.plan(job("MERGE", "phase=RECOVERY_REQUIRED"),
            PlotMutationRecoveryAction.ACKNOWLEDGE)
        assertEquals(emptyList<PlotMutationRecoveryTarget>(),
            assertInstanceOf(PlotMutationRecoveryPlanResult.Ready::class.java, acknowledged).targets)
    }
}
