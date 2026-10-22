package systems.diath.homeclaim.platform.paper.plot.mutation

import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.policy.CorrelationId
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

enum class PlotBackupStatus { CREATING, READY, FAILED, CORRUPT }
enum class PlotBackupCompression {
    NONE,
    ZSTD;

    companion object {
        fun parse(value: String): PlotBackupCompression = when (value.trim().lowercase()) {
            "none" -> NONE
            "zstd" -> ZSTD
            else -> throw IllegalArgumentException("Unsupported backup compression '$value'; expected 'zstd' or 'none'")
        }
    }
}

enum class PlotBackupApplyKind { TRANSFER, RESTORE }
enum class PlotBackupApplyPhase { APPLY_STARTED, APPLY_COMPLETED, VERIFY_COMPLETED }

enum class PlotBackupCaptureMode(val wireId: Int) {
    TICKWISE(1);

    companion object {
        fun fromWireId(id: Int): PlotBackupCaptureMode? = entries.firstOrNull { it.wireId == id }
    }
}

enum class PlotBackupCapability(val wireId: Int) {
    BLOCK_DATA(1),
    CONTAINER_INVENTORY(2);

    companion object {
        fun fromWireId(id: Int): PlotBackupCapability? = entries.firstOrNull { it.wireId == id }
    }
}

/** File-manifest metadata; intentionally not duplicated in the relational backup index. */
data class PlotBackupCaptureMetadata(
    val mode: PlotBackupCaptureMode,
    val startedAt: Instant,
    val completedAt: Instant,
    val capturedBlockCount: Long,
    val capabilities: Set<PlotBackupCapability>
)

/** No-op in production. Tests may inject deterministic failures without a config or command backdoor. */
fun interface PlotBackupFaultInjector {
    fun afterBlockWrite(kind: PlotBackupApplyKind, appliedBlocks: Int)

    companion object {
        val NONE = PlotBackupFaultInjector { _, _ -> }
    }
}

/** Persistent metadata for a versioned file payload. The file path is derived from UUIDs, never stored as input. */
data class PlotBackupRecord(
    val id: UUID,
    val correlationId: CorrelationId,
    val worldId: WorldId,
    val paperWorldId: UUID,
    val areaId: AreaId,
    val regionId: RegionId,
    val bounds: Bounds,
    val createdByKind: String,
    val createdByRef: String?,
    val cause: String,
    val status: PlotBackupStatus,
    val formatVersion: Int,
    val compression: PlotBackupCompression,
    val blockCount: Long,
    val payloadBytes: Long = 0,
    val checksumSha256: String? = null,
    val error: String? = null,
    val createdAt: Instant = Instant.now(),
    val readyAt: Instant? = null,
    /** Read from the versioned manifest; the database index stores the encoded payload size. */
    val uncompressedPayloadBytes: Long? = null,
    /** Present in HCBK v3; older manifests do not claim capture timing or exact capabilities. */
    val captureMetadata: PlotBackupCaptureMetadata? = null
)

interface PlotBackupIndex {
    fun create(record: PlotBackupRecord)
    fun markReady(id: UUID, payloadBytes: Long, checksumSha256: String)
    fun markFailed(id: UUID, status: PlotBackupStatus, error: String)
    fun find(id: UUID): PlotBackupRecord?
    fun list(regionId: RegionId, limit: Int = 20): List<PlotBackupRecord>
}

class JdbcPlotBackupIndex(private val dataSource: DataSource) : PlotBackupIndex {
    override fun create(record: PlotBackupRecord) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                INSERT INTO plot_backups (
                    id, correlation_id, world_id, paper_world_id, area_id, region_id,
                    min_x, max_x, min_y, max_y, min_z, max_z,
                    created_by_kind, created_by_ref, cause, status, format_version,
                    compression, block_count, payload_bytes, checksum_sha256, error,
                    created_at, ready_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { statement ->
                statement.setObject(1, record.id)
                statement.setObject(2, record.correlationId.value)
                statement.setObject(3, record.worldId.value)
                statement.setObject(4, record.paperWorldId)
                statement.setObject(5, record.areaId.value)
                statement.setObject(6, record.regionId.value)
                statement.setInt(7, record.bounds.minX)
                statement.setInt(8, record.bounds.maxX)
                statement.setInt(9, record.bounds.minY)
                statement.setInt(10, record.bounds.maxY)
                statement.setInt(11, record.bounds.minZ)
                statement.setInt(12, record.bounds.maxZ)
                statement.setString(13, record.createdByKind)
                statement.setString(14, record.createdByRef)
                statement.setString(15, record.cause)
                statement.setString(16, record.status.name)
                statement.setInt(17, record.formatVersion)
                statement.setString(18, record.compression.name)
                statement.setLong(19, record.blockCount)
                statement.setLong(20, record.payloadBytes)
                statement.setString(21, record.checksumSha256)
                statement.setString(22, record.error)
                statement.setTimestamp(23, Timestamp.from(record.createdAt))
                statement.setTimestamp(24, record.readyAt?.let(Timestamp::from))
                statement.executeUpdate()
            }
        }
    }

    override fun markReady(id: UUID, payloadBytes: Long, checksumSha256: String) {
        require(payloadBytes >= 0 && checksumSha256.matches(Regex("[0-9a-f]{64}")))
        update("""
            UPDATE plot_backups SET status = ?, payload_bytes = ?, checksum_sha256 = ?,
                ready_at = CURRENT_TIMESTAMP, error = NULL
            WHERE id = ? AND status = ?
        """.trimIndent(), PlotBackupStatus.READY.name, payloadBytes, checksumSha256,
            id, PlotBackupStatus.CREATING.name)
    }

    override fun markFailed(id: UUID, status: PlotBackupStatus, error: String) {
        require(status == PlotBackupStatus.FAILED || status == PlotBackupStatus.CORRUPT)
        update("""
            UPDATE plot_backups SET status = ?, error = ?
            WHERE id = ? AND status IN (?, ?)
        """.trimIndent(), status.name, error.take(MAX_ERROR_LENGTH), id,
            PlotBackupStatus.CREATING.name, PlotBackupStatus.READY.name)
    }

    override fun find(id: UUID): PlotBackupRecord? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT * FROM plot_backups WHERE id = ?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { result -> if (result.next()) result.toBackupRecord() else null }
        }
    }

    override fun list(regionId: RegionId, limit: Int): List<PlotBackupRecord> {
        require(limit in 1..MAX_LIST_LIMIT)
        return dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT * FROM plot_backups WHERE region_id = ? AND status = ?
                ORDER BY created_at DESC, id DESC LIMIT ?
            """.trimIndent()).use { statement ->
                statement.setObject(1, regionId.value)
                statement.setString(2, PlotBackupStatus.READY.name)
                statement.setInt(3, limit)
                statement.executeQuery().use { result ->
                    buildList { while (result.next()) add(result.toBackupRecord()) }
                }
            }
        }
    }

    private fun java.sql.ResultSet.toBackupRecord(): PlotBackupRecord = PlotBackupRecord(
        id = UUID.fromString(getString("id")),
        correlationId = CorrelationId(UUID.fromString(getString("correlation_id"))),
        worldId = WorldId(UUID.fromString(getString("world_id"))),
        paperWorldId = UUID.fromString(getString("paper_world_id")),
        areaId = AreaId(UUID.fromString(getString("area_id"))),
        regionId = RegionId(UUID.fromString(getString("region_id"))),
        bounds = Bounds(
            getInt("min_x"), getInt("max_x"), getInt("min_y"), getInt("max_y"), getInt("min_z"), getInt("max_z")
        ),
        createdByKind = getString("created_by_kind"),
        createdByRef = getString("created_by_ref"),
        cause = getString("cause"),
        status = PlotBackupStatus.valueOf(getString("status")),
        formatVersion = getInt("format_version"),
        compression = PlotBackupCompression.valueOf(getString("compression")),
        blockCount = getLong("block_count"),
        payloadBytes = getLong("payload_bytes"),
        checksumSha256 = getString("checksum_sha256"),
        error = getString("error"),
        createdAt = getTimestamp("created_at").toInstant(),
        readyAt = getTimestamp("ready_at")?.toInstant()
    )

    private fun update(sql: String, vararg values: Any?) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                val updated = statement.executeUpdate()
                check(updated == 1) { "Backup state update affected $updated rows" }
            }
        }
    }

    private companion object {
        const val MAX_LIST_LIMIT = 100
        const val MAX_ERROR_LENGTH = 2048
    }
}
