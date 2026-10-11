package systems.diath.homeclaim.platform.paper.plot.mutation

import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.policy.CorrelationId
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

enum class PlotMutationJobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    ROLLED_BACK,
    FAILED,
    CANCELLED,
    RECOVERY_REQUIRED
}

data class PlotMutationJobRecord(
    val id: UUID,
    val correlationId: CorrelationId,
    val operation: String,
    val worldId: WorldId?,
    val areaId: AreaId,
    val worldName: String,
    val regionIds: List<RegionId>,
    val backend: String,
    val status: PlotMutationJobStatus,
    val completedUnits: Int = 0,
    val totalUnits: Int = 0,
    val error: String? = null,
    val createdAt: Instant = Instant.now(),
    val startedAt: Instant? = null,
    val updatedAt: Instant = createdAt,
    val completedAt: Instant? = null
)

/** Persistent operational journal for long-running world mutations. */
interface PlotMutationJobStore {
    fun create(record: PlotMutationJobRecord)
    fun markRunning(id: UUID)
    fun updateProgress(id: UUID, completedUnits: Int, totalUnits: Int)
    fun finish(id: UUID, status: PlotMutationJobStatus, completedUnits: Int, totalUnits: Int, error: String? = null)
    fun markInterruptedAsRecoveryRequired(): Int
    /**
     * Looks up one persisted job for operational diagnostics. This is deliberately
     * read-only: inspection never resumes, acknowledges, or changes a job.
     */
    fun findById(id: UUID): PlotMutationJobRecord?
    fun findRecoveryRequired(limit: Int = 100): List<PlotMutationJobRecord>
}

class JdbcPlotMutationJobStore(private val dataSource: DataSource) : PlotMutationJobStore {
    override fun create(record: PlotMutationJobRecord) {
        val sql = """
            INSERT INTO plot_mutation_jobs (
                id, correlation_id, operation, world_id, area_id, world_name, region_ids,
                backend, status, completed_units, total_units, error, created_at, started_at,
                updated_at, completed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, record.id)
                statement.setObject(2, record.correlationId.value)
                statement.setString(3, record.operation)
                statement.setObject(4, record.worldId?.value)
                statement.setObject(5, record.areaId.value)
                statement.setString(6, record.worldName)
                statement.setString(7, record.regionIds.joinToString(",") { it.value.toString() })
                statement.setString(8, record.backend)
                statement.setString(9, record.status.name)
                statement.setInt(10, record.completedUnits)
                statement.setInt(11, record.totalUnits)
                statement.setString(12, record.error)
                statement.setTimestamp(13, Timestamp.from(record.createdAt))
                statement.setTimestamp(14, record.startedAt?.let(Timestamp::from))
                statement.setTimestamp(15, Timestamp.from(record.updatedAt))
                statement.setTimestamp(16, record.completedAt?.let(Timestamp::from))
                statement.executeUpdate()
            }
        }
    }

    override fun markRunning(id: UUID) {
        update("""
            UPDATE plot_mutation_jobs
            SET status = ?, started_at = COALESCE(started_at, CURRENT_TIMESTAMP), updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND status = ?
        """.trimIndent(), PlotMutationJobStatus.RUNNING.name, id, PlotMutationJobStatus.QUEUED.name)
    }

    override fun updateProgress(id: UUID, completedUnits: Int, totalUnits: Int) {
        require(completedUnits >= 0 && totalUnits >= completedUnits)
        update("""
            UPDATE plot_mutation_jobs
            SET completed_units = ?, total_units = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND status = ?
        """.trimIndent(), completedUnits, totalUnits, id, PlotMutationJobStatus.RUNNING.name)
    }

    override fun finish(
        id: UUID,
        status: PlotMutationJobStatus,
        completedUnits: Int,
        totalUnits: Int,
        error: String?
    ) {
        require(status in setOf(
            PlotMutationJobStatus.COMPLETED,
            PlotMutationJobStatus.ROLLED_BACK,
            PlotMutationJobStatus.FAILED,
            PlotMutationJobStatus.CANCELLED,
            PlotMutationJobStatus.RECOVERY_REQUIRED
        ))
        require(completedUnits >= 0 && totalUnits >= completedUnits)
        update("""
            UPDATE plot_mutation_jobs
            SET status = ?, completed_units = ?, total_units = ?, error = ?,
                updated_at = CURRENT_TIMESTAMP, completed_at = CURRENT_TIMESTAMP
            WHERE id = ? AND status IN (?, ?)
        """.trimIndent(), status.name, completedUnits, totalUnits, error, id,
            PlotMutationJobStatus.QUEUED.name, PlotMutationJobStatus.RUNNING.name)
    }

    override fun markInterruptedAsRecoveryRequired(): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("""
            UPDATE plot_mutation_jobs
            SET status = ?, error = 'Interrupted by server restart; inspect and recover manually',
                updated_at = CURRENT_TIMESTAMP, completed_at = CURRENT_TIMESTAMP
            WHERE status IN (?, ?)
        """.trimIndent()).use { statement ->
            statement.setString(1, PlotMutationJobStatus.RECOVERY_REQUIRED.name)
            statement.setString(2, PlotMutationJobStatus.QUEUED.name)
            statement.setString(3, PlotMutationJobStatus.RUNNING.name)
            statement.executeUpdate()
        }
    }

    override fun findById(id: UUID): PlotMutationJobRecord? = dataSource.connection.use { connection ->
        connection.prepareStatement("""
            SELECT id, correlation_id, operation, area_id, world_name, region_ids, backend,
                   world_id, status, completed_units, total_units, error, created_at, started_at,
                   updated_at, completed_at
            FROM plot_mutation_jobs
            WHERE id = ?
        """.trimIndent()).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { result ->
                if (result.next()) result.toRecordOrNull() else null
            }
        }
    }

    override fun findRecoveryRequired(limit: Int): List<PlotMutationJobRecord> {
        require(limit in 1..500)
        return dataSource.connection.use { connection ->
            connection.prepareStatement("""
                SELECT id, correlation_id, operation, area_id, world_name, region_ids, backend,
                       world_id,
                       status, completed_units, total_units, error, created_at, started_at,
                       updated_at, completed_at
                FROM plot_mutation_jobs
                WHERE status = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
            """.trimIndent()).use { statement ->
                statement.setString(1, PlotMutationJobStatus.RECOVERY_REQUIRED.name)
                statement.setInt(2, limit)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            result.toRecordOrNull()?.let(::add)
                        }
                    }
                }
            }
        }
    }

    /** A malformed historical row is unavailable to diagnostics, not a command crash. */
    private fun java.sql.ResultSet.toRecordOrNull(): PlotMutationJobRecord? = runCatching {
        val regionIds = getString("region_ids")
            .split(',').mapNotNull { raw -> raw.takeIf(String::isNotBlank)?.let { RegionId(UUID.fromString(it)) } }
        PlotMutationJobRecord(
            id = UUID.fromString(getString("id")),
            correlationId = CorrelationId(UUID.fromString(getString("correlation_id"))),
            operation = getString("operation"),
            worldId = getString("world_id")?.let { WorldId(UUID.fromString(it)) },
            areaId = AreaId(UUID.fromString(getString("area_id"))),
            worldName = getString("world_name"),
            regionIds = regionIds,
            backend = getString("backend"),
            status = PlotMutationJobStatus.valueOf(getString("status")),
            completedUnits = getInt("completed_units"),
            totalUnits = getInt("total_units"),
            error = getString("error"),
            createdAt = getTimestamp("created_at").toInstant(),
            startedAt = getTimestamp("started_at")?.toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
            completedAt = getTimestamp("completed_at")?.toInstant()
        )
    }.getOrNull()

    private fun update(sql: String, vararg values: Any?) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                val updated = statement.executeUpdate()
                check(updated == 1) { "Mutation job state update affected $updated rows" }
            }
        }
    }
}
