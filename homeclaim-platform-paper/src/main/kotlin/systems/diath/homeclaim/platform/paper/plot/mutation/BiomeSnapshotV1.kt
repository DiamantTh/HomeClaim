package systems.diath.homeclaim.platform.paper.plot.mutation

import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.WorldId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Coordinates are biome quart coordinates (one entry represents a complete 4x4x4 block cell). */
data class BiomeCell(val quartX: Int, val quartY: Int, val quartZ: Int) : Comparable<BiomeCell> {
    override fun compareTo(other: BiomeCell): Int = compareValuesBy(this, other, BiomeCell::quartX, BiomeCell::quartY, BiomeCell::quartZ)

    fun blockX(): Int = Math.multiplyExact(quartX, 4)
    fun blockY(): Int = Math.multiplyExact(quartY, 4)
    fun blockZ(): Int = Math.multiplyExact(quartZ, 4)

    companion object {
        /** Paper's block-coordinate biome API maps coordinates to quart cells using floor semantics. */
        fun fromBlock(x: Int, y: Int, z: Int) = BiomeCell(Math.floorDiv(x, 4), Math.floorDiv(y, 4), Math.floorDiv(z, 4))
    }
}

/**
 * Safe subset of complete cells: a cell is included only if all 64 blocks lie
 * within one selected Region. Edge cells split by a plot boundary are left
 * untouched, so the operation never writes into an unreserved neighboring plot.
 */
object BiomeCellScope {
    fun cells(regions: Collection<Region>, maxCells: Int = BiomeSnapshotV1.MAX_CELLS): List<BiomeCell> {
        require(maxCells > 0)
        val result = sortedSetOf<BiomeCell>()
        regions.forEach { region ->
            val bounds = region.bounds
            val minQx = ceilDiv(bounds.minX, 4)
            val maxQx = Math.floorDiv(bounds.maxX.toLong() - 3L, 4L).toInt()
            val minQy = ceilDiv(bounds.minY, 4)
            val maxQy = Math.floorDiv(bounds.maxY.toLong() - 3L, 4L).toInt()
            val minQz = ceilDiv(bounds.minZ, 4)
            val maxQz = Math.floorDiv(bounds.maxZ.toLong() - 3L, 4L).toInt()
            if (minQx > maxQx || minQy > maxQy || minQz > maxQz) return@forEach
            val volume = Math.multiplyExact(Math.multiplyExact(maxQx.toLong() - minQx + 1, maxQy.toLong() - minQy + 1), maxQz.toLong() - minQz + 1)
            require(volume <= maxCells.toLong() - result.size) { "Biome scope exceeds the $maxCells-cell safety limit" }
            for (qx in minQx..maxQx) for (qy in minQy..maxQy) for (qz in minQz..maxQz) {
                result += BiomeCell(qx, qy, qz)
                require(result.size <= maxCells) { "Biome scope exceeds the $maxCells-cell safety limit" }
            }
        }
        require(result.isNotEmpty()) { "No complete 4x4x4 biome cells are fully contained in the selected plot bounds" }
        return result.toList()
    }

    private fun ceilDiv(value: Int, divisor: Int): Int = Math.floorDiv(value, divisor) +
        if (Math.floorMod(value, divisor) == 0) 0 else 1
}

data class BiomeSnapshotCell(val cell: BiomeCell, val biomeKey: String)

data class BiomeSnapshotV1(
    val id: UUID,
    val homeClaimWorldId: WorldId,
    val paperWorldId: UUID,
    val areaId: AreaId,
    val regionIds: List<RegionId>,
    val createdAt: Instant,
    val cells: List<BiomeSnapshotCell>
) {
    init {
        require(regionIds.isNotEmpty() && regionIds.size <= MAX_REGIONS)
        require(cells.isNotEmpty() && cells.size <= MAX_CELLS)
        require(cells.map { it.cell }.distinct().size == cells.size) { "Biome snapshot contains duplicate cells" }
        require(cells.all { it.biomeKey.matches(BIOME_KEY) }) { "Biome snapshot contains an invalid registry key" }
    }

    fun encode(): ByteArray {
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeShort(VERSION)
                out.writeUTF(CAPTURE_MODE)
                out.writeUTF(CODEC)
                out.writeLong(createdAt.toEpochMilli())
                out.writeLong(homeClaimWorldId.value.mostSignificantBits)
                out.writeLong(homeClaimWorldId.value.leastSignificantBits)
                out.writeLong(paperWorldId.mostSignificantBits)
                out.writeLong(paperWorldId.leastSignificantBits)
                out.writeLong(areaId.value.mostSignificantBits)
                out.writeLong(areaId.value.leastSignificantBits)
                out.writeLong(id.mostSignificantBits)
                out.writeLong(id.leastSignificantBits)
                out.writeInt(regionIds.size)
                regionIds.sortedBy { it.value.toString() }.forEach { writeUuid(out, it.value) }
                out.writeInt(cells.size)
                cells.sortedBy { it.cell }.forEach { entry ->
                    out.writeInt(entry.cell.quartX); out.writeInt(entry.cell.quartY); out.writeInt(entry.cell.quartZ)
                    val key = entry.biomeKey.toByteArray(StandardCharsets.UTF_8)
                    require(key.size <= MAX_KEY_BYTES)
                    out.writeShort(key.size); out.write(key)
                }
            }
            bytes.toByteArray()
        }
        require(payload.size <= MAX_FILE_BYTES - SHA256_BYTES) { "Biome snapshot exceeds the file size limit" }
        return payload + sha256(payload)
    }

    fun sha256(): String = sha256Hex(encode())

    companion object {
        const val MAX_CELLS = 500_000
        const val MAX_REGIONS = 256
        const val MAX_FILE_BYTES = 64 * 1024 * 1024
        const val MAX_KEY_BYTES = 256
        const val CAPTURE_MODE = "CELL_ALIGNED_TICKWISE"
        const val CODEC = "NONE"
        private const val MAGIC = 0x48434253 // HCBS
        private const val VERSION = 1
        private const val SHA256_BYTES = 32
        private val BIOME_KEY = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")

        fun decode(bytes: ByteArray): BiomeSnapshotV1 {
            require(bytes.size in (SHA256_BYTES + 16)..MAX_FILE_BYTES) { "Biome snapshot size is invalid" }
            val payload = bytes.copyOfRange(0, bytes.size - SHA256_BYTES)
            val checksum = bytes.copyOfRange(bytes.size - SHA256_BYTES, bytes.size)
            require(MessageDigest.isEqual(checksum, sha256(payload))) { "Biome snapshot SHA-256 mismatch" }
            val input = DataInputStream(ByteArrayInputStream(payload))
            require(input.readInt() == MAGIC) { "Biome snapshot magic mismatch" }
            require(input.readUnsignedShort() == VERSION) { "Unsupported biome snapshot version" }
            require(input.readUTF() == CAPTURE_MODE) { "Unsupported biome snapshot capture mode" }
            require(input.readUTF() == CODEC) { "Unsupported biome snapshot codec" }
            val createdAt = Instant.ofEpochMilli(input.readLong())
            val worldId = WorldId(readUuid(input))
            val paperWorldId = readUuid(input)
            val areaId = AreaId(readUuid(input))
            val id = readUuid(input)
            val regionCount = input.readInt().also { require(it in 1..MAX_REGIONS) { "Invalid biome snapshot region count" } }
            val regions = List(regionCount) { RegionId(readUuid(input)) }
            require(regions.distinct().size == regions.size) { "Biome snapshot contains duplicate region ids" }
            val cellCount = input.readInt().also { require(it in 1..MAX_CELLS) { "Invalid biome snapshot cell count" } }
            val cells = List(cellCount) {
                val cell = BiomeCell(input.readInt(), input.readInt(), input.readInt())
                val length = input.readUnsignedShort().also { require(it in 1..MAX_KEY_BYTES) { "Invalid biome key length" } }
                val key = ByteArray(length).also(input::readFully).toString(StandardCharsets.UTF_8)
                BiomeSnapshotCell(cell, key)
            }
            require(input.available() == 0) { "Biome snapshot contains trailing payload bytes" }
            return BiomeSnapshotV1(id, worldId, paperWorldId, areaId, regions, createdAt, cells)
        }

        private fun writeUuid(out: DataOutputStream, uuid: UUID) { out.writeLong(uuid.mostSignificantBits); out.writeLong(uuid.leastSignificantBits) }
        private fun readUuid(input: DataInputStream) = UUID(input.readLong(), input.readLong())
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        private fun sha256Hex(bytes: ByteArray) = sha256(bytes).joinToString("") { "%02x".format(it) }
    }
}
