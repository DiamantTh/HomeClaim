package systems.diath.homeclaim.platform.paper.plot.mutation

import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.policy.CorrelationId
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Stable, bounded binary container. Payload compression is deliberately NONE in format v1. */
internal object PlotBackupFormat {
    const val CURRENT_VERSION = 1
    const val MAX_BLOCKS = 10_000_000L
    const val MAX_BACKUP_BYTES = 1_073_741_824L
    const val MAX_BLOCK_DATA_BYTES = 8_192
    const val MAX_ITEM_BYTES = 65_536
    const val MAX_INVENTORY_SLOTS = 256
    private const val MAX_INVENTORY_BYTES = 1_048_576
    private const val MAX_BATCH_BYTES = 16 * 1_048_576
    private const val MAGIC = 0x4843424B // HCBK
    private const val MAX_MANIFEST_BYTES = 16_384

    data class BlockSnapshot(
        val x: Int,
        val y: Int,
        val z: Int,
        val blockData: String,
        val inventoryItems: List<ByteArray?>?
    )

    data class Header(val record: PlotBackupRecord, val payloadOffset: Long)

    fun blockCount(bounds: Bounds): Long {
        val x = bounds.maxX.toLong() - bounds.minX + 1
        val y = bounds.maxY.toLong() - bounds.minY + 1
        val z = bounds.maxZ.toLong() - bounds.minZ + 1
        require(x > 0 && y > 0 && z > 0) { "Backup bounds are invalid" }
        val count = Math.multiplyExact(Math.multiplyExact(x, y), z)
        require(count in 1..MAX_BLOCKS) { "Backup volume exceeds the supported $MAX_BLOCKS blocks" }
        return count
    }

    fun appendPayload(path: Path, snapshots: List<BlockSnapshot>) {
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND
        ))).use { output -> snapshots.forEach { writeBlock(output, it) } }
    }

    fun finalizeFile(payload: Path, temporaryFile: Path, finalFile: Path, record: PlotBackupRecord): Pair<Long, String> {
        val payloadSize = Files.size(payload)
        require(payloadSize <= MAX_BACKUP_BYTES) { "Backup payload exceeds the supported size" }
        val digest = sha256(payload)
        val manifest = encodeManifest(record, payloadSize, digest)
        Files.newOutputStream(temporaryFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { raw ->
            DataOutputStream(BufferedOutputStream(raw)).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(manifest.size)
                output.write(manifest)
                Files.newInputStream(payload).use { input -> input.copyBoundedTo(output, MAX_BACKUP_BYTES) }
            }
        }
        try {
            Files.move(temporaryFile, finalFile, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporaryFile, finalFile)
        }
        return payloadSize to digest.joinToString("") { "%02x".format(it) }
    }

    fun validate(file: Path, indexed: PlotBackupRecord): Header {
        require(indexed.status == PlotBackupStatus.READY) { "Backup is not ready" }
        require(indexed.formatVersion == CURRENT_VERSION) { "Unsupported backup format ${indexed.formatVersion}" }
        require(indexed.compression == PlotBackupCompression.NONE) { "Unsupported backup compression ${indexed.compression}" }
        val fileSize = Files.size(file)
        require(fileSize in 9..(MAX_BACKUP_BYTES + MAX_MANIFEST_BYTES + 8)) { "Backup file size is invalid" }
        val (record, payloadSize, expectedDigest, payloadOffset) = readManifest(file)
        require(record.id == indexed.id && record.regionId == indexed.regionId) { "Backup identity does not match its index" }
        require(record.correlationId == indexed.correlationId) { "Backup correlation does not match its index" }
        require(record.worldId == indexed.worldId && record.paperWorldId == indexed.paperWorldId) { "Backup world identity does not match its index" }
        require(record.areaId == indexed.areaId && record.bounds == indexed.bounds) { "Backup scope does not match its index" }
        require(record.createdAt == indexed.createdAt && record.createdByKind == indexed.createdByKind && record.createdByRef == indexed.createdByRef) {
            "Backup provenance does not match its index"
        }
        require(record.cause == indexed.cause && record.blockCount == indexed.blockCount) { "Backup manifest does not match its index" }
        require(payloadSize == indexed.payloadBytes && expectedDigest.joinToString("") { "%02x".format(it) } == indexed.checksumSha256) { "Backup integrity metadata does not match its index" }
        require(payloadOffset + payloadSize == fileSize) { "Backup payload length is invalid" }
        require(blockCount(record.bounds) == record.blockCount) { "Backup block count does not match its bounds" }
        val actualDigest = sha256(file, payloadOffset, payloadSize)
        require(MessageDigest.isEqual(expectedDigest, actualDigest)) { "Backup SHA-256 checksum does not match" }

        // Validate record framing and bounds before any world mutation. The payload is then
        // streamed again in bounded batches for restore; it is never loaded into one ByteArray.
        openPayload(file, payloadOffset).use { input ->
            repeat(record.blockCount.toInt()) { readBlock(input, record.bounds) }
            require(input.read() == -1) { "Backup has trailing payload data" }
        }
        return Header(record.copy(payloadBytes = payloadSize, checksumSha256 = expectedDigest.joinToString("") { "%02x".format(it) }), payloadOffset)
    }

    fun openPayload(file: Path, header: Header): DataInputStream = openPayload(file, header.payloadOffset)

    fun readBatch(input: DataInputStream, bounds: Bounds, count: Int): List<BlockSnapshot> = buildList(count) {
        var bytes = 0L
        repeat(count) {
            val snapshot = readBlock(input, bounds)
            bytes += snapshot.blockData.length * 2L
            bytes += snapshot.inventoryItems.orEmpty().sumOf { it?.size?.toLong() ?: 0L }
            require(bytes <= MAX_BATCH_BYTES) { "Restore batch exceeds the supported in-memory buffer" }
            add(snapshot)
        }
    }

    private fun openPayload(file: Path, payloadOffset: Long): DataInputStream {
        val input = BufferedInputStream(Files.newInputStream(file))
        try {
            var remaining = payloadOffset
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped <= 0) throw EOFException("Truncated backup header")
                remaining -= skipped
            }
            return DataInputStream(input)
        } catch (failure: Throwable) {
            input.close()
            throw failure
        }
    }

    private data class ManifestRead(
        val record: PlotBackupRecord,
        val payloadSize: Long,
        val digest: ByteArray,
        val payloadOffset: Long
    )

    private fun readManifest(file: Path): ManifestRead {
        DataInputStream(BufferedInputStream(Files.newInputStream(file))).use { input ->
            require(input.readInt() == MAGIC) { "Backup magic header is invalid" }
            val manifestLength = input.readInt()
            require(manifestLength in 1..MAX_MANIFEST_BYTES) { "Backup manifest length is invalid" }
            val bytes = ByteArray(manifestLength)
            input.readFully(bytes)
            val recordInput = DataInputStream(bytes.inputStream())
            val version = recordInput.readInt()
            require(version == CURRENT_VERSION) { "Unsupported backup format $version" }
            val id = recordInput.readUuid()
            val correlation = CorrelationId(recordInput.readUuid())
            val world = WorldId(recordInput.readUuid())
            val paperWorld = recordInput.readUuid()
            val area = AreaId(recordInput.readUuid())
            val region = RegionId(recordInput.readUuid())
            val bounds = readBounds(recordInput)
            val actorKind = recordInput.readBoundedString(16)
            val actorRef = recordInput.readNullableString(128)
            val cause = recordInput.readBoundedString(32)
            val createdAt = Instant.ofEpochMilli(recordInput.readLong())
            val compression = PlotBackupCompression.entries.getOrNull(recordInput.readUnsignedByte())
                ?: throw IllegalArgumentException("Unknown backup compression")
            val blockCount = recordInput.readLong()
            val payloadSize = recordInput.readLong()
            require(payloadSize in 0..MAX_BACKUP_BYTES) { "Declared backup payload size is invalid" }
            val checksum = ByteArray(32)
            recordInput.readFully(checksum)
            require(recordInput.read() == -1) { "Backup manifest has trailing fields" }
            val record = PlotBackupRecord(
                id, correlation, world, paperWorld, area, region, bounds, actorKind, actorRef, cause,
                PlotBackupStatus.READY, version, compression, blockCount, payloadSize,
                checksum.joinToString("") { "%02x".format(it) }, createdAt = createdAt
            )
            return ManifestRead(record, payloadSize, checksum, 8L + manifestLength)
        }
    }

    private fun encodeManifest(record: PlotBackupRecord, payloadSize: Long, digest: ByteArray): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(CURRENT_VERSION)
            output.writeUuid(record.id)
            output.writeUuid(record.correlationId.value)
            output.writeUuid(record.worldId.value)
            output.writeUuid(record.paperWorldId)
            output.writeUuid(record.areaId.value)
            output.writeUuid(record.regionId.value)
            writeBounds(output, record.bounds)
            output.writeBoundedString(record.createdByKind, 16)
            output.writeNullableString(record.createdByRef, 128)
            output.writeBoundedString(record.cause, 32)
            output.writeLong(record.createdAt.toEpochMilli())
            output.writeByte(record.compression.ordinal)
            output.writeLong(record.blockCount)
            output.writeLong(payloadSize)
            output.write(digest)
        }
        return bytes.toByteArray().also { require(it.size <= MAX_MANIFEST_BYTES) }
    }

    private fun writeBlock(output: DataOutputStream, block: BlockSnapshot) {
        output.writeInt(block.x)
        output.writeInt(block.y)
        output.writeInt(block.z)
        output.writeBoundedString(block.blockData, MAX_BLOCK_DATA_BYTES)
        val items = block.inventoryItems
        output.writeBoolean(items != null)
        if (items != null) {
            require(items.size <= MAX_INVENTORY_SLOTS) { "Inventory exceeds the supported slot count" }
            output.writeShort(items.size)
            var inventoryBytes = 0L
            items.forEach { item ->
                if (item == null) output.writeInt(-1) else {
                    require(item.size <= MAX_ITEM_BYTES) { "Serialized item exceeds the supported size" }
                    inventoryBytes += item.size
                    require(inventoryBytes <= MAX_INVENTORY_BYTES) { "Serialized inventory exceeds the supported size" }
                    output.writeInt(item.size)
                    output.write(item)
                }
            }
        }
    }

    private fun readBlock(input: DataInputStream, bounds: Bounds): BlockSnapshot {
        val x = input.readInt()
        val y = input.readInt()
        val z = input.readInt()
        require(bounds.contains(x, y, z)) { "Backup block lies outside its declared bounds" }
        val blockData = input.readBoundedString(MAX_BLOCK_DATA_BYTES)
        require(blockData.isNotBlank()) { "Backup block data is empty" }
        val hasInventory = input.readBoolean()
        val items = if (!hasInventory) null else {
            val size = input.readUnsignedShort()
            require(size <= MAX_INVENTORY_SLOTS) { "Inventory slot count is invalid" }
            var inventoryBytes = 0L
            List(size) {
                val itemSize = input.readInt()
                when {
                    itemSize == -1 -> null
                    itemSize in 1..MAX_ITEM_BYTES -> {
                        inventoryBytes += itemSize
                        require(inventoryBytes <= MAX_INVENTORY_BYTES) { "Serialized inventory exceeds the supported size" }
                        ByteArray(itemSize).also(input::readFully)
                    }
                    else -> throw IllegalArgumentException("Serialized item length is invalid")
                }
            }
        }
        return BlockSnapshot(x, y, z, blockData, items)
    }

    private fun writeBounds(output: DataOutputStream, bounds: Bounds) {
        output.writeInt(bounds.minX); output.writeInt(bounds.maxX)
        output.writeInt(bounds.minY); output.writeInt(bounds.maxY)
        output.writeInt(bounds.minZ); output.writeInt(bounds.maxZ)
    }

    private fun readBounds(input: DataInputStream) = Bounds(
        input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readInt()
    )

    private fun sha256(path: Path): ByteArray = Files.newInputStream(path).use { input -> sha256ToEnd(input) }

    private fun sha256(path: Path, offset: Long, length: Long): ByteArray =
        openPayload(path, offset).use { input -> sha256(input, length) }

    private fun sha256(input: InputStream, length: Long): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = length
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) break
            digest.update(buffer, 0, count)
            remaining -= count
        }
        require(remaining == 0L) { "Backup payload is truncated" }
        return digest.digest()
    }

    private fun sha256ToEnd(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest()
    }

    private fun InputStream.copyBoundedTo(output: OutputStream, maxBytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            copied = Math.addExact(copied, count.toLong())
            require(copied <= maxBytes) { "Backup payload exceeds the supported size" }
            output.write(buffer, 0, count)
        }
    }

    private fun DataOutputStream.writeUuid(value: UUID) { writeLong(value.mostSignificantBits); writeLong(value.leastSignificantBits) }
    private fun DataInputStream.readUuid() = UUID(readLong(), readLong())

    private fun DataOutputStream.writeBoundedString(value: String, maxBytes: Int) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxBytes) { "Backup string exceeds the supported size" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedString(maxBytes: Int): String {
        val size = readInt()
        require(size in 0..maxBytes) { "Backup string length is invalid" }
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }

    private fun DataOutputStream.writeNullableString(value: String?, maxBytes: Int) {
        if (value == null) writeInt(-1) else writeBoundedString(value, maxBytes)
    }

    private fun DataInputStream.readNullableString(maxBytes: Int): String? {
        val size = readInt()
        if (size == -1) return null
        require(size in 0..maxBytes) { "Backup string length is invalid" }
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }

    private fun String.hexToBytes(): ByteArray {
        require(length == 64 && all { it in '0'..'9' || it in 'a'..'f' })
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
