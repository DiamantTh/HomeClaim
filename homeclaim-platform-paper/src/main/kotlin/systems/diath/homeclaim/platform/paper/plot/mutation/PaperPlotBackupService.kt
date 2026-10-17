package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.block.Container
import org.bukkit.block.TileState
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
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
import systems.diath.homeclaim.core.service.WorldAreaService
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

/** Paper-thread snapshot capture and restore with bounded file-worker handoffs. */
class PaperPlotBackupService(
    private val plugin: JavaPlugin,
    private val index: PlotBackupIndex,
    private val worldAreaService: WorldAreaService,
    private val jobStore: PlotMutationJobStore,
    private val auditService: AuditService?,
    private val conflicts: RegionMutationConflictCoordinator? = null
) {
    private val dataRoot = plugin.dataFolder.toPath().resolve("backups").toAbsolutePath().normalize()
    private val io: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "HomeClaim-PlotBackup-IO").apply { isDaemon = true }
    }
    private val capturesByRegion = ConcurrentHashMap<RegionId, CaptureTask>()
    private val restores = ConcurrentHashMap<UUID, RestoreTask>()
    private val applies = ConcurrentHashMap.newKeySet<ApplyTask>()
    private val closed = AtomicBoolean(false)

    fun listBackups(regionId: RegionId, limit: Int = 10): CompletableFuture<List<PlotBackupRecord>> = async {
        index.list(regionId, limit)
    }

    fun findBackup(id: UUID): CompletableFuture<PlotBackupRecord?> = async { index.find(id) }

    /** Rechecks a completed snapshot's manifest and payload before a multi-snapshot mutation starts. */
    fun validateReadyBackup(id: UUID): CompletableFuture<PlotBackupRecord> = async {
        val record = index.find(id) ?: throw IllegalArgumentException("Backup $id was not found")
        require(record.status == PlotBackupStatus.READY) { "Backup $id is not READY" }
        PlotBackupFormat.validate(backupFile(record), record)
        record
    }

    /** Starts a pre-reset backup. Completion is delivered on Paper's server thread. */
    fun createBeforeReset(
        region: Region,
        world: World,
        minY: Int,
        maxY: Int,
        actor: ActionActor,
        correlationId: CorrelationId,
        cause: String,
        backupId: UUID = UUID.randomUUID(),
        onComplete: (Result<PlotBackupRecord>) -> Unit
    ): Boolean {
        if (closed.get() || capturesByRegion.containsKey(region.id)) return false
        val areaWorldId = worldAreaService.getArea(region.areaId)?.worldId ?: return false
        val bounds = Bounds(region.bounds.minX, region.bounds.maxX, minY, maxY, region.bounds.minZ, region.bounds.maxZ)
        val count = runCatching { PlotBackupFormat.blockCount(bounds) }.getOrElse { return false }
        if (minY < world.minHeight || maxY >= world.maxHeight) return false

        val id = backupId
        val record = PlotBackupRecord(
            id = id,
            correlationId = correlationId,
            worldId = areaWorldId,
            paperWorldId = world.uid,
            areaId = region.areaId,
            regionId = region.id,
            bounds = bounds,
            createdByKind = actor.kind,
            createdByRef = actor.stableId,
            cause = cause.take(32),
            status = PlotBackupStatus.CREATING,
            formatVersion = PlotBackupFormat.CURRENT_VERSION,
            compression = PlotBackupCompression.NONE,
            blockCount = count,
            createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        )
        val directory = safePath("${region.areaId.value}", "${region.id.value}") ?: return false
        val finalFile = directory.resolve("${id}.hcbackup")
        val payload = safePath(".staging", "$id.payload.part") ?: return false
        val temporary = safePath(".staging", "$id.backup.part") ?: return false
        val task = CaptureTask(record, region, world, actor, cause, payload, temporary, finalFile, onComplete)
        if (capturesByRegion.putIfAbsent(region.id, task) != null) return false
        try { io.execute {
            try {
                audit(actor, region, correlationId, HomeClaimOperationActions.REGION_BACKUP_CREATE,
                    AuditStatus.ATTEMPTED, "backupId" to id.toString(), "cause" to cause)
                Files.createDirectories(directory)
                Files.createDirectories(payload.parent)
                Files.createFile(payload)
                index.create(record)
                task.indexCreated = true
                runOnServer { beginCapture(task) }
            } catch (failure: Throwable) {
                failCapture(task, failure)
            }
        } } catch (failure: Throwable) {
            capturesByRegion.remove(region.id, task)
            return false
        }
        return true
    }

    /** Creates a durable snapshot for a transfer while leaving mutation ownership to its caller. */
    fun createSnapshot(
        region: Region,
        world: World,
        actor: ActionActor,
        correlationId: CorrelationId,
        cause: String,
        backupId: UUID = UUID.randomUUID()
    ): CompletableFuture<PlotBackupRecord> {
        val result = CompletableFuture<PlotBackupRecord>()
        val minY = world.minHeight
        val maxY = world.maxHeight - 1
        val accepted = createBeforeReset(region, world, minY, maxY, actor, correlationId, cause, backupId) { backup ->
            backup.fold(result::complete) { error -> result.completeExceptionally(error) }
        }
        if (!accepted) result.completeExceptionally(IllegalStateException("Backup capture could not be started for ${region.id}"))
        return result
    }

    /** Applies a validated source snapshot to an equal-sized target; reservation and journaling belong to the transfer operation. */
    fun applyBackupToTarget(
        backupId: UUID,
        source: Region,
        target: Region,
        world: World,
        expectedWorldId: systems.diath.homeclaim.core.model.WorldId,
        correlationId: CorrelationId,
        onProgress: ((Int) -> Unit)? = null
    ): CompletableFuture<Int> {
        val result = CompletableFuture<Int>()
        // Read Paper properties while called from the server thread; the worker only retains immutable values.
        val paperWorldId = world.uid
        val minHeight = world.minHeight
        val maxHeight = world.maxHeight
        val task = ApplyTask(backupId, source, target, world, paperWorldId, minHeight, maxHeight, correlationId, result, onProgress)
        if (closed.get()) return CompletableFuture.failedFuture(IllegalStateException("Backup service is shutting down"))
        applies += task
        result.whenComplete { _, _ -> applies.remove(task) }
        try {
            io.execute {
                try {
                    val record = index.find(backupId) ?: throw IllegalArgumentException("Transfer backup was not found")
                    require(record.status == PlotBackupStatus.READY) { "Transfer backup is not READY" }
                    val sourceWorldId = worldAreaService.getArea(source.areaId)?.worldId
                        ?: throw IllegalStateException("Source Area no longer exists")
                    val targetWorldId = worldAreaService.getArea(target.areaId)?.worldId
                        ?: throw IllegalStateException("Target Area no longer exists")
                    require(record.regionId == source.id && record.areaId == source.areaId) { "Backup is not the declared source plot snapshot" }
                    require(record.worldId == expectedWorldId && sourceWorldId == expectedWorldId && targetWorldId == expectedWorldId) {
                        "Transfer source and target must remain in the same HomeClaim WorldId"
                    }
                    require(record.paperWorldId == paperWorldId) { "Transfer snapshot belongs to another Paper world binding" }
                    val sourceSize = plotDimensions(source)
                    val targetSize = plotDimensions(target)
                    require(sourceSize == targetSize) { "Transfer source and target dimensions differ" }
                    require(record.bounds.minX == source.bounds.minX && record.bounds.maxX == source.bounds.maxX &&
                        record.bounds.minZ == source.bounds.minZ && record.bounds.maxZ == source.bounds.maxZ) {
                        "Source bounds changed after its snapshot was created"
                    }
                    require(record.bounds.minY >= minHeight && record.bounds.maxY < maxHeight) {
                        "Snapshot height range is outside the currently loaded world"
                    }
                    val targetBounds = Bounds(target.bounds.minX, target.bounds.maxX, record.bounds.minY, record.bounds.maxY,
                        target.bounds.minZ, target.bounds.maxZ)
                    require(PlotBackupFormat.blockCount(targetBounds) == record.blockCount) { "Transfer snapshot volume does not match the target" }
                    val file = backupFile(record)
                    task.file = file
                    task.header = PlotBackupFormat.validate(file, record)
                    task.input = PlotBackupFormat.openPayload(file, requireNotNull(task.header))
                    io.execute { runOnServer { applyTransferBatch(task, targetBounds) } }
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                }
            }
        } catch (failure: Throwable) {
            result.completeExceptionally(failure)
        }
        return result
    }

    private fun applyTransferBatch(task: ApplyTask, targetBounds: Bounds) {
        if (task.completed.get()) return
        try {
            val record = requireNotNull(task.header).record
            val remaining = record.blockCount - task.applied
            if (remaining == 0L) {
                if (!task.verifying) {
                    task.verifying = true
                    io.execute {
                        try {
                            task.input?.close()
                            task.input = PlotBackupFormat.openPayload(task.file!!, task.header!!)
                            task.applied = 0
                            runOnServer { verifyTransferBatch(task, targetBounds) }
                        } catch (failure: Throwable) {
                            runOnServer { failApply(task, failure) }
                        }
                    }
                } else {
                    task.input?.close()
                    if (task.completed.compareAndSet(false, true)) task.result.complete(task.applied)
                }
                return
            }
            val batchSize = minOf(RESTORE_BATCH_BLOCKS.toLong(), remaining).toInt()
            io.execute {
                try {
                    val batch = PlotBackupFormat.readBatch(requireNotNull(task.input), record.bounds, batchSize)
                    runOnServer {
                        if (task.completed.get()) return@runOnServer
                        try {
                            val dx = targetBounds.minX - record.bounds.minX
                            val dz = targetBounds.minZ - record.bounds.minZ
                            for (snapshot in batch) {
                                val x = Math.addExact(snapshot.x, dx)
                                val z = Math.addExact(snapshot.z, dz)
                                val block = task.world.getBlockAt(x, snapshot.y, z)
                                block.setBlockData(Bukkit.createBlockData(snapshot.blockData), false)
                                task.applied++
                                task.onProgress?.invoke(task.applied)
                                val items = snapshot.inventoryItems ?: continue
                                val state = block.state
                                require(state is Container) { "Source container does not match the restored block at $x,${snapshot.y},$z" }
                                val inventory = state.snapshotInventory
                                require(inventory.size == items.size) { "Container size changed at $x,${snapshot.y},$z" }
                                inventory.contents = items.map { bytes -> bytes?.let(ItemStack::deserializeBytes) }.toTypedArray()
                                check(state.update(true, false)) { "Could not apply transferred container at $x,${snapshot.y},$z" }
                            }
                            applyTransferBatch(task, targetBounds)
                        } catch (failure: Throwable) {
                            failApply(task, failure)
                        }
                    }
                } catch (failure: Throwable) {
                    runOnServer { failApply(task, failure) }
                }
            }
        } catch (failure: Throwable) {
            failApply(task, failure)
        }
    }

    private fun verifyTransferBatch(task: ApplyTask, targetBounds: Bounds) {
        if (task.completed.get()) return
        try {
            val record = requireNotNull(task.header).record
            val remaining = record.blockCount - task.applied
            if (remaining == 0L) {
                task.input?.close()
                if (task.completed.compareAndSet(false, true)) task.result.complete(task.applied)
                return
            }
            val count = minOf(RESTORE_BATCH_BLOCKS.toLong(), remaining).toInt()
            io.execute {
                try {
                    val expected = PlotBackupFormat.readBatch(requireNotNull(task.input), record.bounds, count)
                    runOnServer {
                        if (task.completed.get()) return@runOnServer
                        try {
                            val dx = targetBounds.minX - record.bounds.minX
                            val dz = targetBounds.minZ - record.bounds.minZ
                            expected.forEach { snapshot ->
                                val x = Math.addExact(snapshot.x, dx)
                                val z = Math.addExact(snapshot.z, dz)
                                val block = task.world.getBlockAt(x, snapshot.y, z)
                                check(block.blockData.asString == snapshot.blockData) { "Transferred block validation failed at $x,${snapshot.y},$z" }
                                val items = snapshot.inventoryItems ?: return@forEach
                                val state = block.state
                                require(state is Container) { "Transferred container is missing at $x,${snapshot.y},$z" }
                                val contents = state.snapshotInventory.contents
                                require(contents.size == items.size) { "Transferred container size changed at $x,${snapshot.y},$z" }
                                items.indices.forEach { index ->
                                    val actual = contents[index]?.takeUnless { it.type.isAir }?.serializeAsBytes()
                                    val wanted = items[index]
                                    check(actual.contentEqualsNullable(wanted)) { "Transferred container contents differ at $x,${snapshot.y},$z slot $index" }
                                }
                            }
                            task.applied += expected.size
                            applyTransferBatch(task, targetBounds)
                        } catch (failure: Throwable) {
                            failApply(task, failure)
                        }
                    }
                } catch (failure: Throwable) {
                    runOnServer { failApply(task, failure) }
                }
            }
        } catch (failure: Throwable) {
            failApply(task, failure)
        }
    }

    private fun failApply(task: ApplyTask, failure: Throwable) {
        if (!task.completed.compareAndSet(false, true)) return
        runCatching { task.input?.close() }
        val decorated = if (task.applied > 0) IllegalStateException(
            "Transfer apply partially changed ${task.applied} blocks; recovery required", failure
        ) else failure
        task.result.completeExceptionally(decorated)
    }

    private fun plotDimensions(region: Region): Triple<Long, Long, Long> = Triple(
        region.bounds.maxX.toLong() - region.bounds.minX + 1,
        region.bounds.maxY.toLong() - region.bounds.minY + 1,
        region.bounds.maxZ.toLong() - region.bounds.minZ + 1
    )

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
        this == null -> other == null
        other == null -> false
        else -> java.security.MessageDigest.isEqual(this, other)
    }

    /** Validates and restores only to the exact originating region and Paper world. */
    fun restore(
        backupId: UUID,
        region: Region,
        world: World,
        actor: ActionActor,
        correlationId: CorrelationId
    ): CompletableFuture<PlotRestoreResult> {
        val result = CompletableFuture<PlotRestoreResult>()
        // Read Paper world properties on the calling/server thread; workers only use immutable IDs/bounds.
        val paperWorldId = world.uid
        val worldMinHeight = world.minHeight
        val worldMaxHeight = world.maxHeight
        val worldName = world.name
        val lease = when (val attempt = conflicts?.reserve(RegionMutationOperation.RESTORE, listOf(region.id), region.mergeGroupId, actor, correlationId)) {
            null -> null
            is ReservationAttempt.Acquired -> attempt.lease
            is ReservationAttempt.Conflict -> return CompletableFuture.failedFuture(IllegalStateException("Mutation conflict: ${attempt.conflict}"))
        }
        val task = RestoreTask(backupId, region, world, actor, correlationId, result, lease)
        if (closed.get()) {
            lease?.close()
            return CompletableFuture.failedFuture(IllegalStateException("Backup service is shutting down"))
        }
        if (restores.putIfAbsent(backupId, task) != null) {
            lease?.close()
            return CompletableFuture.failedFuture(IllegalStateException("This backup is already being restored"))
        }
        try {
            io.execute {
                try {
                    audit(actor, region, correlationId, HomeClaimOperationActions.REGION_RESTORE,
                        AuditStatus.ATTEMPTED, "backupId" to backupId.toString())
                    val record = index.find(backupId) ?: throw IllegalArgumentException("Backup was not found")
                    task.record = record
                    require(record.status == PlotBackupStatus.READY) { "Backup is not ready (${record.status})" }
                    val area = worldAreaService.getArea(region.areaId)
                        ?: throw IllegalStateException("Target Area no longer exists")
                    require(record.regionId == region.id && record.areaId == region.areaId) { "Backup belongs to another region or Area" }
                    require(record.worldId == area.worldId && record.paperWorldId == paperWorldId) { "Backup belongs to another world binding" }
                    require(record.bounds.minY >= worldMinHeight && record.bounds.maxY < worldMaxHeight) { "Backup height range is outside the currently loaded world" }
                    require(record.bounds.minX == region.bounds.minX && record.bounds.maxX == region.bounds.maxX &&
                        record.bounds.minZ == region.bounds.minZ && record.bounds.maxZ == region.bounds.maxZ) {
                        "Backup plot bounds no longer match the original region"
                    }
                    val file = backupFile(record)
                    task.file = file
                    val header = try {
                        PlotBackupFormat.validate(file, record)
                    } catch (validationFailure: Throwable) {
                        runCatching { index.markFailed(record.id, PlotBackupStatus.CORRUPT, validationFailure.message ?: "Backup validation failed") }
                        throw validationFailure
                    }
                    task.header = header
                    val job = PlotMutationJobRecord(
                        id = UUID.randomUUID(),
                        correlationId = correlationId,
                        operation = "RESTORE",
                        worldId = record.worldId,
                        areaId = record.areaId,
                        worldName = worldName,
                        regionIds = listOf(region.id),
                        backend = "paper-plot-backup-v${record.formatVersion}",
                        status = PlotMutationJobStatus.QUEUED,
                        totalUnits = record.blockCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    )
                    task.job = job
                    jobStore.create(job)
                    runOnServer { beginRestore(task) }
                } catch (failure: Throwable) {
                    finishRestore(task, failure)
                }
            }
        } catch (failure: Throwable) {
            restores.remove(backupId, task)
            lease?.close()
            result.completeExceptionally(failure)
        }
        return result
    }

    fun cancelCapture(regionId: RegionId) {
        capturesByRegion[regionId]?.let { failCapture(it, IllegalStateException("Backup capture cancelled by shutdown")) }
    }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        capturesByRegion.values.toList().forEach { failCapture(it, IllegalStateException("Backup capture cancelled by plugin shutdown")) }
        restores.values.toList().forEach { task ->
            finishRestore(task, IllegalStateException("Restore interrupted by plugin shutdown"))
        }
        applies.toList().forEach { task -> failApply(task, IllegalStateException("Transfer snapshot application interrupted by plugin shutdown")) }
        io.shutdown()
        runCatching { if (!io.awaitTermination(5, TimeUnit.SECONDS)) io.shutdownNow() }
    }

    private fun beginCapture(task: CaptureTask) {
        if (task.completed.get()) return
        val blockCount = task.record.blockCount
        var cursor = 0L
        fun captureBatch() {
            if (task.completed.get()) return
            try {
                val snapshots = ArrayList<PlotBackupFormat.BlockSnapshot>(CAPTURE_BATCH_BLOCKS)
                val end = minOf(blockCount, cursor + CAPTURE_BATCH_BLOCKS)
                var estimatedBytes = 0L
                while (cursor < end) {
                    val snapshot = captureBlock(task.world, task.record.bounds, cursor)
                    val snapshotBytes = snapshot.blockData.length * 2L +
                        snapshot.inventoryItems.orEmpty().sumOf { it?.size?.toLong() ?: 0L }
                    snapshots += snapshot
                    cursor++
                    estimatedBytes += snapshotBytes
                    if (estimatedBytes >= MAX_CAPTURE_BATCH_BYTES) break
                }
                if (snapshots.isEmpty()) {
                    io.execute {
                        try {
                            val (payloadBytes, checksum) = PlotBackupFormat.finalizeFile(
                                task.payload, task.temporary, task.finalFile, task.record
                            )
                            index.markReady(task.record.id, payloadBytes, checksum)
                            val ready = task.record.copy(
                                status = PlotBackupStatus.READY,
                                payloadBytes = payloadBytes,
                                checksumSha256 = checksum,
                                readyAt = Instant.now()
                            )
                            audit(task.actor, task.region, ready.correlationId, HomeClaimOperationActions.REGION_BACKUP_CREATE,
                                AuditStatus.COMPLETED, "backupId" to ready.id.toString(), "checksum" to checksum)
                            completeCapture(task, Result.success(ready))
                        } catch (failure: Throwable) {
                            failCapture(task, failure)
                        }
                    }
                    return
                }
                io.execute {
                    try {
                        PlotBackupFormat.appendPayload(task.payload, snapshots)
                        runOnServer { captureBatch() }
                    } catch (failure: Throwable) {
                        failCapture(task, failure)
                    }
                }
            } catch (failure: Throwable) {
                failCapture(task, failure)
            }
        }
        task.scheduled = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            task.scheduled?.cancel()
            task.scheduled = null
            captureBatch()
        }, 1L, 1L)
    }

    private fun captureBlock(world: World, bounds: Bounds, index: Long): PlotBackupFormat.BlockSnapshot {
        val height = bounds.maxY.toLong() - bounds.minY + 1
        val layer = bounds.maxX.toLong() - bounds.minX + 1
        val area = layer * (bounds.maxZ.toLong() - bounds.minZ + 1)
        val y = bounds.minY + (index % height).toInt()
        val column = index / height
        val x = bounds.minX + (column / (bounds.maxZ.toLong() - bounds.minZ + 1)).toInt()
        val z = bounds.minZ + (column % (bounds.maxZ.toLong() - bounds.minZ + 1)).toInt()
        check(index < area * height)
        val block = world.getBlockAt(x, y, z)
        val state = block.state
        val inventoryItems = when (state) {
            is Container -> state.snapshotInventory.contents.map { item ->
                item?.takeUnless { it.type.isAir }?.serializeAsBytes()?.also {
                    require(it.size <= PlotBackupFormat.MAX_ITEM_BYTES) { "A container item exceeds the backup format limit at $x,$y,$z" }
                }
            }
            is TileState -> throw IllegalStateException("Backup v1 cannot preserve ${block.type.key} block-state data at $x,$y,$z; reset was not started")
            else -> null
        }
        return PlotBackupFormat.BlockSnapshot(x, y, z, block.blockData.asString, inventoryItems)
    }

    private fun beginRestore(task: RestoreTask) {
        if (task.completed.get()) return
        val job = requireNotNull(task.job)
        try {
            jobStore.markRunning(job.id)
            task.input = PlotBackupFormat.openPayload(requireNotNull(task.file), requireNotNull(task.header))
            scheduleRestoreBatch(task)
        } catch (failure: Throwable) {
            finishRestore(task, failure)
        }
    }

    private fun scheduleRestoreBatch(task: RestoreTask) {
        if (task.completed.get()) return
        io.execute {
            try {
                val record = requireNotNull(task.record)
                val remaining = record.blockCount - task.applied
                if (remaining == 0L) {
                    runOnServer { finishRestore(task, null) }
                    return@execute
                }
                val batch = PlotBackupFormat.readBatch(requireNotNull(task.input), record.bounds, minOf(RESTORE_BATCH_BLOCKS.toLong(), remaining).toInt())
                runOnServer { applyRestoreBatch(task, batch) }
            } catch (failure: Throwable) {
                finishRestore(task, failure)
            }
        }
    }

    private fun applyRestoreBatch(task: RestoreTask, batch: List<PlotBackupFormat.BlockSnapshot>) {
        if (task.completed.get()) return
        try {
            for (snapshot in batch) {
                val block = task.world.getBlockAt(snapshot.x, snapshot.y, snapshot.z)
                block.setBlockData(Bukkit.createBlockData(snapshot.blockData), false)
                task.applied++
                val items = snapshot.inventoryItems ?: continue
                val state = block.state
                require(state is Container) { "Backup expects a container at ${snapshot.x},${snapshot.y},${snapshot.z}" }
                val inventory = state.snapshotInventory
                require(inventory.size == items.size) { "Container size changed at ${snapshot.x},${snapshot.y},${snapshot.z}" }
                val decoded = items.map { bytes -> bytes?.let(ItemStack::deserializeBytes) }.toTypedArray()
                inventory.contents = decoded
                check(state.update(true, false)) { "Could not restore container state at ${snapshot.x},${snapshot.y},${snapshot.z}" }
            }
            if (task.applied % RESTORE_PROGRESS_INTERVAL < batch.size) {
                io.execute { runCatching { jobStore.updateProgress(requireNotNull(task.job).id, task.applied, requireNotNull(task.job).totalUnits) } }
            }
            scheduleRestoreBatch(task)
        } catch (failure: Throwable) {
            finishRestore(task, failure)
        }
    }

    private fun finishRestore(task: RestoreTask, failure: Throwable?) {
        if (!task.completed.compareAndSet(false, true)) return
        task.input?.let { runCatching { it.close() } }
        task.scheduled?.cancel()
        restores.remove(task.backupId, task)
        val job = task.job
        val applied = task.applied
        val status = when {
            failure == null -> PlotMutationJobStatus.COMPLETED
            applied > 0 -> PlotMutationJobStatus.RECOVERY_REQUIRED
            else -> PlotMutationJobStatus.FAILED
        }
        io.execute {
            try {
                if (job != null) jobStore.finish(job.id, status, applied, job.totalUnits, failure?.message)
                if (failure == null) {
                    val auditRecorded = runCatching {
                        audit(task.actor, task.region, task.correlationId, HomeClaimOperationActions.REGION_RESTORE,
                            AuditStatus.COMPLETED, "backupId" to task.backupId.toString())
                    }.onFailure { auditFailure -> plugin.logger.log(Level.SEVERE, "Restore completed but terminal audit could not be queued for ${task.backupId}", auditFailure) }.isSuccess
                    val restoreResult = PlotRestoreResult(task.backupId, applied, status, auditRecorded)
                    if (closed.get()) task.result.complete(restoreResult) else runOnServer { task.result.complete(restoreResult) }
                } else {
                    runCatching {
                        audit(task.actor, task.region, task.correlationId, HomeClaimOperationActions.REGION_RESTORE,
                            AuditStatus.FAILED, "backupId" to task.backupId.toString(), "failure" to (failure.message ?: failure.javaClass.simpleName))
                    }.onFailure { auditFailure -> plugin.logger.log(Level.SEVERE, "Restore failure audit could not be queued for ${task.backupId}", auditFailure) }
                    val exception = IllegalStateException(
                        if (applied > 0) "Restore partially changed the world; recovery required (job ${job?.id})"
                        else "Restore failed before changing the world: ${failure.message}", failure
                    )
                    if (closed.get()) task.result.completeExceptionally(exception) else runOnServer { task.result.completeExceptionally(exception) }
                }
            } catch (journalFailure: Throwable) {
                plugin.logger.log(Level.SEVERE, "Could not finalize backup restore job ${job?.id}", journalFailure)
                if (job != null) runCatching {
                    jobStore.finish(
                        job.id,
                        if (applied > 0) PlotMutationJobStatus.RECOVERY_REQUIRED else PlotMutationJobStatus.FAILED,
                        applied,
                        job.totalUnits,
                        "Terminal journal write failed: ${journalFailure.message ?: journalFailure.javaClass.simpleName}"
                    )
                }.onFailure { fallbackError -> plugin.logger.log(Level.SEVERE, "Could not persist fallback restore recovery state for ${job.id}", fallbackError) }
                runOnServer { task.result.completeExceptionally(journalFailure) }
            } finally {
                task.lease?.close()
            }
        }
    }

    private fun failCapture(task: CaptureTask, failure: Throwable) {
        if (!task.completed.compareAndSet(false, true)) return
        task.scheduled?.cancel()
        capturesByRegion.remove(task.region.id, task)
        io.execute {
            val current = runCatching { if (task.indexCreated) index.find(task.record.id) else null }.getOrNull()
            runCatching {
                if (task.indexCreated && current?.status != PlotBackupStatus.READY) {
                    index.markFailed(task.record.id, PlotBackupStatus.FAILED, failure.message ?: failure.javaClass.simpleName)
                }
            }
            runCatching { Files.deleteIfExists(task.payload); Files.deleteIfExists(task.temporary) }
            // A finalized file with a failed READY/audit transition is retained only when the
            // index says READY. Otherwise remove it so a partial snapshot is never discoverable.
            runCatching {
                if (task.indexCreated && index.find(task.record.id)?.status != PlotBackupStatus.READY) Files.deleteIfExists(task.finalFile)
            }
            runCatching {
                audit(task.actor, task.region, task.record.correlationId, HomeClaimOperationActions.REGION_BACKUP_CREATE,
                    AuditStatus.FAILED, "backupId" to task.record.id.toString(), "failure" to (failure.message ?: failure.javaClass.simpleName))
            }
            runOnServer { task.callback(Result.failure(failure)) }
        }
    }

    private fun completeCapture(task: CaptureTask, result: Result<PlotBackupRecord>) {
        if (!task.completed.compareAndSet(false, true)) return
        capturesByRegion.remove(task.region.id, task)
        runCatching { Files.deleteIfExists(task.payload) }
        runOnServer { task.callback(result) }
    }

    private fun audit(
        actor: ActionActor,
        region: Region,
        correlationId: CorrelationId,
        action: ActionKey,
        status: AuditStatus,
        vararg details: Pair<String, String>
    ) {
        val request = ActionRequest(
            actor = actor,
            actionKey = action,
            target = ActionEndpoint(resource = ActionResourceRef.Region(region.id), areaId = region.areaId),
            regionId = region.id,
            areaId = region.areaId,
            context = ActionContext(platform = "paper", eventName = action.value.substringAfter(':'), attributes = details.toMap()),
            correlationId = correlationId
        )
        auditService?.append(AuditEntries.operationResult(request, status))
    }

    private fun backupFile(record: PlotBackupRecord): Path =
        requireNotNull(safePath("${record.areaId.value}", "${record.regionId.value}", "${record.id}.hcbackup"))

    private fun safePath(vararg components: String): Path? = runCatching {
        val resolved = components.fold(dataRoot) { path, part -> path.resolve(part) }.normalize()
        require(resolved.startsWith(dataRoot))
        resolved
    }.getOrNull()

    private fun <T> async(work: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(work, io)

    private fun runOnServer(action: () -> Unit) {
        if (closed.get()) {
            if (Bukkit.isPrimaryThread()) action()
            return
        }
        try {
            Bukkit.getScheduler().runTask(plugin, Runnable { if (!closed.get()) action() })
        } catch (failure: Throwable) {
            plugin.logger.log(Level.WARNING, "Could not schedule plot backup callback on the Paper thread", failure)
        }
    }

    private inner class CaptureTask(
        val record: PlotBackupRecord,
        val region: Region,
        val world: World,
        val actor: ActionActor,
        val cause: String,
        val payload: Path,
        val temporary: Path,
        val finalFile: Path,
        val callback: (Result<PlotBackupRecord>) -> Unit,
        val completed: AtomicBoolean = AtomicBoolean()
    ) {
        @Volatile var indexCreated = false
        @Volatile var scheduled: BukkitTask? = null
    }

    private inner class RestoreTask(
        val backupId: UUID,
        val region: Region,
        val world: World,
        val actor: ActionActor,
        val correlationId: CorrelationId,
        val result: CompletableFuture<PlotRestoreResult>,
        val lease: RegionMutationConflictCoordinator.Lease?,
        val completed: AtomicBoolean = AtomicBoolean()
    ) {
        @Volatile var record: PlotBackupRecord? = null
        @Volatile var file: Path? = null
        @Volatile var header: PlotBackupFormat.Header? = null
        @Volatile var input: java.io.DataInputStream? = null
        @Volatile var job: PlotMutationJobRecord? = null
        @Volatile var applied: Int = 0
        @Volatile var scheduled: BukkitTask? = null
    }

    private inner class ApplyTask(
        val backupId: UUID,
        val source: Region,
        val target: Region,
        val world: World,
        val paperWorldId: UUID,
        val minHeight: Int,
        val maxHeight: Int,
        val correlationId: CorrelationId,
        val result: CompletableFuture<Int>,
        val onProgress: ((Int) -> Unit)?,
        val completed: AtomicBoolean = AtomicBoolean()
    ) {
        @Volatile var header: PlotBackupFormat.Header? = null
        @Volatile var file: Path? = null
        @Volatile var input: java.io.DataInputStream? = null
        @Volatile var applied: Int = 0
        @Volatile var verifying: Boolean = false
    }

    companion object {
        private const val CAPTURE_BATCH_BLOCKS = 192
        private const val MAX_CAPTURE_BATCH_BYTES = 8 * 1_048_576L
        private const val RESTORE_BATCH_BLOCKS = 192
        private const val RESTORE_PROGRESS_INTERVAL = 2_048
    }
}

data class PlotRestoreResult(
    val backupId: UUID,
    val restoredBlocks: Int,
    val jobStatus: PlotMutationJobStatus,
    val auditRecorded: Boolean = true
)
