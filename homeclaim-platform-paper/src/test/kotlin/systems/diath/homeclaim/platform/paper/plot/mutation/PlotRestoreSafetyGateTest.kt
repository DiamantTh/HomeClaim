package systems.diath.homeclaim.platform.paper.plot.mutation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture

class PlotRestoreSafetyGateTest {
    private fun backup(id: UUID = UUID.randomUUID()) = PlotBackupRecord(
        id = id,
        correlationId = systems.diath.homeclaim.core.policy.CorrelationId.newId(),
        worldId = systems.diath.homeclaim.core.model.WorldId(UUID.randomUUID()),
        paperWorldId = UUID.randomUUID(),
        areaId = systems.diath.homeclaim.core.model.AreaId(UUID.randomUUID()),
        regionId = systems.diath.homeclaim.core.model.RegionId(UUID.randomUUID()),
        bounds = systems.diath.homeclaim.core.model.Bounds(0, 0, 0, 0, 0, 0),
        createdByKind = "PLAYER", createdByRef = UUID.randomUUID().toString(), cause = "restore_safety",
        status = PlotBackupStatus.READY, formatVersion = PlotBackupFormat.CURRENT_VERSION,
        compression = PlotBackupCompression.ZSTD, blockCount = 1
    )

    @Test
    fun `safety backup is validated and linked before the caller can mutate`() {
        val captured = backup()
        val ready = captured.copy(payloadBytes = 100, checksumSha256 = "a".repeat(64))
        val capture = CompletableFuture<PlotBackupRecord>()
        val validation = CompletableFuture<PlotBackupRecord>()
        var linked: UUID? = null
        var mutationStarted = false
        val gated = PlotRestoreSafetyGate.prepare(
            capture = { capture },
            validate = { id -> assertEquals(captured.id, id); validation },
            persistReady = { record -> linked = record.id }
        ).thenApply { mutationStarted = true }

        capture.complete(captured)
        assertFalse(gated.isDone)
        assertFalse(mutationStarted)
        assertEquals(null, linked)
        validation.complete(ready)
        gated.join()
        assertEquals(ready.id, linked)
        assertTrue(mutationStarted)
    }

    @Test
    fun `capture or validation failure prevents persisted linkage`() {
        var linked = false
        val captureFailure = PlotRestoreSafetyGate.prepare(
            capture = { CompletableFuture.failedFuture(IllegalStateException("capture failed")) },
            validate = { error("validation must not run") },
            persistReady = { linked = true }
        )
        assertThrows(java.util.concurrent.CompletionException::class.java) { captureFailure.join() }
        assertFalse(linked)

        val validationFailure = PlotRestoreSafetyGate.prepare(
            capture = { CompletableFuture.completedFuture(backup()) },
            validate = { CompletableFuture.failedFuture(IllegalStateException("checksum failed")) },
            persistReady = { linked = true }
        )
        assertThrows(java.util.concurrent.CompletionException::class.java) { validationFailure.join() }
        assertFalse(linked)
    }
}
