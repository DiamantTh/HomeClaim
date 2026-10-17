package systems.diath.homeclaim.platform.paper.plot.mutation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.policy.CorrelationId
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class PlotBackupFormatTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `format validates manifest and streams payload blocks`() {
        val bounds = Bounds(3, 4, 60, 60, -2, -2)
        val record = record(bounds)
        val payload = temp.resolve("payload.part")
        Files.createFile(payload)
        PlotBackupFormat.appendPayload(payload, listOf(
            PlotBackupFormat.BlockSnapshot(3, 60, -2, "minecraft:stone", null),
            PlotBackupFormat.BlockSnapshot(4, 60, -2, "minecraft:chest[facing=north,type=single,waterlogged=false]", listOf("container-data".toByteArray(), null))
        ))
        val temporary = temp.resolve("backup.part")
        val file = temp.resolve("${record.id}.hcbackup")
        val (payloadBytes, checksum) = PlotBackupFormat.finalizeFile(payload, temporary, file, record)
        val indexed = record.copy(status = PlotBackupStatus.READY, payloadBytes = payloadBytes, checksumSha256 = checksum)

        val header = PlotBackupFormat.validate(file, indexed)
        PlotBackupFormat.openPayload(file, header).use { input ->
            val blocks = PlotBackupFormat.readBatch(input, bounds, 2)
            assertEquals("minecraft:stone", blocks[0].blockData)
            assertEquals("minecraft:chest[facing=north,type=single,waterlogged=false]", blocks[1].blockData)
            assertEquals("container-data", blocks[1].inventoryItems?.get(0)?.toString(Charsets.UTF_8))
            assertEquals(null, blocks[1].inventoryItems?.get(1))
            assertEquals(-1, input.read())
        }
    }

    @Test
    fun `corrupt and truncated backup payloads are rejected before restore`() {
        val bounds = Bounds(0, 0, 0, 0, 0, 0)
        val record = record(bounds)
        val payload = temp.resolve("payload.part")
        Files.createFile(payload)
        PlotBackupFormat.appendPayload(payload, listOf(PlotBackupFormat.BlockSnapshot(0, 0, 0, "minecraft:air", null)))
        val temporary = temp.resolve("backup.part")
        val file = temp.resolve("backup.hcbackup")
        val (payloadBytes, checksum) = PlotBackupFormat.finalizeFile(payload, temporary, file, record)
        val indexed = record.copy(status = PlotBackupStatus.READY, payloadBytes = payloadBytes, checksumSha256 = checksum)
        assertTrue(PlotBackupFormat.validate(file, indexed).payloadOffset > 0)

        val bytes = Files.readAllBytes(file)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        Files.write(file, bytes)
        assertThrows(IllegalArgumentException::class.java) { PlotBackupFormat.validate(file, indexed) }
        Files.write(file, bytes.copyOf(bytes.size - 1))
        assertThrows(IllegalArgumentException::class.java) { PlotBackupFormat.validate(file, indexed) }
    }

    private fun record(bounds: Bounds) = PlotBackupRecord(
        id = UUID.randomUUID(),
        correlationId = CorrelationId.newId(),
        worldId = WorldId(UUID.randomUUID()),
        paperWorldId = UUID.randomUUID(),
        areaId = AreaId(UUID.randomUUID()),
        regionId = RegionId(UUID.randomUUID()),
        bounds = bounds,
        createdByKind = "PLAYER",
        createdByRef = UUID.randomUUID().toString(),
        cause = "reset:manual",
        status = PlotBackupStatus.CREATING,
        formatVersion = PlotBackupFormat.CURRENT_VERSION,
        compression = PlotBackupCompression.NONE,
        blockCount = PlotBackupFormat.blockCount(bounds),
        createdAt = Instant.parse("2026-10-17T10:00:00Z")
    )
}
