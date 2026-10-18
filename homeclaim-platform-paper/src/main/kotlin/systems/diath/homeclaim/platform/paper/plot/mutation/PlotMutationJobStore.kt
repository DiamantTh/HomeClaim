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
    RECOVERY_REQUIRED,
    RECOVERY_IN_PROGRESS,
    RECOVERED,
    ACKNOWLEDGED
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
    val completedAt: Instant? = null,
    val details: String? = null
)

/** Persistent operational journal for long-running world mutations. */
interface PlotMutationJobStore {
    fun create(record: PlotMutationJobRecord)
    fun markRunning(id: UUID)
    fun updateProgress(id: UUID, completedUnits: Int, totalUnits: Int)
    fun updateDetails(id: UUID, details: String) = Unit
    fun finish(id: UUID, status: PlotMutationJobStatus, completedUnits: Int, totalUnits: Int, error: String? = null)
    fun markInterruptedAsRecoveryRequired(): Int
    /**
     * Looks up one persisted job for operational diagnostics. This is deliberately
     * read-only: inspection never resumes, acknowledges, or changes a job.
     */
    fun findById(id: UUID): PlotMutationJobRecord?
    fun findRecoveryRequired(limit: Int = 100): List<PlotMutationJobRecord>
    fun findRecoveryRequiredForRegions(regionIds: Collection<RegionId>): List<PlotMutationJobRecord> {
        val targets = regionIds.toSet()
        return if (targets.isEmpty()) emptyList() else findRecoveryRequired(500).filter { job -> job.regionIds.any(targets::contains) }
    }

    /** Atomically claims an explicitly confirmed operator recovery. */
    fun beginRecovery(id: UUID, recoveryDetails: String): Boolean = false

    /** Persists the recovery outcome while retaining the original mutation job. */
    fun finishRecovery(id: UUID, status: PlotMutationJobStatus, recoveryDetails: String, error: String? = null): Boolean = false
}

class JdbcPlotMutationJobStore(private val dataSource: DataSource) : PlotMutationJobStore {
    override fun create(record: PlotMutationJobRecord) {
        val sql = """
            INSERT INTO plot_mutation_jobs (
                id, correlation_id, operation, world_id, area_id, world_name, region_ids,
                backend, status, completed_units, total_units, error, created_at, started_at,
                updated_at, completed_at, details
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                statement.setString(17, record.details)
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

    override fun updateDetails(id: UUID, details: String) {
        require(details.length <= MAX_DETAILS_LENGTH)
        update("""
            UPDATE plot_mutation_jobs SET details = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND status IN (?, ?)
        """.trimIndent(), details, id, PlotMutationJobStatus.QUEUED.name, PlotMutationJobStatus.RUNNING.name)
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
        connection.autoCommit = false
        try {
            val interrupted = connection.prepareStatement("""
                SELECT id, correlation_id, operation, area_id, world_name, region_ids, backend,
                       world_id, status, completed_units, total_units, error, created_at, started_at,
                       updated_at, completed_at, details
                FROM plot_mutation_jobs WHERE status IN (?, ?, ?) ORDER BY created_at, id
            """.trimIndent()).use { statement ->
                statement.setString(1, PlotMutationJobStatus.QUEUED.name)
                statement.setString(2, PlotMutationJobStatus.RUNNING.name)
                statement.setString(3, PlotMutationJobStatus.RECOVERY_IN_PROGRESS.name)
                statement.executeQuery().use { result ->
                    buildList { while (result.next()) result.toRecordOrNull()?.let(::add) }
                }
            }
            var changed = 0
            for (record in interrupted) {
                val classification = PlotMutationRestartClassifier.classify(record)
                val detail = appendDetail(record.details, "restartClassification=${classification.reason}")
                connection.prepareStatement("""
                    UPDATE plot_mutation_jobs
                    SET status = ?, details = ?, error = ?, updated_at = CURRENT_TIMESTAMP,
                        completed_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status IN (?, ?, ?)
                """.trimIndent()).use { statement ->
                    statement.setString(1, classification.status.name)
                    statement.setString(2, detail)
                    statement.setString(3, classification.message)
                    statement.setObject(4, record.id)
                    statement.setString(5, PlotMutationJobStatus.QUEUED.name)
                    statement.setString(6, PlotMutationJobStatus.RUNNING.name)
                    statement.setString(7, PlotMutationJobStatus.RECOVERY_IN_PROGRESS.name)
                    changed += statement.executeUpdate()
                }
            }
            connection.commit()
            changed
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    override fun beginRecovery(id: UUID, recoveryDetails: String): Boolean {
        require(recoveryDetails.length <= MAX_DETAILS_LENGTH)
        return dataSource.connection.use { connection ->
            connection.prepareStatement("""
                UPDATE plot_mutation_jobs SET status = ?, details = ?, updated_at = CURRENT_TIMESTAMP,
                    completed_at = NULL
                WHERE id = ? AND status = ?
            """.trimIndent()).use { statement ->
                statement.setString(1, PlotMutationJobStatus.RECOVERY_IN_PROGRESS.name)
                statement.setString(2, recoveryDetails)
                statement.setObject(3, id)
                statement.setString(4, PlotMutationJobStatus.RECOVERY_REQUIRED.name)
                statement.executeUpdate() == 1
            }
        }
    }

    override fun finishRecovery(id: UUID, status: PlotMutationJobStatus, recoveryDetails: String, error: String?): Boolean {
        require(status in setOf(PlotMutationJobStatus.RECOVERED, PlotMutationJobStatus.ACKNOWLEDGED, PlotMutationJobStatus.RECOVERY_REQUIRED))
        require(recoveryDetails.length <= MAX_DETAILS_LENGTH)
        return dataSource.connection.use { connection ->
            connection.prepareStatement("""
                UPDATE plot_mutation_jobs SET status = ?, details = ?, error = ?, updated_at = CURRENT_TIMESTAMP,
                    completed_at = CASE WHEN ? = ? THEN NULL ELSE CURRENT_TIMESTAMP END
                WHERE id = ? AND status = ?
            """.trimIndent()).use { statement ->
                statement.setString(1, status.name)
                statement.setString(2, recoveryDetails)
                statement.setString(3, error)
                statement.setString(4, status.name)
                statement.setString(5, PlotMutationJobStatus.RECOVERY_REQUIRED.name)
                statement.setObject(6, id)
                statement.setString(7, PlotMutationJobStatus.RECOVERY_IN_PROGRESS.name)
                statement.executeUpdate() == 1
            }
        }
    }

    override fun findById(id: UUID): PlotMutationJobRecord? = dataSource.connection.use { connection ->
        connection.prepareStatement("""
            SELECT id, correlation_id, operation, area_id, world_name, region_ids, backend,
                   world_id, status, completed_units, total_units, error, created_at, started_at,
                   updated_at, completed_at, details
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
                       updated_at, completed_at, details
                FROM plot_mutation_jobs
                WHERE status IN (?, ?)
                ORDER BY created_at DESC, id DESC
                LIMIT ?
            """.trimIndent()).use { statement ->
                statement.setString(1, PlotMutationJobStatus.RECOVERY_REQUIRED.name)
                statement.setString(2, PlotMutationJobStatus.RECOVERY_IN_PROGRESS.name)
                statement.setInt(3, limit)
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

    override fun findRecoveryRequiredForRegions(regionIds: Collection<RegionId>): List<PlotMutationJobRecord> {
        val targets = regionIds.map { it.value.toString() }.distinct()
        if (targets.isEmpty()) return emptyList()
        val tokenClauses = targets.joinToString(" OR ") { "(region_ids = ? OR region_ids LIKE ? OR region_ids LIKE ? OR region_ids LIKE ?)" }
        val sql = """
            SELECT id, correlation_id, operation, area_id, world_name, region_ids, backend, world_id,
                   status, completed_units, total_units, error, created_at, started_at, updated_at, completed_at, details
            FROM plot_mutation_jobs
            WHERE status IN (?, ?) AND ($tokenClauses)
            ORDER BY created_at DESC, id DESC
        """.trimIndent()
        return dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, PlotMutationJobStatus.RECOVERY_REQUIRED.name)
                statement.setString(2, PlotMutationJobStatus.RECOVERY_IN_PROGRESS.name)
                var index = 3
                targets.forEach { id ->
                    statement.setString(index++, id)
                    statement.setString(index++, "$id,%")
                    statement.setString(index++, "%,$id,%")
                    statement.setString(index++, "%,$id")
                }
                statement.executeQuery().use { result ->
                    buildList { while (result.next()) result.toRecordOrNull()?.let(::add) }
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
            completedAt = getTimestamp("completed_at")?.toInstant(),
            details = getString("details")
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

    private companion object {
        const val MAX_DETAILS_LENGTH = 8192
    }

    private fun appendDetail(details: String?, value: String): String {
        val result = listOfNotNull(details?.takeIf(String::isNotBlank), value).joinToString("\n")
        // Status and error carry the classification even for a legacy row whose
        // diagnostic payload already occupies the entire bounded details column.
        return result.takeIf { it.length <= MAX_DETAILS_LENGTH } ?: details.orEmpty()
    }
}

data class PlotMutationRestartClassification(
    val status: PlotMutationJobStatus,
    val reason: String,
    val message: String
)

/** Conservative startup classification: only durable pre-world phases are cleanly failed. */
object PlotMutationRestartClassifier {
    fun classify(record: PlotMutationJobRecord): PlotMutationRestartClassification {
        if (record.status == PlotMutationJobStatus.RECOVERY_IN_PROGRESS) {
            return PlotMutationRestartClassification(
                PlotMutationJobStatus.RECOVERY_REQUIRED,
                "operator_recovery_interrupted",
                "An operator recovery was interrupted by server restart; inspect and request a new explicit recovery"
            )
        }
        if (record.status == PlotMutationJobStatus.QUEUED) {
            return PlotMutationRestartClassification(
                PlotMutationJobStatus.FAILED,
                "queued_before_execution",
                "Interrupted before the mutation job entered RUNNING; no world mutation was started"
            )
        }
        val values = record.details.orEmpty().lineSequence().mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
        }.toMap()
        val phase = values["phase"]?.uppercase()
        val safelyBeforeWorldWrite = when {
            record.operation in setOf("COPY", "MOVE", "SWAP") -> phase in setOf("VALIDATING", "BACKING_UP")
            record.operation.startsWith("RESET_") -> phase in setOf("BACKING_UP", "VALIDATING")
            record.operation == "RESTORE" -> phase in setOf("VALIDATING", "BACKING_UP_SAFETY")
            else -> false
        }
        return if (safelyBeforeWorldWrite) {
            PlotMutationRestartClassification(
                PlotMutationJobStatus.FAILED,
                "interrupted_before_world_write",
                "Interrupted before the durable phase that begins world mutation"
            )
        } else {
            PlotMutationRestartClassification(
                PlotMutationJobStatus.RECOVERY_REQUIRED,
                "world_mutation_phase_uncertain",
                "Interrupted during or after a world mutation phase; inspect and explicitly recover"
            )
        }
    }
}
