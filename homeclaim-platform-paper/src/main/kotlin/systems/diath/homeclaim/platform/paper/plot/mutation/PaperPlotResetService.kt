package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask
import org.bukkit.plugin.java.JavaPlugin
import systems.diath.homeclaim.core.mutation.MutationJobInfo
import systems.diath.homeclaim.core.mutation.MutationReason
import systems.diath.homeclaim.core.policy.CorrelationId
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.mutation.WorldMutationBackend
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.service.WorldAreaService
import systems.diath.homeclaim.platform.paper.plot.PlotWorldChunkGenerator
import systems.diath.homeclaim.platform.paper.plot.PlotWorldConfigStore
import systems.diath.homeclaim.platform.paper.plot.sanitized
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

/**
 * Paper-only first cut for heavier plot resets.
 *
 * The job runs in small sync batches and restores the interior of a plot region
 * using the configured plot generator defaults, while clearing the build volume above.
 */
class PaperPlotResetService(
    private val plugin: JavaPlugin,
    private val configStore: PlotWorldConfigStore = PlotWorldConfigStore(plugin),
    private val mutationBackend: WorldMutationBackend = PaperSynchronousWorldMutationBackend(),
    private val jobStore: PlotMutationJobStore? = null,
    private val worldAreaService: WorldAreaService? = null,
    private val backupService: PaperPlotBackupService? = null,
    private val conflicts: RegionMutationConflictCoordinator? = null
) : PlotResetService {
    private val queueRegistry = PlotJobRegistry()
    private val activeResets = ConcurrentHashMap<String, () -> Unit>()

    init {
        jobStore?.let { store ->
            val interrupted = store.markInterruptedAsRecoveryRequired()
            if (interrupted > 0) {
                plugin.logger.severe("$interrupted plot mutation job(s) were interrupted by a previous server shutdown; inspect /plot jobs status")
            }
            store.findRecoveryRequired().forEach { job ->
                plugin.logger.severe(
                    "Plot mutation ${job.operation} requires recovery: job=${job.id}, region=${job.regionIds.joinToString()}, " +
                        "world=${job.worldName}, progress=${job.completedUnits}/${job.totalUnits}, correlation=${job.correlationId}"
                )
            }
        }
    }

    override fun shutdown() {
        activeResets.values.toList().forEach { cancel ->
            runCatching(cancel).onFailure { error ->
                plugin.logger.log(java.util.logging.Level.SEVERE, "Could not finish a reset during shutdown", error)
            }
        }
    }

    override fun cancelPendingJobs(worldName: String?): Int {
        return queueRegistry.requestCancelAll(world = worldName, kind = PlotJobRegistry.JobKind.RESET)
    }

    override fun activeJobDiagnostics(worldName: String?): List<String> {
        return queueRegistry.snapshot(world = worldName, kind = PlotJobRegistry.JobKind.RESET).map { snapshot ->
            "reset:${snapshot.world}:${snapshot.key}:reason=${snapshot.reason?.name ?: MutationReason.RESET.name}:age=${snapshot.ageMillis}ms:cancelled=${snapshot.cancelRequested}"
        }
    }

    override fun recoveryRequiredDiagnostics(worldName: String?): List<String> = runCatching {
        jobStore?.findRecoveryRequired()?.filter { worldName == null || it.worldName == worldName }?.map { record ->
            "recovery-required:${record.operation}:${record.worldName}:${record.id}:regions=${record.regionIds.joinToString()}:progress=${record.completedUnits}/${record.totalUnits}:correlation=${record.correlationId}"
        }.orEmpty()
    }.getOrElse { error ->
        plugin.logger.log(java.util.logging.Level.SEVERE, "Could not read recovery-required plot jobs", error)
        listOf("recovery-required:status lookup failed: ${error.message ?: error.javaClass.simpleName}")
    }

    override fun activeJobs(worldName: String?): List<PlotJobSnapshot> {
        return queueRegistry.snapshot(world = worldName, kind = PlotJobRegistry.JobKind.RESET).map { snapshot ->
            PlotJobSnapshot(
                key = snapshot.key,
                world = snapshot.world,
                kind = PlotJobRegistry.JobKind.RESET.name,
                reason = snapshot.reason,
                ageMillis = snapshot.ageMillis,
                cancelRequested = snapshot.cancelRequested
            )
        }
    }

    override fun activeJobInfo(worldName: String?): List<MutationJobInfo> {
        return queueRegistry.mutationJobInfo(world = worldName, kind = PlotJobRegistry.JobKind.RESET, defaultReason = MutationReason.RESET)
    }

    override fun queueReset(region: Region, reason: PlotResetReason): Boolean {
        return queueReset(region, reason, CorrelationId.newId()) { }
    }

    override fun queueReset(
        region: Region,
        reason: PlotResetReason,
        onComplete: (PlotResetCompletion) -> Unit
    ): Boolean = queueReset(region, reason, CorrelationId.newId(), onComplete)

    override fun queueReset(
        region: Region,
        reason: PlotResetReason,
        correlationId: CorrelationId,
        onComplete: (PlotResetCompletion) -> Unit
    ): Boolean = queueReset(region, reason, correlationId, ActionActor.System(), onComplete)

    override fun queueReset(
        region: Region,
        reason: PlotResetReason,
        correlationId: CorrelationId,
        actor: ActionActor,
        onComplete: (PlotResetCompletion) -> Unit
    ): Boolean {
        val resolved = resolveWorld(region) ?: return false
        val world = resolved.first
        val worldName = resolved.second
        val config = configStore.loadConfig(worldName)?.sanitized() ?: return false
        if (reason == PlotResetReason.DELETE && !config.resetOnDelete) return false
        if (reason == PlotResetReason.UNCLAIM && !config.resetOnUnclaim) return false

        val runtimeReservation = when (val attempt = conflicts?.reserve(
            RegionMutationOperation.RESET, listOf(region.id), region.mergeGroupId, actor, correlationId
        )) {
            null -> null
            is ReservationAttempt.Acquired -> attempt.lease
            is ReservationAttempt.Conflict -> return false
        }

        val jobKey = "paper-reset:${world.uid}:${region.id.value}"
        val jobHandle = queueRegistry.tryAcquire(
            key = jobKey,
            world = worldName,
            kind = PlotJobRegistry.JobKind.RESET,
            reason = MutationReason.RESET,
            maxConcurrentPerWorld = config.maxConcurrentResetJobsPerWorld,
            timeoutMillis = config.jobTimeoutMillis
        ) ?: run {
            runtimeReservation?.close()
            val reasonText = if (queueRegistry.isActive(jobKey, config.jobTimeoutMillis)) "duplicate" else "world-limit"
            plugin.logger.fine("Skipping $reasonText Paper plot reset for ${region.id.value}")
            return false
        }

        val columns = ArrayDeque(PlotResetPlanner.interiorColumns(region.bounds))
        if (columns.isEmpty()) {
            jobHandle.close()
            runtimeReservation?.close()
            return false
        }
        val totalColumns = columns.size
        var completedColumns = 0
        var mutationAttempted = false
        val persistentJobId = UUID.randomUUID()
        val persistentJob = PlotMutationJobRecord(
            id = persistentJobId,
            correlationId = correlationId,
            operation = "RESET_${reason.name}",
            worldId = worldAreaService?.getArea(region.areaId)?.worldId,
            areaId = region.areaId,
            worldName = worldName,
            regionIds = listOf(region.id),
            backend = mutationBackend.backendId,
            status = PlotMutationJobStatus.QUEUED,
            totalUnits = totalColumns
        )
        try {
            jobStore?.create(persistentJob)
        } catch (error: Throwable) {
            jobHandle.close()
            runtimeReservation?.close()
            plugin.logger.log(java.util.logging.Level.SEVERE, "Could not persist reset job before mutation for ${region.id.value}", error)
            return false
        }
        var journalStarted = false
        val completionSent = AtomicBoolean(false)
        var scheduledTask: BukkitTask? = null
        fun finish(status: PlotResetCompletionStatus, error: String? = null) {
            if (!completionSent.compareAndSet(false, true)) return
            scheduledTask?.cancel()
            jobHandle.close()
            activeResets.remove(jobKey)
            val resultPartial = mutationAttempted && (status != PlotResetCompletionStatus.COMPLETED || completedColumns < totalColumns)
            val persistedStatus = PlotResetCompletion(status, completedColumns, resultPartial, error).persistentJobStatus()
            val journalError = runCatching {
                jobStore?.finish(
                    persistentJobId,
                    persistedStatus,
                    completedColumns,
                    totalColumns,
                    error
                )
            }.exceptionOrNull()
            if (journalError != null) {
                runCatching {
                    jobStore?.finish(
                        persistentJobId,
                        if (mutationAttempted) PlotMutationJobStatus.RECOVERY_REQUIRED else PlotMutationJobStatus.FAILED,
                        completedColumns,
                        totalColumns,
                        "Terminal journal write failed: ${journalError.message ?: journalError.javaClass.simpleName}"
                    )
                }.onFailure { fallbackError ->
                    plugin.logger.log(java.util.logging.Level.SEVERE, "Could not persist fallback reset recovery state for ${region.id.value}", fallbackError)
                }
            }
            val finalStatus = if (journalError != null && status == PlotResetCompletionStatus.COMPLETED) {
                PlotResetCompletionStatus.FAILED
            } else status
            if (journalError != null) {
                plugin.logger.log(java.util.logging.Level.SEVERE, "Could not persist terminal reset state for ${region.id.value}", journalError)
            }
            runtimeReservation?.close()
            val result = PlotResetCompletion(
                status = finalStatus,
                completedColumns = completedColumns,
                partialMutation = resultPartial,
                error = listOfNotNull(error, journalError?.let { "reset journal could not be finalized: ${it.message}" }).joinToString("; ").ifBlank { null }
            )
            runCatching { onComplete(result) }.onFailure { callbackError ->
                plugin.logger.log(java.util.logging.Level.SEVERE, "Reset completion callback failed for ${region.id.value}", callbackError)
            }
        }

        val generator = PlotWorldChunkGenerator(config)
        val minY = config.minGenHeight ?: region.bounds.minY
        val topY = (config.plotHeight - 1).coerceAtLeast(minY)
        val clearUntil = region.bounds.maxY
        val columnsPerTick = config.resetBatchColumnsPerTick.coerceAtLeast(1)

        val startMutation = {
          runCatching {
            scheduledTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
                if (jobHandle.isCancellationRequested(config.jobTimeoutMillis)) {
                    finish(PlotResetCompletionStatus.CANCELLED, "Reset cancelled after $completedColumns of $totalColumns columns")
                    return@Runnable
                }
                runCatching {
                    var processed = 0
                    while (processed < columnsPerTick && columns.isNotEmpty()) {
                        val batchColumns = ArrayList<Pair<Int, Int>>(columnsPerTick)
                        while (processed < columnsPerTick && columns.isNotEmpty()) {
                            batchColumns += columns.removeFirst()
                            processed++
                        }
                        val batch = PlotMutationPlanFactory.resetBatch(
                            id = jobKey,
                            world = world.name,
                            columns = batchColumns,
                            generator = generator,
                            minY = minY,
                            topY = topY,
                            clearUntil = clearUntil
                        )
                        if (!journalStarted) {
                            jobStore?.markRunning(persistentJobId)
                            journalStarted = true
                        }
                        mutationAttempted = true
                        mutationBackend.submit(batch)
                        completedColumns += batchColumns.size
                        if (completedColumns % PERSIST_PROGRESS_INTERVAL < batchColumns.size || columns.isEmpty()) {
                            jobStore?.updateProgress(persistentJobId, completedColumns, totalColumns)
                        }
                    }
                    if (columns.isEmpty()) {
                        finish(PlotResetCompletionStatus.COMPLETED)
                    }
                }.onFailure { error ->
                    plugin.logger.warning("Paper plot reset failed for ${region.id.value}: ${error.message}")
                    finish(
                        PlotResetCompletionStatus.FAILED,
                        "${error.message ?: error::class.simpleName ?: "mutation failed"}; $completedColumns of $totalColumns columns completed"
                    )
                }
            }, 1L, 1L)
            activeResets[jobKey] = {
                backupService?.cancelCapture(region.id)
                finish(PlotResetCompletionStatus.CANCELLED, "Reset cancelled by plugin shutdown after $completedColumns of $totalColumns columns")
            }

            plugin.logger.info("Queued plot reset for ${region.id.value} (${reason.name.lowercase()})")
            true
          }.getOrElse { error ->
            plugin.logger.log(java.util.logging.Level.WARNING, "Failed to schedule Paper plot reset for ${region.id.value}", error)
            finish(
                PlotResetCompletionStatus.FAILED,
                "Could not schedule reset: ${error.message ?: error.javaClass.simpleName}"
            )
            false
          }
        }
        activeResets[jobKey] = {
            backupService?.cancelCapture(region.id)
            finish(PlotResetCompletionStatus.CANCELLED, "Reset cancelled before world mutation")
        }
        val backups = backupService ?: run {
            finish(PlotResetCompletionStatus.FAILED, "Persistent plot backup service is unavailable; reset was not started")
            return false
        }
        val backupAccepted = backups.createBeforeReset(region, world, minY, clearUntil, actor, correlationId, "reset:${reason.name.lowercase()}") { backupResult ->
            if (backupResult.isFailure) {
                finish(PlotResetCompletionStatus.FAILED, "Pre-reset backup failed; reset was not started: ${backupResult.exceptionOrNull()?.message ?: "unknown backup error"}")
            } else {
                startMutation()
            }
        }
        if (!backupAccepted) {
            finish(PlotResetCompletionStatus.FAILED, "Pre-reset backup could not be started; reset was not started")
            return false
        }
        return true
    }

    private fun resolveWorld(region: Region): Pair<org.bukkit.World, String>? {
        val service = worldAreaService
        if (service == null) {
            val legacyWorld = Bukkit.getWorld(region.world) ?: return null
            return legacyWorld to region.world
        }
        val area = service.getArea(region.areaId) ?: return null
        val homeWorld = service.getWorld(area.worldId) ?: return null
        val platformId = homeWorld.platformWorldId
        val world = (if (platformId != null) Bukkit.getWorld(platformId) else Bukkit.getWorld(homeWorld.name))
            ?: return null
        return world to homeWorld.name
    }

    private companion object {
        const val PERSIST_PROGRESS_INTERVAL = 1024
    }
}
