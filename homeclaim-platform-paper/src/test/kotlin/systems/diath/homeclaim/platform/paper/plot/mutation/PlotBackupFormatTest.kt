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
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.nio.ByteBuffer

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
    fun `zstd v2 round trips and records compressed and uncompressed sizes`() {
        val bounds = Bounds(0, 1, 0, 0, 0, 0)
        val record = record(bounds).copy(compression = PlotBackupCompression.ZSTD)
        val payload = temp.resolve("zstd-payload.part")
        Files.createFile(payload)
        PlotBackupFormat.appendPayload(payload, listOf(
            PlotBackupFormat.BlockSnapshot(0, 0, 0, "minecraft:stone", null),
            PlotBackupFormat.BlockSnapshot(1, 0, 0, "minecraft:stone", null)
        ))
        val originalBytes = Files.size(payload)
        val file = temp.resolve("zstd.hcbackup")
        val encoded = PlotBackupFormat.finalizeFile(payload, temp.resolve("zstd.part"), file, record)
        val indexed = record.copy(status = PlotBackupStatus.READY, payloadBytes = encoded.payloadBytes, checksumSha256 = encoded.checksum)
        val header = PlotBackupFormat.validate(file, indexed)
        assertEquals(PlotBackupFormat.CURRENT_VERSION, header.record.formatVersion)
        assertEquals(originalBytes, header.uncompressedPayloadBytes)
        assertEquals(originalBytes, encoded.uncompressedPayloadBytes)
        assertTrue(encoded.payloadBytes < originalBytes)
        PlotBackupFormat.openPayload(file, header).use { input ->
            assertEquals(listOf("minecraft:stone", "minecraft:stone"), PlotBackupFormat.readBatch(input, bounds, 2).map { it.blockData })
            assertEquals(-1, input.read())
        }
    }

    @Test
    fun `zstd corruption checksum and oversized declared output are rejected`() {
        val bounds = Bounds(0, 0, 0, 0, 0, 0)
        val record = record(bounds).copy(compression = PlotBackupCompression.ZSTD)
        val payload = temp.resolve("validation-payload.part")
        Files.createFile(payload)
        PlotBackupFormat.appendPayload(payload, listOf(PlotBackupFormat.BlockSnapshot(0, 0, 0, "minecraft:stone", null)))
        val file = temp.resolve("validation.hcbackup")
        val finalized = PlotBackupFormat.finalizeFile(payload, temp.resolve("validation.part"), file, record)
        val indexed = record.copy(status = PlotBackupStatus.READY, payloadBytes = finalized.payloadBytes, checksumSha256 = finalized.checksum)

        val bytes = Files.readAllBytes(file)
        val offsets = manifestOffsets(bytes)
        val payloadOffset = 8 + offsets.manifestLength
        bytes[payloadOffset + 3] = (bytes[payloadOffset + 3].toInt() xor 0x40).toByte()
        val storedPayload = bytes.copyOfRange(payloadOffset, bytes.size)
        val damagedDigest = MessageDigest.getInstance("SHA-256").digest(storedPayload)
        damagedDigest.copyInto(bytes, 8 + offsets.manifestLength - 32)
        Files.write(file, bytes)
        val damagedIndex = indexed.copy(checksumSha256 = damagedDigest.joinToString("") { "%02x".format(it) })
        assertThrows(Exception::class.java) { PlotBackupFormat.validate(file, damagedIndex) }

        val truncated = Files.readAllBytes(file)
        val truncatedPayload = truncated.copyOfRange(payloadOffset, truncated.size - 2)
        ByteBuffer.wrap(truncated, 8 + offsets.payloadSizeOffset, Long.SIZE_BYTES).putLong(truncatedPayload.size.toLong())
        val truncatedDigest = MessageDigest.getInstance("SHA-256").digest(truncatedPayload)
        truncatedDigest.copyInto(truncated, 8 + offsets.checksumOffset)
        Files.write(file, truncated.copyOf(payloadOffset + truncatedPayload.size))
        val truncatedIndex = indexed.copy(payloadBytes = truncatedPayload.size.toLong(),
            checksumSha256 = truncatedDigest.joinToString("") { "%02x".format(it) })
        assertThrows(Exception::class.java) { PlotBackupFormat.validate(file, truncatedIndex) }

        finalized.checksum.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            .copyInto(bytes, 8 + offsets.manifestLength - 32)
        ByteBuffer.wrap(bytes, 8 + offsets.uncompressedSizeOffset, Long.SIZE_BYTES)
            .putLong(PlotBackupFormat.MAX_BACKUP_BYTES + 1)
        Files.write(file, bytes)
        assertThrows(IllegalArgumentException::class.java) { PlotBackupFormat.validate(file, indexed) }
    }

    @Test
    fun `compression parsing accepts only configured codecs`() {
        assertEquals(PlotBackupCompression.ZSTD, PlotBackupCompression.parse(" ZsTd "))
        assertEquals(PlotBackupCompression.NONE, PlotBackupCompression.parse("none"))
        assertThrows(IllegalArgumentException::class.java) { PlotBackupCompression.parse("gzip") }
    }

    @Test
    fun `legacy hcbk v1 none fixture remains readable`() {
        val bounds = Bounds(0, 0, 0, 0, 0, 0)
        val oldRecord = record(bounds).copy(formatVersion = 1, compression = PlotBackupCompression.NONE)
        val payload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(0); output.writeInt(0); output.writeInt(0)
                val block = "minecraft:air".toByteArray()
                output.writeInt(block.size); output.write(block); output.writeBoolean(false)
            }
        }.toByteArray()
        val checksum = MessageDigest.getInstance("SHA-256").digest(payload)
        val manifestBytes = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(1)
                output.writeUuid(oldRecord.id); output.writeUuid(oldRecord.correlationId.value)
                output.writeUuid(oldRecord.worldId.value); output.writeUuid(oldRecord.paperWorldId)
                output.writeUuid(oldRecord.areaId.value); output.writeUuid(oldRecord.regionId.value)
                output.writeInt(bounds.minX); output.writeInt(bounds.maxX); output.writeInt(bounds.minY); output.writeInt(bounds.maxY)
                output.writeInt(bounds.minZ); output.writeInt(bounds.maxZ)
                output.writeBounded("PLAYER"); output.writeNullable(oldRecord.createdByRef)
                output.writeBounded(oldRecord.cause); output.writeLong(oldRecord.createdAt.toEpochMilli())
                output.writeByte(PlotBackupCompression.NONE.ordinal); output.writeLong(1); output.writeLong(payload.size.toLong())
                output.write(checksum)
            }
        }.toByteArray()
        val file = temp.resolve("legacy-v1.hcbackup")
        DataOutputStream(Files.newOutputStream(file)).use { output ->
            output.writeInt(0x4843424B); output.writeInt(manifestBytes.size); output.write(manifestBytes); output.write(payload)
        }
        val indexed = oldRecord.copy(status = PlotBackupStatus.READY, payloadBytes = payload.size.toLong(),
            checksumSha256 = checksum.joinToString("") { "%02x".format(it) })
        val header = PlotBackupFormat.validate(file, indexed)
        assertEquals(1, header.record.formatVersion)
        assertEquals(PlotBackupCompression.NONE, header.record.compression)
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

    private fun DataOutputStream.writeUuid(value: UUID) { writeLong(value.mostSignificantBits); writeLong(value.leastSignificantBits) }
    private fun DataOutputStream.writeBounded(value: String) {
        val bytes = value.toByteArray(); writeInt(bytes.size); write(bytes)
    }
    private fun DataOutputStream.writeNullable(value: String?) {
        if (value == null) writeInt(-1) else writeBounded(value)
    }

    private data class ManifestOffsets(val manifestLength: Int, val payloadSizeOffset: Int, val uncompressedSizeOffset: Int, val checksumOffset: Int)

    private fun manifestOffsets(bytes: ByteArray): ManifestOffsets {
        val manifestLength = ByteBuffer.wrap(bytes, 4, 4).int
        val input = java.io.DataInputStream(bytes.copyOfRange(8, 8 + manifestLength).inputStream())
        input.readInt()
        repeat(6) { input.readLong(); input.readLong() }
        repeat(6) { input.readInt() }
        fun skipString() { val length = input.readInt(); if (length >= 0) input.skipNBytes(length.toLong()) }
        skipString(); skipString(); skipString()
        input.readLong(); input.readUnsignedByte(); input.readLong()
        val payloadSizeOffset = manifestLength - input.available()
        input.readLong()
        val uncompressedSizeOffset = manifestLength - input.available()
        input.readLong()
        val checksumOffset = manifestLength - input.available()
        return ManifestOffsets(manifestLength, payloadSizeOffset, uncompressedSizeOffset, checksumOffset)
    }
}
