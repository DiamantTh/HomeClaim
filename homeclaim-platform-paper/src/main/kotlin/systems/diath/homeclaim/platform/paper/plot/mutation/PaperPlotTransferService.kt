package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.ActionContext
import systems.diath.homeclaim.core.policy.ActionEndpoint
import systems.diath.homeclaim.core.policy.ActionKey
import systems.diath.homeclaim.core.policy.ActionRequest
import systems.diath.homeclaim.core.policy.ActionResourceRef
import systems.diath.homeclaim.core.policy.CorrelationId
import systems.diath.homeclaim.core.policy.HomeClaimOperationActions
import systems.diath.homeclaim.core.service.AuditEntries
import systems.diath.homeclaim.core.service.AuditService
import systems.diath.homeclaim.core.service.AuditStatus
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

/** One journaled and reserved Paper workflow for copy, move, and swap. */
class PaperPlotTransferService(
    private val plugin: JavaPlugin,
    private val backups: PaperPlotBackupService,
    private val conflicts: RegionMutationConflictCoordinator,
    private val jobs: PlotMutationJobStore,
    private val auditService: AuditService?,
    private val faultInjector: PlotTransferFaultInjector = PlotTransferFaultInjector.NONE
) {
    private val activeTasks = java.util.concurrent.ConcurrentHashMap.newKeySet<TransferTask>()
    fun execute(
        plan: PlotTransferPlan,
        world: World,
        actor: ActionActor,
        correlationId: CorrelationId
    ): CompletableFuture<PlotTransferResult> {
        val result = CompletableFuture<PlotTransferResult>()
        val action = actionFor(plan.operation)
        val request = request(plan, world, actor, correlationId, action)
        val reservationOperation = when (plan.operation) {
            PlotTransferOperation.COPY -> RegionMutationOperation.COPY
            PlotTransferOperation.MOVE -> RegionMutationOperation.MOVE
            PlotTransferOperation.SWAP -> RegionMutationOperation.SWAP
        }
        val lease = when (val attempt = conflicts.reserve(
            reservationOperation, plan.regionIds, actor = actor, correlationId = correlationId
        )) {
            is ReservationAttempt.Conflict -> {
                runCatching { audit(request, AuditStatus.DENIED, attempt.conflict.toString()) }
                    .onFailure { plugin.logger.log(Level.WARNING, "Could not audit transfer conflict", it) }
                result.complete(PlotTransferResult.Conflict(attempt.conflict.toString()))
                return result
            }
            is ReservationAttempt.Acquired -> attempt.lease
        }
        val sourceBackupId = UUID.randomUUID()
        val targetBackupId = UUID.randomUUID()
        val detail = details(plan, actor, world, sourceBackupId, targetBackupId, "VALIDATING")
        val jobId = UUID.randomUUID()
        val totalSteps = when (plan.operation) {
            PlotTransferOperation.COPY -> 3
            PlotTransferOperation.MOVE, PlotTransferOperation.SWAP -> 4
        }
        val job = PlotMutationJobRecord(
            id = jobId,
            correlationId = correlationId,
            operation = plan.operation.name,
            worldId = plan.worldId,
            areaId = plan.source.areaId,
            worldName = world.name,
            regionIds = plan.regionIds,
            backend = "paper-native-transfer-v1",
            status = PlotMutationJobStatus.QUEUED,
            totalUnits = totalSteps,
            details = detail.encode()
        )
        var journalCreated = false
        try {
            audit(request, AuditStatus.ATTEMPTED)
            jobs.create(job)
            journalCreated = true
            jobs.markRunning(jobId)
        } catch (failure: Throwable) {
            lease.close()
            if (journalCreated) runCatching {
                jobs.finish(jobId, PlotMutationJobStatus.FAILED, 0, totalSteps, "Could not start transfer: ${failure.message}")
            }.onFailure { plugin.logger.log(Level.SEVERE, "Could not close failed transfer journal $jobId", it) }
            runCatching { audit(request, AuditStatus.FAILED, "Could not start transfer journal: ${failure.message}") }
            result.complete(PlotTransferResult.Failed(jobId, "Could not start transfer journal: ${failure.message}", false))
            return result
        }
        val task = TransferTask(plan, world, actor, correlationId, request, job, lease, sourceBackupId, targetBackupId, totalSteps, result)
        activeTasks += task
        runCatching { jobs.updateDetails(jobId, detail.copy(phase = "BACKING_UP").encode()) }
            .onFailure { fail(task, it, false); return result }

        val sourceBackup = runCatching {
            backups.createSnapshot(plan.source, world, actor, correlationId,
                "transfer:${plan.operation.name.lowercase()}:source", sourceBackupId)
        }.getOrElse { fail(task, it, false); return result }
        val targetBackup = runCatching {
            backups.createSnapshot(plan.target, world, actor, correlationId,
                "transfer:${plan.operation.name.lowercase()}:target", targetBackupId)
        }.getOrElse { fail(task, it, false); return result }
        sourceBackup.thenCombine(targetBackup) { sourceRecord, targetRecord -> sourceRecord to targetRecord }
            .whenComplete { pair, failure ->
                onServer {
                    if (failure != null) {
                        fail(task, unwrap(failure), false)
                        return@onServer
                    }
                    try {
                        check(pair.first.status == PlotBackupStatus.READY && pair.second.status == PlotBackupStatus.READY) {
                            "Both source and target safety backups must be READY before transfer mutation"
                        }
                    } catch (_: IllegalStateException) {
                        fail(task, IllegalStateException("Both transfer backups must be READY"), false)
                        return@onServer
                    }
                    if (pair.first.id != task.sourceBackupId || pair.second.id != task.targetBackupId) {
                        fail(task, IllegalStateException("Transfer backup identity mismatch"), false)
                        return@onServer
                    }
                    backups.validateReadyBackup(task.sourceBackupId)
                        .thenCombine(backups.validateReadyBackup(task.targetBackupId)) { sourceReady, targetReady -> sourceReady to targetReady }
                        .whenComplete { validated, validationFailure -> onServer {
                            if (validationFailure != null) {
                                fail(task, unwrap(validationFailure), false)
                                return@onServer
                            }
                            if (validated.first.id != task.sourceBackupId || validated.second.id != task.targetBackupId) {
                                fail(task, IllegalStateException("Validated transfer backup identity mismatch"), false)
                                return@onServer
                            }
                            task.completedUnits = 2
                            runCatching {
                                jobs.updateProgress(jobId, task.completedUnits, totalSteps)
                                jobs.updateDetails(jobId, details(task, "APPLYING_TARGET").encode())
                            }.onFailure { fail(task, it, false); return@onServer }
                            applySourceToTarget(task)
                        } }
                }
            }
        return result
    }

    private fun applySourceToTarget(task: TransferTask) {
        backups.applyBackupToTarget(
            task.sourceBackupId, task.plan.source, task.plan.target, task.world, task.plan.worldId, task.correlationId
        ) { applied -> if (applied > 0) { task.worldMutated = true; task.targetChanged = true } }.whenComplete { _, failure -> onServer {
            if (failure != null) {
                fail(task, unwrap(failure), messageImpliesPartial(failure))
                return@onServer
            }
            task.completedUnits = 3
            if (task.plan.operation == PlotTransferOperation.COPY) {
                complete(task)
            } else if (task.plan.operation == PlotTransferOperation.MOVE) {
                updatePhase(task, "CLEANING_SOURCE") {
                    faultInjector.beforePhase(PlotTransferPhase.SOURCE_CLEANUP)
                    clearSource(task)
                }
            } else {
                updatePhase(task, "APPLYING_SECONDARY") {
                    faultInjector.beforePhase(PlotTransferPhase.SECONDARY_APPLY)
                    backups.applyBackupToTarget(
                        task.targetBackupId, task.plan.target, task.plan.source, task.world,
                        task.plan.worldId, task.correlationId
                    ) { applied -> if (applied > 0) { task.worldMutated = true; task.sourceChanged = true } }.whenComplete { _, secondFailure -> onServer {
                        if (secondFailure != null) fail(task, unwrap(secondFailure), true)
                        else { task.completedUnits = 4; complete(task) }
                    } }
                }
            }
        } }
    }

    private fun clearSource(task: TransferTask) {
        val region = task.plan.source
        val minY = task.world.minHeight
        val maxY = task.world.maxHeight - 1
        val total = (region.bounds.maxX.toLong() - region.bounds.minX + 1) *
            (region.bounds.maxZ.toLong() - region.bounds.minZ + 1) * (maxY.toLong() - minY + 1)
        if (total <= 0 || total > PlotBackupFormat.MAX_BLOCKS) {
            fail(task, IllegalStateException("Source cleanup volume is outside supported limits"), true)
            return
        }
        var cursor = 0L
        var taskHandle: BukkitTask? = null
        fun finish(error: Throwable? = null) {
            taskHandle?.cancel()
            taskHandle = null
            task.scheduledTask = null
            if (error != null) { fail(task, error, true); return }
            task.completedUnits = 4
            complete(task)
        }
        try {
            taskHandle = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
                try {
                    var changed = 0
                    while (cursor < total && changed < CLEANUP_BLOCKS_PER_TICK) {
                        val xSize = region.bounds.maxX.toLong() - region.bounds.minX + 1
                        val zSize = region.bounds.maxZ.toLong() - region.bounds.minZ + 1
                        val ySize = maxY.toLong() - minY + 1
                        val y = minY + (cursor % ySize).toInt()
                        val column = cursor / ySize
                        val x = region.bounds.minX + (column / zSize).toInt()
                        val z = region.bounds.minZ + (column % zSize).toInt()
                        val block = task.world.getBlockAt(x, y, z)
                        block.setType(org.bukkit.Material.AIR, false)
                        task.sourceChanged = true
                        cursor++
                        changed++
                    }
                    if (cursor >= total) {
                        taskHandle?.cancel()
                        verifySourceEmpty(task, minY, maxY, total) { error -> finish(error) }
                    }
                } catch (failure: Throwable) { finish(failure) }
            }, 1L, 1L)
            task.scheduledTask = taskHandle
        } catch (failure: Throwable) { finish(failure) }
    }

    private fun verifySourceEmpty(task: TransferTask, minY: Int, maxY: Int, total: Long, finish: (Throwable?) -> Unit) {
        val region = task.plan.source
        var cursor = 0L
        var verifyTask: BukkitTask? = null
        try {
            verifyTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
                try {
                    var checked = 0
                    val xSize = region.bounds.maxX.toLong() - region.bounds.minX + 1
                    val zSize = region.bounds.maxZ.toLong() - region.bounds.minZ + 1
                    val ySize = maxY.toLong() - minY + 1
                    while (cursor < total && checked < CLEANUP_BLOCKS_PER_TICK) {
                        val y = minY + (cursor % ySize).toInt()
                        val column = cursor / ySize
                        val x = region.bounds.minX + (column / zSize).toInt()
                        val z = region.bounds.minZ + (column % zSize).toInt()
                        check(task.world.getBlockAt(x, y, z).type.isAir) { "Source cleanup verification failed at $x,$y,$z" }
                        cursor++
                        checked++
                    }
                    if (cursor >= total) {
                        verifyTask?.cancel()
                        task.scheduledTask = null
                        finish(null)
                    }
                } catch (failure: Throwable) {
                    verifyTask?.cancel()
                    finish(failure)
                }
            }, 1L, 1L)
            task.scheduledTask = verifyTask
        } catch (failure: Throwable) { finish(failure) }
    }

    private fun updatePhase(task: TransferTask, phase: String, next: () -> Unit) {
        try {
            jobs.updateProgress(task.job.id, task.completedUnits, task.totalSteps)
            jobs.updateDetails(task.job.id, details(task, phase).encode())
            next()
        } catch (failure: Throwable) { fail(task, failure, task.completedUnits > 0) }
    }

    private fun complete(task: TransferTask) {
        if (!task.completed.compareAndSet(false, true)) return
        val detail = details(task, "COMPLETED").encode()
        val terminalFailure = runCatching {
            jobs.updateDetails(task.job.id, detail)
            jobs.finish(task.job.id, PlotMutationJobStatus.COMPLETED, task.totalSteps, task.totalSteps)
        }.exceptionOrNull()
        task.lease.close()
        task.scheduledTask?.cancel()
        activeTasks.remove(task)
        if (terminalFailure != null) {
            val failure = terminalFailure
            val persisted = runCatching { jobs.findById(task.job.id) }.getOrNull()
            if (persisted?.status == PlotMutationJobStatus.COMPLETED) {
                val auditError = runCatching { audit(task.request, AuditStatus.COMPLETED, detail) }.exceptionOrNull()
                task.result.complete(PlotTransferResult.Completed(task.job.id, task.sourceBackupId, task.targetBackupId))
                if (auditError != null) plugin.logger.log(Level.SEVERE, "Transfer ${task.job.id} completed but terminal audit failed", auditError)
                return
            }
            runCatching { jobs.finish(task.job.id, PlotMutationJobStatus.RECOVERY_REQUIRED, task.completedUnits, task.totalSteps,
                "World transfer completed but terminal journal finalization failed: ${failure.message}; $detail") }
                .onFailure { plugin.logger.log(Level.SEVERE, "Could not mark transfer ${task.job.id} as recovery required", it) }
            runCatching { audit(task.request, AuditStatus.FAILED,
                "World transfer completed, but terminal journal finalization failed: ${failure.message}; $detail") }
            task.result.complete(PlotTransferResult.Failed(task.job.id, "Transfer completed in world, but final state needs inspection", true))
        } else {
            val auditError = runCatching { audit(task.request, AuditStatus.COMPLETED, detail) }.exceptionOrNull()
            task.result.complete(PlotTransferResult.Completed(task.job.id, task.sourceBackupId, task.targetBackupId))
            if (auditError != null) plugin.logger.log(Level.SEVERE, "Transfer ${task.job.id} completed but terminal audit failed", auditError)
        }
    }

    private fun fail(task: TransferTask, failure: Throwable, partial: Boolean) {
        if (!task.completed.compareAndSet(false, true)) return
        val recoveryRequired = partial || task.worldMutated
        val status = if (recoveryRequired) PlotMutationJobStatus.RECOVERY_REQUIRED else PlotMutationJobStatus.FAILED
        val phase = if (recoveryRequired) "RECOVERY_REQUIRED" else "FAILED"
        val details = details(task, phase).encode()
        val message = "${failure.message ?: failure.javaClass.simpleName}; $details"
        runCatching { jobs.updateDetails(task.job.id, details) }
        runCatching { jobs.finish(task.job.id, status, task.completedUnits, task.totalSteps, message) }
            .onFailure { journalFailure -> plugin.logger.log(Level.SEVERE, "Could not finalize transfer job ${task.job.id}", journalFailure) }
        runCatching { audit(task.request, AuditStatus.FAILED, message) }
            .onFailure { auditFailure -> plugin.logger.log(Level.SEVERE, "Could not audit transfer failure ${task.job.id}", auditFailure) }
        task.lease.close()
        task.scheduledTask?.cancel()
        activeTasks.remove(task)
        task.result.complete(PlotTransferResult.Failed(task.job.id, message, recoveryRequired))
    }

    private fun audit(request: ActionRequest, status: AuditStatus, detail: String? = null) {
        auditService?.append(AuditEntries.operationResult(request, status, detail, 2))
    }

    private fun details(task: TransferTask, phase: String) = details(
        task.plan, task.actor, task.world, task.sourceBackupId, task.targetBackupId, phase,
        sourceChanged = task.sourceChanged, targetChanged = task.targetChanged
    )

    private fun details(
        plan: PlotTransferPlan,
        actor: ActionActor,
        world: World,
        sourceBackupId: UUID,
        targetBackupId: UUID,
        phase: String,
        sourceChanged: Boolean = false,
        targetChanged: Boolean = false
    ) = PlotTransferJournalDetails(
        operation = plan.operation,
        sourceRegionId = plan.source.id.value,
        targetRegionId = plan.target.id.value,
        sourceBackupId = sourceBackupId,
        targetBackupId = targetBackupId,
        phase = phase,
        areaId = plan.source.areaId,
        worldId = plan.worldId,
        paperWorldId = world.uid,
        actorKind = actor.kind,
        actorRef = actor.stableId,
        sourceChanged = sourceChanged,
        targetChanged = targetChanged
    )

    private fun request(
        plan: PlotTransferPlan,
        world: World,
        actor: ActionActor,
        correlationId: CorrelationId,
        action: ActionKey
    ): ActionRequest {
        val worldId = plan.worldId
        val areaId = plan.source.areaId
        return ActionRequest(
            actor = actor,
            actionKey = action,
            target = ActionEndpoint(
                position = Position(worldId, center(plan.target.bounds.minX, plan.target.bounds.maxX),
                    center(plan.target.bounds.minY, plan.target.bounds.maxY), center(plan.target.bounds.minZ, plan.target.bounds.maxZ)),
                resource = ActionResourceRef.Region(plan.target.id),
                areaId = areaId,
            ),
            source = ActionEndpoint(
                position = Position(worldId, center(plan.source.bounds.minX, plan.source.bounds.maxX),
                    center(plan.source.bounds.minY, plan.source.bounds.maxY), center(plan.source.bounds.minZ, plan.source.bounds.maxZ)),
                resource = ActionResourceRef.Region(plan.source.id),
                areaId = areaId,
            ),
            regionId = plan.target.id,
            areaId = areaId,
            context = ActionContext(
                platform = "paper",
                eventName = "plot_${plan.operation.name.lowercase()}",
                attributes = mapOf(
                    "sourceRegion" to plan.source.id.value.toString(),
                    "targetRegion" to plan.target.id.value.toString(),
                    "worldBinding" to world.uid.toString()
                )
            ),
            correlationId = correlationId
        )
    }

    private fun actionFor(operation: PlotTransferOperation): ActionKey = when (operation) {
        PlotTransferOperation.COPY -> HomeClaimOperationActions.REGION_COPY
        PlotTransferOperation.MOVE -> HomeClaimOperationActions.REGION_MOVE
        PlotTransferOperation.SWAP -> HomeClaimOperationActions.REGION_SWAP
    }

    private fun center(min: Int, max: Int): Int = (min.toLong() + (max.toLong() - min.toLong()) / 2L).toInt()

    private fun onServer(block: () -> Unit) {
        if (Bukkit.isPrimaryThread()) block()
        else Bukkit.getScheduler().runTask(plugin, Runnable(block))
    }

    fun shutdown() {
        activeTasks.toList().forEach { task ->
            task.scheduledTask?.cancel()
            fail(task, IllegalStateException("Transfer interrupted by plugin shutdown"), task.worldMutated)
        }
    }

    private fun unwrap(error: Throwable): Throwable = error.cause ?: error
    private fun messageImpliesPartial(error: Throwable): Boolean =
        generateSequence(error as Throwable?) { it.cause }.any { it.message?.contains("partially changed", ignoreCase = true) == true }

    private data class TransferTask(
        val plan: PlotTransferPlan,
        val world: World,
        val actor: ActionActor,
        val correlationId: CorrelationId,
        val request: ActionRequest,
        val job: PlotMutationJobRecord,
        val lease: RegionMutationConflictCoordinator.Lease,
        var sourceBackupId: UUID,
        var targetBackupId: UUID,
        val totalSteps: Int,
        val result: CompletableFuture<PlotTransferResult>,
        var completedUnits: Int = 0,
        @Volatile var worldMutated: Boolean = false,
        @Volatile var sourceChanged: Boolean = false,
        @Volatile var targetChanged: Boolean = false,
        @Volatile var scheduledTask: BukkitTask? = null,
        val completed: AtomicBoolean = AtomicBoolean()
    )

    private companion object {
        const val CLEANUP_BLOCKS_PER_TICK = 512
    }
}

/** Narrow dependency-injection seam for isolated integration tests; production always uses [NONE]. */
enum class PlotTransferPhase { SOURCE_CLEANUP, SECONDARY_APPLY }

fun interface PlotTransferFaultInjector {
    fun beforePhase(phase: PlotTransferPhase)

    companion object {
        val NONE = PlotTransferFaultInjector { }
    }
}
