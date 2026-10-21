package systems.diath.homeclaim.platform.paper.e2e

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Container
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import systems.diath.homeclaim.core.model.Area
import systems.diath.homeclaim.core.model.HomeClaimWorld
import systems.diath.homeclaim.core.model.AreaScope
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionShape
import systems.diath.homeclaim.core.model.MergeGroupId
import systems.diath.homeclaim.core.model.RegionHome
import systems.diath.homeclaim.core.mutation.MutationReason
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.CorrelationId
import systems.diath.homeclaim.platform.paper.HomeClaimPaperPlugin
import systems.diath.homeclaim.platform.paper.PlatformServices
import systems.diath.homeclaim.platform.paper.plot.PlotWorldConfig
import systems.diath.homeclaim.platform.paper.plot.PlotWorldConfigStore
import systems.diath.homeclaim.platform.paper.plot.PlotWorldChunkGenerator
import systems.diath.homeclaim.platform.paper.plot.mutation.PaperPlotBackupService
import systems.diath.homeclaim.platform.paper.plot.mutation.PaperPlotMutationService
import systems.diath.homeclaim.platform.paper.plot.mutation.PaperMutationSafetyBundleStore
import systems.diath.homeclaim.platform.paper.plot.mutation.MutationSafetyBundle
import systems.diath.homeclaim.platform.paper.plot.mutation.MutationSafetyOperation
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationFaultInjector
import systems.diath.homeclaim.platform.paper.plot.mutation.PreparedPlotWorldMutation
import systems.diath.homeclaim.platform.paper.plot.mutation.JdbcPlotBackupIndex
import systems.diath.homeclaim.platform.paper.plot.mutation.PaperPlotResetService
import systems.diath.homeclaim.platform.paper.plot.mutation.PaperPlotTransferService
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotBackupCompression
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotBackupFaultInjector
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotBackupApplyKind
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotBackupRecord
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotBackupStatus
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationJobStatus
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationJobStore
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationJobRecord
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationRecoveryAction
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationRecoveryPlanResult
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationRecoveryPlanner
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotTransferFaultInjector
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotTransferPhase
import systems.diath.homeclaim.platform.paper.plot.mutation.RegionMutationConflictCoordinator
import systems.diath.homeclaim.platform.paper.plot.mutation.RegionMutationOperation
import systems.diath.homeclaim.platform.paper.plot.mutation.ReservationAttempt
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotResetCompletionStatus
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotResetReason
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotTransferOperation
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotTransferPlanResult
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotTransferPlanner
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotTransferResult
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

/** Separate test artifact. It is not part of HomeClaim.jar and exposes no production command. */
class PaperWorldE2EHarnessPlugin : JavaPlugin() {
    private val reportFile by lazy { dataFolder.toPath().resolve("result.txt") }
    private var world: World? = null
    private lateinit var homeClaimPlugin: HomeClaimPaperPlugin
    private lateinit var services: PlatformServices
    private lateinit var backupService: PaperPlotBackupService
    private lateinit var transferService: PaperPlotTransferService
    private lateinit var resetService: PaperPlotResetService
    private lateinit var safetyBundleStore: PaperMutationSafetyBundleStore
    private lateinit var plotWorldMutation: PaperPlotMutationService
    private lateinit var testBackupService: PaperPlotBackupService
    private lateinit var jobStore: PlotMutationJobStore
    private lateinit var conflicts: RegionMutationConflictCoordinator
    private val backupFailure = AtomicReference<Pair<PlotBackupApplyKind, Int>?>(null)
    private lateinit var homeWorld: HomeClaimWorld
    private lateinit var area: Area
    private lateinit var first: Region
    private lateinit var second: Region
    private lateinit var third: Region
    private var concurrencyRegion: Region? = null
    private var concurrencyTarget: Region? = null
    private var concurrencyLease: RegionMutationConflictCoordinator.Lease? = null
    private val operationSafetyRegions = mutableListOf<Region>()

    override fun onEnable() {
        dataFolder.mkdirs()
        server.scheduler.runTask(this, Runnable {
            runCatching { begin() }.onFailure(::fail)
        })
    }

    private fun begin() {
        val homeClaim = server.pluginManager.getPlugin("HomeClaim") as? HomeClaimPaperPlugin
            ?: error("HomeClaim dependency was not loaded")
        homeClaimPlugin = homeClaim
        services = privateField(homeClaim, "services")
        backupService = privateField(homeClaim, "plotBackupService")
        transferService = privateField(homeClaim, "plotTransferService")
        resetService = privateField(homeClaim, "plotResetService")
        safetyBundleStore = privateField(homeClaim, "mutationSafetyBundleStore")
        plotWorldMutation = privateField(homeClaim, "plotMutationService")
        jobStore = privateField(homeClaim, "plotMutationJobStore")
        conflicts = privateField(homeClaim, "plotMutationConflicts")
        val testWorld = server.worlds.firstOrNull() ?: error("Paper did not load its isolated test world")
        world = testWorld

        val areas = requireNotNull(services.worldAreaService) { "HomeClaim WorldAreaService is unavailable" }
        testBackupService = PaperPlotBackupService(
            homeClaim,
            JdbcPlotBackupIndex(requireNotNull(services.dataSource)),
            areas,
            jobStore,
            services.auditService,
            conflicts,
            PlotBackupCompression.ZSTD,
            PlotBackupFaultInjector { kind, applied ->
                val pending = backupFailure.get()
                if (pending != null && pending.first == kind && applied >= pending.second && backupFailure.compareAndSet(pending, null)) {
                    throw IllegalStateException("test-only injected $kind apply failure after $applied blocks")
                }
            }
        )
        val registeredWorld = areas.getWorldByPlatformId(testWorld.uid)
            ?: areas.getWorldByName(testWorld.name)?.let { areas.bindPlatformWorld(it.id, testWorld.uid, testWorld.name) }
            ?: areas.createWorld(testWorld.name, testWorld.uid)
        homeWorld = registeredWorld

        when (System.getProperty("homeclaim.worlde2e.phase", "full")) {
            "merge-restart-verify" -> {
                verifyMergeRecoveryRestart(testWorld, areas)
                return
            }
            "merge-restart-prepare" -> {
                createFixtures(homeClaim, areas, testWorld)
                prepareMergeRecoveryRestart(testWorld)
                return
            }
            "restart-verify" -> {
                verifyRestartRecovery(testWorld, areas)
                return
            }
            "restart-prepare" -> {
                createFixtures(homeClaim, areas, testWorld)
                prepareRestartRecovery(testWorld)
                return
            }
        }

        createFixtures(homeClaim, areas, testWorld)

        seed(first, "copy-source")
        seed(second, "copy-target")
        val beforeA = capture(testWorld, first.bounds.minX)
        runTransfer(PlotTransferOperation.COPY, first, second) { result ->
            check(result is PlotTransferResult.Completed) { "COPY did not complete: $result" }
            val completed = result as PlotTransferResult.Completed
            check(jobStore.findById(completed.jobId)?.status == PlotMutationJobStatus.COMPLETED) { "COPY job is not terminal COMPLETED" }
            check(conflicts.checkAvailable(listOf(first.id, second.id)) == null) { "COPY did not release its reservation" }
            check(capture(testWorld, first.bounds.minX) == beforeA) { "COPY changed its source" }
            check(capture(testWorld, second.bounds.minX) == beforeA) { "COPY target differs from source snapshot" }
            verifyReadyBackup(completed.sourceBackupId) { sourceBackup ->
                check(sourceBackup.compression == PlotBackupCompression.ZSTD)
                verifyReadyBackup(completed.targetBackupId) { targetBackup ->
                    check(targetBackup.compression == PlotBackupCompression.ZSTD)
                    check(backupService.listBackups(first.id).get(20, java.util.concurrent.TimeUnit.SECONDS).any { it.id == sourceBackup.id })
                    runMove(testWorld)
                }
            }
        }
    }

    private fun createFixtures(homeClaim: HomeClaimPaperPlugin, areas: systems.diath.homeclaim.core.service.WorldAreaService, testWorld: World) {
        val areaBounds = Bounds(-32, 64, testWorld.minHeight, testWorld.maxHeight - 1, -16, 24)
        area = areas.createArea(Area(systems.diath.homeclaim.core.model.AreaId.newId(), homeWorld.id,
            "paper-world-e2e", AreaScope.Finite(areaBounds), priority = 100_000))
        val bounds = { x: Int -> Bounds(x, x, testWorld.minHeight, testWorld.maxHeight - 1, 0, 0) }
        first = createRegion(bounds(0))
        second = createRegion(bounds(2))
        third = createRegion(bounds(4))

        PlotWorldConfigStore(homeClaim).persistConfig(
            PlotWorldConfig(
                worldName = testWorld.name,
                plotHeight = testWorld.maxHeight,
                minGenHeight = testWorld.minHeight,
                resetBatchColumnsPerTick = 8
            )
        )
    }

    private fun prepareRestartRecovery(testWorld: World) {
        seed(first, "restart-source")
        seed(second, "restart-target")
        val targetBefore = capture(testWorld, second.bounds.minX)
        val service = transferWithPhaseFault(null)
        backupFailure.set(PlotBackupApplyKind.TRANSFER to 24)
        await(service.execute(readyPlan(PlotTransferOperation.COPY, first, second), testWorld,
            ActionActor.System("paper-world-e2e-restart"), CorrelationId.newId())) { result ->
            service.shutdown()
            check(result is PlotTransferResult.Failed && result.recoveryRequired) { "Restart fixture did not reach RECOVERY_REQUIRED: $result" }
            val jobId = (result as PlotTransferResult.Failed).jobId
            check(jobStore.findById(jobId)?.status == PlotMutationJobStatus.RECOVERY_REQUIRED)
            val restartState = buildList {
                add(area.id.value.toString())
                add(first.id.value.toString())
                add(second.id.value.toString())
                add(jobId.toString())
                addAll(encodeSnapshot(targetBefore))
            }.joinToString("\n") + "\n"
            Files.writeString(dataFolder.toPath().resolve("restart-state.txt"), restartState)
            testBackupService.shutdown()
            writeResult("PREPARED $jobId")
            Bukkit.shutdown()
        }
    }

    private fun verifyRestartRecovery(testWorld: World, areas: systems.diath.homeclaim.core.service.WorldAreaService) {
        val state = Files.readAllLines(dataFolder.toPath().resolve("restart-state.txt"))
        require(state.size >= 5) { "Missing persisted restart recovery fixture" }
        area = requireNotNull(areas.getArea(systems.diath.homeclaim.core.model.AreaId(UUID.fromString(state[0]))))
        first = requireNotNull(services.regionService.getRegionById(RegionId(UUID.fromString(state[1]))))
        second = requireNotNull(services.regionService.getRegionById(RegionId(UUID.fromString(state[2]))))
        val jobId = UUID.fromString(state[3])
        val job = requireNotNull(jobStore.findById(jobId)) { "Interrupted mutation job was not loaded after server restart" }
        check(job.status == PlotMutationJobStatus.RECOVERY_REQUIRED) { "Restart classification was ${job.status}, expected RECOVERY_REQUIRED" }
        check(conflicts.checkAvailable(listOf(first.id, second.id)) != null) { "Restart did not restore the persisted region block" }
        val expectedTarget = decodeSnapshot(state.drop(4))
        recover(jobId, PlotMutationRecoveryAction.RESTORE_TARGET) {
            check(capture(testWorld, second.bounds.minX) == expectedTarget) { "Restart recovery did not restore the persisted target safety snapshot" }
            check(jobStore.findById(jobId)?.status == PlotMutationJobStatus.RECOVERED)
            finish("PASS restart recovery persisted-job classified blocked and restored after real server restart")
        }
    }

    private fun prepareMergeRecoveryRestart(testWorld: World) {
        val left = createRegion(Bounds(8, 10, testWorld.minHeight, testWorld.maxHeight - 1, 10, 12))
            .copy(home = RegionHome(9.5, testWorld.minHeight + 12.0, 11.5, 45f, 0f))
        val right = createRegion(Bounds(14, 16, testWorld.minHeight, testWorld.maxHeight - 1, 10, 12))
            .copy(home = RegionHome(15.5, testWorld.minHeight + 12.0, 11.5, 135f, 0f))
        services.regionService.updateRegion(left)
        services.regionService.updateRegion(right)
        operationSafetyRegions += left
        operationSafetyRegions += right
        val groupId = MergeGroupId(UUID.randomUUID())
        val plan = plotWorldMutation.prepareMergeMutation(listOf(left, right).map { it.copy(mergeGroupId = groupId) })
        seedMutationScope(testWorld, plan, Material.CHEST)
        val jobId = createSafetyJob("MERGE", listOf(left, right))
        val bundleId = UUID.randomUUID()
        await(safetyBundleStore.create(jobId, MutationSafetyOperation.MERGE, homeWorld.id, area.id,
            listOf(left, right), plan, testWorld, ActionActor.System("paper-world-merge-restart"), CorrelationId.newId(), bundleId)) { bundle ->
            recordSafetyReady(jobId, bundle)
            plotWorldMutation.applyPreparedMutation(plan, "merge").whenComplete { _, writeFailure -> server.scheduler.runTask(this, checkedRunnable {
                if (writeFailure != null) throw IllegalStateException("Merge restart fixture world write failed", writeFailure)
                val persistedGroup = services.regionService.mergeRegions(listOf(left.id, right.id))
                listOf(left, right).forEach { before ->
                    services.regionService.updateRegion(requireNotNull(services.regionService.getRegionById(before.id)).copy(
                        mergeGroupId = persistedGroup, home = if (before.id == left.id) left.home else null
                    ))
                }
                check(jobStore.findById(jobId)?.details?.contains("safetyBundleId=$bundleId") == true)
                jobStore.finish(jobId, PlotMutationJobStatus.RECOVERY_REQUIRED, plan.batches.size, plan.batches.size,
                    "simulated terminalization failure after merge world and domain application")
                check(jobStore.findById(jobId)?.status == PlotMutationJobStatus.RECOVERY_REQUIRED)
                val actor = ActionActor.System("paper-world-merge-restart-recovery")
                val recoveryCorrelation = CorrelationId.newId()
                val lease = when (val attempt = conflicts.reserveRecovery(jobId, listOf(left.id, right.id), actor, recoveryCorrelation)) {
                    is ReservationAttempt.Acquired -> attempt.lease
                    is ReservationAttempt.Conflict -> error("Could not reserve merge restart fixture recovery: ${attempt.conflict}")
                }
                check(jobStore.beginRecovery(jobId, "restart fixture recovery started"))
                check(jobStore.updateRecoveryDetails(jobId, recoveryCheckpoint(jobId, bundleId, bundle.manifestSha256, "WORLD_RESTORE_RUNNING")))
                safetyBundleStore.validate(jobId, bundleId, MutationSafetyOperation.MERGE, area.id, homeWorld.id,
                    testWorld.uid, listOf(left.id, right.id), bundle.manifestSha256).whenComplete { validated, validationFailure ->
                    server.scheduler.runTask(this, checkedRunnable {
                        if (validationFailure != null) throw IllegalStateException("Merge restart fixture bundle validation failed", validationFailure)
                        safetyBundleStore.restoreWorld(validated, testWorld).whenComplete { _, restoreFailure -> server.scheduler.runTask(this, checkedRunnable {
                            if (restoreFailure != null) throw IllegalStateException("Merge restart fixture pre-image restore failed", restoreFailure)
                            check(jobStore.updateRecoveryDetails(jobId,
                                recoveryCheckpoint(jobId, bundleId, bundle.manifestSha256, "WORLD_RESTORED_DOMAIN_RESTORE_PENDING")))
                            check(listOf(left, right).all { services.regionService.getRegionById(it.id)?.mergeGroupId == persistedGroup }) {
                                "Restart fixture did not retain the post-merge domain layer before restart"
                            }
                            val state = listOf(area.id.value.toString(), left.id.value.toString(), right.id.value.toString(),
                                jobId.toString(), bundleId.toString(), bundle.manifestSha256,
                                first.id.value.toString(), second.id.value.toString(), third.id.value.toString())
                            Files.writeString(dataFolder.toPath().resolve("merge-restart-state.txt"), state.joinToString("\n") + "\n")
                            testBackupService.shutdown()
                            safetyBundleStore.shutdown()
                            writeResult("PREPARED MERGE_RECOVERY_IN_PROGRESS phase=WORLD_RESTORED_DOMAIN_RESTORE_PENDING")
                            Bukkit.shutdown()
                        }) }
                    })
                }
            }) }
        }
    }

    private fun verifyMergeRecoveryRestart(
        testWorld: World,
        areas: systems.diath.homeclaim.core.service.WorldAreaService
    ) {
        val state = Files.readAllLines(dataFolder.toPath().resolve("merge-restart-state.txt"))
        require(state.size == 9) { "Missing merge recovery restart fixture" }
        area = requireNotNull(areas.getArea(systems.diath.homeclaim.core.model.AreaId(UUID.fromString(state[0]))))
        first = requireNotNull(services.regionService.getRegionById(RegionId(UUID.fromString(state[6]))))
        second = requireNotNull(services.regionService.getRegionById(RegionId(UUID.fromString(state[7]))))
        third = requireNotNull(services.regionService.getRegionById(RegionId(UUID.fromString(state[8]))))
        val leftId = RegionId(UUID.fromString(state[1]))
        val rightId = RegionId(UUID.fromString(state[2]))
        val left = requireNotNull(services.regionService.getRegionById(leftId))
        val right = requireNotNull(services.regionService.getRegionById(rightId))
        operationSafetyRegions += left
        operationSafetyRegions += right
        val jobId = UUID.fromString(state[3])
        val bundleId = UUID.fromString(state[4])
        val manifestHash = state[5]
        val job = requireNotNull(jobStore.findById(jobId)) { "Interrupted merge recovery job was not loaded after restart" }
        check(job.status == PlotMutationJobStatus.RECOVERY_REQUIRED) { "Merge recovery restart classification was ${job.status}" }
        check(job.details.orEmpty().contains("safetyBundleId=$bundleId")) { "Safety bundle linkage was lost at restart" }
        check(job.details.orEmpty().contains("WORLD_RESTORED_DOMAIN_RESTORE_PENDING")) { "Recovery phase checkpoint was lost at restart" }
        check(conflicts.checkAvailable(listOf(leftId, rightId)) != null) { "Restart did not reconstruct the merge recovery block" }
        check(left.mergeGroupId != null && right.mergeGroupId == left.mergeGroupId) { "Domain layer did not remain pending across restart" }
        val actor = ActionActor.System("paper-world-merge-restart-recovery")
        val correlation = CorrelationId.newId()
        val lease = when (val attempt = conflicts.reserveRecovery(jobId, listOf(leftId, rightId), actor, correlation)) {
            is ReservationAttempt.Acquired -> attempt.lease
            is ReservationAttempt.Conflict -> error("Could not reserve merge recovery after restart: ${attempt.conflict}")
        }
        check(jobStore.beginRecovery(jobId, "explicit post-restart merge recovery"))
        safetyBundleStore.validate(jobId, bundleId, MutationSafetyOperation.MERGE, area.id, homeWorld.id,
            testWorld.uid, listOf(leftId, rightId), manifestHash).whenComplete { bundle, validationFailure ->
            server.scheduler.runTask(this, checkedRunnable {
                if (validationFailure != null) throw IllegalStateException("Persisted merge safety bundle failed validation", validationFailure)
                check(jobStore.updateRecoveryDetails(jobId, recoveryCheckpoint(jobId, bundleId, manifestHash, "WORLD_RESTORE_RUNNING")))
                safetyBundleStore.restoreWorld(bundle, testWorld).whenComplete { restored, restoreFailure -> server.scheduler.runTask(this, checkedRunnable {
                    if (restoreFailure != null) throw IllegalStateException("Merge world restore after restart failed", restoreFailure)
                    check(restored == bundle.worldSnapshot.blockCount)
                    restoreDomainSnapshot(bundle.domainSnapshot, area.id)
                    val recoveredLeft = requireNotNull(services.regionService.getRegionById(leftId))
                    val recoveredRight = requireNotNull(services.regionService.getRegionById(rightId))
                    check(recoveredLeft.mergeGroupId == null && recoveredRight.mergeGroupId == null)
                    check(systems.diath.homeclaim.platform.paper.plot.mutation.MergeDomainSnapshotV1.capture(
                        listOf(recoveredLeft, recoveredRight)
                    ) == bundle.domainSnapshot) { "MergeGroup or home pre-image differs after process restart recovery" }
                    check(jobStore.finishRecovery(jobId, PlotMutationJobStatus.RECOVERED, "phase=WORLD_AND_DOMAIN_VERIFIED"))
                    lease.close()
                    check(conflicts.checkAvailable(listOf(leftId, rightId)) == null)
                    finish("PASS merge world+domain recovery resumed explicitly after real Paper process restart; regions unblocked")
                }) }
            })
        }
    }

    private fun recoveryCheckpoint(jobId: UUID, bundleId: UUID, manifestSha256: String, phase: String): String {
        val original = jobStore.findById(jobId)?.details.orEmpty().lineSequence()
            .filterNot { it.startsWith("phase=") || it.startsWith("recoveryPhase=") }
            .toList()
        return (original + "safetyBundleId=$bundleId" + "safetyBundleManifestSha256=$manifestSha256" + "phase=WORLD_MUTATION" +
            "recoveryPhase=$phase").joinToString("\n").take(8192)
    }

    private fun encodeSnapshot(snapshot: List<BlockImage>): List<String> = snapshot.map { image ->
        val items = image.items?.joinToString(",") { it ?: "~" } ?: "~inventory"
        Base64.getEncoder().encodeToString(image.blockData.toByteArray(Charsets.UTF_8)) + "\t" +
            Base64.getEncoder().encodeToString(items.toByteArray(Charsets.UTF_8))
    }

    private fun decodeSnapshot(lines: List<String>): List<BlockImage> = lines.map { line ->
        val (blockData, encodedItems) = line.split('\t', limit = 2)
        val items = String(Base64.getDecoder().decode(encodedItems), Charsets.UTF_8)
        BlockImage(
            String(Base64.getDecoder().decode(blockData), Charsets.UTF_8),
            if (items == "~inventory") null else items.split(',').map { it.takeUnless(String::isEmpty)?.takeUnless { value -> value == "~" } }
        )
    }

    private fun runMove(testWorld: World) {
        seed(first, "move-source")
        seed(second, "move-target")
        val source = capture(testWorld, first.bounds.minX)
        runTransfer(PlotTransferOperation.MOVE, first, second) { result ->
            check(result is PlotTransferResult.Completed) { "MOVE did not complete: $result" }
            val completed = result as PlotTransferResult.Completed
            check(jobStore.findById(completed.jobId)?.status == PlotMutationJobStatus.COMPLETED) { "MOVE job is not terminal COMPLETED" }
            check(conflicts.checkAvailable(listOf(first.id, second.id)) == null) { "MOVE did not release its reservation" }
            verifyReadyBackup(completed.sourceBackupId) { verifyReadyBackup(completed.targetBackupId) {
            check(capture(testWorld, second.bounds.minX) == source) { "MOVE target was not written from the source snapshot" }
            check(capture(testWorld, first.bounds.minX).all { it.blockData == Material.AIR.createBlockData().asString && it.items == null }) {
                "MOVE did not clear the source after applying the target"
            }
            runSwap(testWorld)
            } }
        }
    }

    private fun runSwap(testWorld: World) {
        seed(first, "swap-a")
        seed(second, "swap-b")
        val originalA = capture(testWorld, first.bounds.minX)
        val originalB = capture(testWorld, second.bounds.minX)
        runTransfer(PlotTransferOperation.SWAP, first, second) { result ->
            check(result is PlotTransferResult.Completed) { "SWAP did not complete: $result" }
            val completed = result as PlotTransferResult.Completed
            check(jobStore.findById(completed.jobId)?.status == PlotMutationJobStatus.COMPLETED) { "SWAP job is not terminal COMPLETED" }
            check(conflicts.checkAvailable(listOf(first.id, second.id)) == null) { "SWAP did not release its reservation" }
            verifyReadyBackup(completed.sourceBackupId) { verifyReadyBackup(completed.targetBackupId) {
            check(capture(testWorld, first.bounds.minX) == originalB) { "SWAP first side does not match B's original snapshot" }
            check(capture(testWorld, second.bounds.minX) == originalA) { "SWAP second side does not match A's original snapshot" }
            runReset(testWorld)
            } }
        }
    }

    private fun runReset(testWorld: World) {
        seed(first, "reset-before")
        val finished = CompletableFuture<systems.diath.homeclaim.platform.paper.plot.mutation.PlotResetCompletion>()
        check(resetService.queueReset(first, PlotResetReason.MANUAL, CorrelationId.newId(), ActionActor.System("paper-world-e2e")) {
            finished.complete(it)
        }) { "Reset service rejected the test region" }
        await(finished) { completion ->
            check(completion.status == PlotResetCompletionStatus.COMPLETED) { "RESET failed: $completion" }
            check(conflicts.checkAvailable(listOf(first.id)) == null) { "RESET did not release its reservation" }
            val generator = PlotWorldChunkGenerator(
                PlotWorldConfig(worldName = testWorld.name, plotHeight = testWorld.maxHeight,
                    minGenHeight = testWorld.minHeight, resetBatchColumnsPerTick = 8)
            )
            for (y in testWorld.minHeight until testWorld.maxHeight) {
                check(testWorld.getBlockAt(first.bounds.minX, y, 0).type == generator.getBlockAt(first.bounds.minX, y, 0)) {
                    "RESET result does not match plot generator at y=$y"
                }
            }
            val backups = backupService.listBackups(first.id).get(20, java.util.concurrent.TimeUnit.SECONDS)
            val resetBackup = backups.firstOrNull { it.cause.startsWith("reset:") && it.status == PlotBackupStatus.READY }
                ?: error("Reset did not retain its READY pre-reset snapshot")
            check(resetBackup.compression == PlotBackupCompression.ZSTD && !resetBackup.checksumSha256.isNullOrBlank()) {
                "Reset snapshot is not a checksummed ZSTD backup"
            }
            check(backupService.validateReadyBackup(resetBackup.id).get(20, java.util.concurrent.TimeUnit.SECONDS).status == PlotBackupStatus.READY)
            runRestore(testWorld)
        }
    }

    private fun runRestore(testWorld: World) {
        seed(first, "restore-source")
        val expected = capture(testWorld, first.bounds.minX)
        val backup = backupService.createSnapshot(first, testWorld, ActionActor.System("paper-world-e2e"),
            CorrelationId.newId(), "e2e:restore-source")
        await(backup) { sourceBackup ->
            check(sourceBackup.status == PlotBackupStatus.READY)
            check(sourceBackup.compression == PlotBackupCompression.ZSTD)
            seed(first, "restore-different")
            val restore = backupService.restore(sourceBackup.id, first, testWorld,
                ActionActor.System("paper-world-e2e"), CorrelationId.newId())
            await(restore) { result ->
                check(result.jobStatus == PlotMutationJobStatus.COMPLETED) { "RESTORE job not completed: $result" }
                check(conflicts.checkAvailable(listOf(first.id)) == null) { "RESTORE did not release its reservation" }
                check(capture(testWorld, first.bounds.minX) == expected) { "RESTORE did not reproduce the saved blocks and container slots" }
                val backups = backupService.listBackups(first.id).get(20, java.util.concurrent.TimeUnit.SECONDS)
                check(backups.any { it.id == sourceBackup.id }) { "Original restore backup was removed" }
                check(backups.any { it.cause == "restore_safety" && it.status == PlotBackupStatus.READY }) {
                    "Restore did not retain its READY safety backup"
                }
                runUnsupportedTileState(testWorld)
            }
        }
    }

    private fun runUnsupportedTileState(testWorld: World) {
        val unsupported = createRegion(Bounds(6, 6, testWorld.minHeight, testWorld.maxHeight - 1, 0, 0))
        val signY = testWorld.minHeight + 20
        testWorld.getBlockAt(6, signY, 0).setType(Material.OAK_SIGN, false)
        check(testWorld.getBlockAt(6, signY, 0).type == Material.OAK_SIGN)
        val backup = backupService.createSnapshot(unsupported, testWorld, ActionActor.System("paper-world-e2e"),
            CorrelationId.newId(), "e2e:unsupported-sign")
        awaitFailure(backup) {
            check(testWorld.getBlockAt(6, signY, 0).type == Material.OAK_SIGN) {
                "Unsupported TileState was changed despite backup failure"
            }
            runCopyRecovery(testWorld)
        }
    }

    private fun runCopyRecovery(testWorld: World) {
        seed(first, "copy-failure-source")
        seed(second, "copy-failure-target")
        val source = capture(testWorld, first.bounds.minX)
        val target = capture(testWorld, second.bounds.minX)
        backupFailure.set(PlotBackupApplyKind.TRANSFER to 24)
        val service = transferWithPhaseFault(null)
        val plan = readyPlan(PlotTransferOperation.COPY, first, second)
        await(service.execute(plan, testWorld, ActionActor.System("paper-world-e2e"), CorrelationId.newId())) { result ->
            service.shutdown()
            check(result is PlotTransferResult.Failed && result.recoveryRequired) { "Injected partial COPY did not require recovery: $result" }
            val jobId = (result as PlotTransferResult.Failed).jobId
            val job = requireNotNull(jobStore.findById(jobId))
            check(job.status == PlotMutationJobStatus.RECOVERY_REQUIRED)
            check(capture(testWorld, first.bounds.minX) == source) { "Partial COPY modified its source" }
            check(conflicts.checkAvailable(listOf(first.id, second.id)) != null) { "COPY recovery block was not active" }
            recover(jobId, PlotMutationRecoveryAction.RESTORE_TARGET) {
                check(capture(testWorld, first.bounds.minX) == source)
                check(capture(testWorld, second.bounds.minX) == target) { "COPY target safety snapshot did not restore Y" }
                runMoveRecovery(testWorld)
            }
        }
    }

    private fun runMoveRecovery(testWorld: World) {
        seed(first, "move-recovery-source")
        seed(second, "move-recovery-target")
        val source = capture(testWorld, first.bounds.minX)
        val target = capture(testWorld, second.bounds.minX)
        val service = transferWithPhaseFault(PlotTransferPhase.SOURCE_CLEANUP)
        val plan = readyPlan(PlotTransferOperation.MOVE, first, second)
        await(service.execute(plan, testWorld, ActionActor.System("paper-world-e2e"), CorrelationId.newId())) { result ->
            service.shutdown()
            check(result is PlotTransferResult.Failed && result.recoveryRequired) { "Injected MOVE cleanup failure did not require recovery: $result" }
            val jobId = (result as PlotTransferResult.Failed).jobId
            check(jobStore.findById(jobId)?.status == PlotMutationJobStatus.RECOVERY_REQUIRED)
            check(capture(testWorld, second.bounds.minX) == source) { "MOVE target did not remain applied before failed cleanup" }
            check(conflicts.checkAvailable(listOf(first.id, second.id)) != null)
            recover(jobId, PlotMutationRecoveryAction.RESTORE_BOTH) {
                check(capture(testWorld, first.bounds.minX) == source)
                check(capture(testWorld, second.bounds.minX) == target)
                runSwapRecovery(testWorld)
            }
        }
    }

    private fun runSwapRecovery(testWorld: World) {
        seed(first, "swap-recovery-a")
        seed(second, "swap-recovery-b")
        val originalA = capture(testWorld, first.bounds.minX)
        val originalB = capture(testWorld, second.bounds.minX)
        val service = transferWithPhaseFault(PlotTransferPhase.SECONDARY_APPLY)
        val plan = readyPlan(PlotTransferOperation.SWAP, first, second)
        await(service.execute(plan, testWorld, ActionActor.System("paper-world-e2e"), CorrelationId.newId())) { result ->
            service.shutdown()
            check(result is PlotTransferResult.Failed && result.recoveryRequired) { "Injected partial SWAP did not require recovery: $result" }
            val jobId = (result as PlotTransferResult.Failed).jobId
            check(jobStore.findById(jobId)?.status == PlotMutationJobStatus.RECOVERY_REQUIRED)
            check(capture(testWorld, second.bounds.minX) == originalA) { "SWAP first write was not visible before failure" }
            recover(jobId, PlotMutationRecoveryAction.RESTORE_BOTH) {
                check(capture(testWorld, first.bounds.minX) == originalA)
                check(capture(testWorld, second.bounds.minX) == originalB)
                runRestoreRecovery(testWorld)
            }
        }
    }

    private fun runRestoreRecovery(testWorld: World) {
        seed(first, "restore-recovery-x")
        val source = testBackupService.createSnapshot(first, testWorld, ActionActor.System("paper-world-e2e"),
            CorrelationId.newId(), "e2e:restore-recovery-source")
        await(source) { sourceBackup ->
            seed(first, "restore-recovery-y")
            val originalTarget = capture(testWorld, first.bounds.minX)
            backupFailure.set(PlotBackupApplyKind.RESTORE to 24)
            val failedRestore = testBackupService.restore(sourceBackup.id, first, testWorld,
                ActionActor.System("paper-world-e2e"), CorrelationId.newId())
            awaitFailure(failedRestore) {
                val job = jobStore.findRecoveryRequiredForRegions(listOf(first.id)).firstOrNull { it.operation == "RESTORE" }
                    ?: error("Partial restore did not persist a recovery job")
                val details = job.details.orEmpty()
                check("restoreSafetyBackupId=" in details) { "Restore safety BackupId was not linked to the recovery job" }
                check(conflicts.checkAvailable(listOf(first.id)) != null) { "Partial RESTORE did not block the region" }
                recover(job.id, PlotMutationRecoveryAction.RESTORE_TARGET) {
                    check(capture(testWorld, first.bounds.minX) == originalTarget) { "Restore recovery did not return to the pre-restore Y state" }
                    check(jobStore.findById(job.id)?.status == PlotMutationJobStatus.RECOVERED)
                    runSnapshotConcurrency(testWorld)
                }
            }
        }
    }

    /** Demonstrates the documented tick-wise snapshot boundary against an actual Paper world. */
    private fun runSnapshotConcurrency(testWorld: World) {
        val region = createRegion(Bounds(8, 15, testWorld.minHeight, testWorld.maxHeight - 1, -8, 7))
        concurrencyRegion = region
        val target = createRegion(Bounds(16, 23, testWorld.minHeight, testWorld.maxHeight - 1, -8, 7))
        concurrencyTarget = target
        val early = Triple(region.bounds.minX, testWorld.minHeight + 20, region.bounds.minZ)
        val late = Triple(region.bounds.maxX, testWorld.maxHeight - 1, region.bounds.maxZ)
        testWorld.getBlockAt(early.first, early.second, early.third).setType(Material.STONE, false)
        testWorld.getBlockAt(late.first, late.second, late.third).setType(Material.STONE, false)

        val actor = ActionActor.System("paper-world-e2e-concurrency")
        val correlation = CorrelationId.newId()
        val reservation = conflicts.reserve(RegionMutationOperation.RESET, listOf(region.id), actor = actor, correlationId = correlation)
        concurrencyLease = (reservation as? ReservationAttempt.Acquired)?.lease
            ?: error("Could not reserve the snapshot region for the capture concurrency check: $reservation")

        val backupId = UUID.randomUUID()
        val startedAt = System.nanoTime()
        var tickCount = 0
        var lastTickAt = startedAt
        var maxTickIntervalNanos = 0L
        var externallyChanged = false
        val stageFile = homeClaimPlugin.dataFolder.toPath()
            .resolve("backups/.staging/$backupId.payload.part")
        lateinit var monitor: BukkitTask
        monitor = server.scheduler.runTaskTimer(this, Runnable {
            val now = System.nanoTime()
            tickCount++
            maxTickIntervalNanos = maxOf(maxTickIntervalNanos, now - lastTickAt)
            lastTickAt = now
            if (!externallyChanged && Files.isRegularFile(stageFile) && Files.size(stageFile) > 0) {
                // The first column's early block was already captured; the far corner is still pending.
                testWorld.getBlockAt(early.first, early.second, early.third).setType(Material.DIAMOND_BLOCK, false)
                testWorld.getBlockAt(late.first, late.second, late.third).setType(Material.DIAMOND_BLOCK, false)
                val competing = conflicts.reserve(RegionMutationOperation.COPY, listOf(region.id), actor = actor)
                check(competing is ReservationAttempt.Conflict) {
                    if (competing is ReservationAttempt.Acquired) competing.lease.close()
                    "A second HomeClaim mutation was not blocked during capture"
                }
                externallyChanged = true
            }
            if (tickCount > 20 * 180) {
                monitor.cancel()
                fail(IllegalStateException("Snapshot concurrency check timed out after 180 seconds"))
            }
        }, 1L, 1L)

        val accepted = backupService.createBeforeReset(
            region, testWorld, testWorld.minHeight, testWorld.maxHeight - 1, actor, correlation,
            "e2e:snapshot-concurrency", backupId
        ) { result ->
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
            val record = result.getOrElse { fail(it); return@createBeforeReset }
            if (!externallyChanged) {
                fail(IllegalStateException("Capture completed before the test plugin could interleave its world writes"))
                return@createBeforeReset
            }
            concurrencyLease?.close()
            concurrencyLease = null
            backupService.validateReadyBackup(record.id).whenComplete { validated, validationFailure ->
                if (validationFailure != null) {
                    server.scheduler.runTask(this, Runnable { fail(validationFailure) })
                    return@whenComplete
                }
                val rawBytes = record.uncompressedPayloadBytes
                    ?: run { monitor.cancel(); fail(IllegalStateException("Validated snapshot is missing uncompressed size")); return@whenComplete }
                val applyStartedAt = System.nanoTime()
                testBackupService.applyBackupToTarget(
                    validated.id, region, target, testWorld, homeWorld.id, CorrelationId.newId()
                ).whenComplete { applied, applyFailure ->
                    server.scheduler.runTask(this, Runnable {
                        monitor.cancel()
                        if (applyFailure != null) {
                            fail(applyFailure)
                            return@Runnable
                        }
                        val dx = target.bounds.minX - region.bounds.minX
                        val dz = target.bounds.minZ - region.bounds.minZ
                        check(applied == record.blockCount.toInt()) { "Snapshot replay applied $applied / ${record.blockCount} blocks" }
                        check(testWorld.getBlockAt(early.first + dx, early.second, early.third + dz).type == Material.STONE) {
                            "The already-captured block did not retain its pre-change state"
                        }
                        check(testWorld.getBlockAt(late.first + dx, late.second, late.third + dz).type == Material.DIAMOND_BLOCK) {
                            "The not-yet-captured block did not record the later external plugin write"
                        }
                        check(record.compression == PlotBackupCompression.ZSTD && !record.checksumSha256.isNullOrBlank())
                        val storedBytes = record.payloadBytes
                        val ratio = if (rawBytes == 0L) 0.0 else storedBytes.toDouble() / rawBytes
                        val applyMillis = (System.nanoTime() - applyStartedAt) / 1_000_000
                        val report =
                            "PASS copy move swap reset restore recoveries; snapshotConcurrency=non-atomic-interleaved " +
                                "blocks=${record.blockCount} ticks=$tickCount captureMs=$elapsedMillis applyMs=$applyMillis " +
                                "maxTickIntervalMs=${"%.2f".format(java.util.Locale.ROOT, maxTickIntervalNanos / 1_000_000.0)} " +
                                "rawBytes=$rawBytes storedBytes=$storedBytes storedRatio=${"%.4f".format(java.util.Locale.ROOT, ratio)} " +
                                "sameRegionMutation=blocked codec=ZSTD"
                        runMergeUnlinkSafetyE2E(testWorld, report)
                    })
                }
            }
        }
        if (!accepted) {
            monitor.cancel()
            fail(IllegalStateException("Could not start the snapshot concurrency capture"))
        }
    }

    private fun transferWithPhaseFault(failAt: PlotTransferPhase?): PaperPlotTransferService = PaperPlotTransferService(
        homeClaimPlugin,
        testBackupService,
        conflicts,
        jobStore,
        services.auditService,
        PlotTransferFaultInjector { phase ->
            if (phase == failAt) throw IllegalStateException("test-only injected transfer phase failure: $phase")
        }
    )

    private fun runMergeUnlinkSafetyE2E(testWorld: World, previousReport: String) {
        val left = createRegion(Bounds(8, 10, testWorld.minHeight, testWorld.maxHeight - 1, 2, 4))
            .copy(home = RegionHome(9.5, testWorld.minHeight + 12.0, 3.5, 90f, 0f))
        val right = createRegion(Bounds(12, 14, testWorld.minHeight, testWorld.maxHeight - 1, 2, 4))
            .copy(home = RegionHome(13.5, testWorld.minHeight + 12.0, 3.5, 180f, 0f))
        operationSafetyRegions += left
        operationSafetyRegions += right
        services.regionService.updateRegion(left)
        services.regionService.updateRegion(right)

        val prospectiveGroup = MergeGroupId(UUID.randomUUID())
        val prospective = listOf(left, right).map { it.copy(mergeGroupId = prospectiveGroup, home = if (it.id == left.id) left.home else null) }
        val mergePlan = plotWorldMutation.prepareMergeMutation(prospective)
        seedMutationScope(testWorld, mergePlan, Material.CHEST)
        val mergeBefore = captureMutationScope(testWorld, mergePlan)
        val mergeJob = createSafetyJob("MERGE", listOf(left, right))
        val mergeBundleId = UUID.randomUUID()
        val mergeCaptureStarted = System.nanoTime()
        await(safetyBundleStore.create(mergeJob, MutationSafetyOperation.MERGE, homeWorld.id, area.id,
            listOf(left, right), mergePlan, testWorld, ActionActor.System("paper-world-e2e"), CorrelationId.newId(), mergeBundleId)) { mergeBundle ->
            recordSafetyReady(mergeJob, mergeBundle)
            val mergeCaptureMillis = (System.nanoTime() - mergeCaptureStarted) / 1_000_000
            val mergeApplyStarted = System.nanoTime()
            plotWorldMutation.applyPreparedMutation(mergePlan, "merge").whenComplete { _, failure -> server.scheduler.runTask(this, checkedRunnable {
                if (failure != null) throw IllegalStateException("Merge world E2E failed", failure)
                val mergeApplyMillis = (System.nanoTime() - mergeApplyStarted) / 1_000_000
                check(captureMutationScope(testWorld, mergePlan) != mergeBefore) { "Merge writer did not change its planned world scope" }
                val group = services.regionService.mergeRegions(listOf(left.id, right.id))
                listOf(left, right).forEach { before ->
                    val home = if (before.id == left.id) left.home else null
                    services.regionService.updateRegion(requireNotNull(services.regionService.getRegionById(before.id)).copy(mergeGroupId = group, home = home))
                }
                check(listOf(left, right).all { services.regionService.getRegionById(it.id)?.mergeGroupId == group })
                jobStore.finish(mergeJob, PlotMutationJobStatus.COMPLETED, mergePlan.batches.size, mergePlan.batches.size)
                check(jobStore.findById(mergeJob)?.status == PlotMutationJobStatus.COMPLETED)
                check(mergeBundle.worldSnapshot.blockCount == mergePlan.batches.sumOf { it.operations.size.toLong() })
                check(mergeBundle.worldSnapshot.compression == "zstd")

                val merged = listOf(left, right).map { requireNotNull(services.regionService.getRegionById(it.id)) }
                val unlinkPlan = plotWorldMutation.prepareUnlinkMutation(merged, createRoads = true, MutationReason.UNCLAIM)
                val unlinkBefore = captureMutationScope(testWorld, unlinkPlan)
                val unlinkJob = createSafetyJob("UNLINK", merged)
                val unlinkBundleId = UUID.randomUUID()
                val unlinkCaptureStarted = System.nanoTime()
                await(safetyBundleStore.create(unlinkJob, MutationSafetyOperation.UNLINK, homeWorld.id, area.id,
                    merged, unlinkPlan, testWorld, ActionActor.System("paper-world-e2e"), CorrelationId.newId(), unlinkBundleId)) { unlinkBundle ->
                    recordSafetyReady(unlinkJob, unlinkBundle)
                    val unlinkCaptureMillis = (System.nanoTime() - unlinkCaptureStarted) / 1_000_000
                    val unlinkApplyStarted = System.nanoTime()
                    plotWorldMutation.applyPreparedMutation(unlinkPlan, "unlink").whenComplete { _, unlinkFailure -> server.scheduler.runTask(this, checkedRunnable {
                        if (unlinkFailure != null) throw IllegalStateException("Unlink world E2E failed", unlinkFailure)
                        val unlinkApplyMillis = (System.nanoTime() - unlinkApplyStarted) / 1_000_000
                        check(captureMutationScope(testWorld, unlinkPlan) != unlinkBefore) { "Unlink writer did not change its planned world scope" }
                        merged.forEach { before -> services.regionService.updateRegion(requireNotNull(services.regionService.getRegionById(before.id)).copy(mergeGroupId = null)) }
                        services.regionHomeService?.reconcileUnlinked(merged)
                        check(merged.all { services.regionService.getRegionById(it.id)?.mergeGroupId == null })
                        jobStore.finish(unlinkJob, PlotMutationJobStatus.COMPLETED, unlinkPlan.batches.size, unlinkPlan.batches.size)
                        check(jobStore.findById(unlinkJob)?.status == PlotMutationJobStatus.COMPLETED)
                        check(unlinkBundle.domainSnapshot.regions.all { it.mergeGroupId == group.value.toString() })

                        val stats = "mergeSafetyBlocks=${mergeBundle.worldSnapshot.blockCount} mergeRaw=${mergeBundle.worldSnapshot.rawBytes} " +
                            "mergeZstd=${mergeBundle.worldSnapshot.storedBytes} mergeCaptureMs=$mergeCaptureMillis mergeApplyMs=$mergeApplyMillis " +
                            "unlinkSafetyBlocks=${unlinkBundle.worldSnapshot.blockCount} unlinkRaw=${unlinkBundle.worldSnapshot.rawBytes} " +
                            "unlinkZstd=${unlinkBundle.worldSnapshot.storedBytes} unlinkCaptureMs=$unlinkCaptureMillis unlinkApplyMs=$unlinkApplyMillis"
                        runUnlinkPartialFailureRecovery(testWorld, previousReport, stats)
                    }) }
                }
            }) }
        }
    }

    private fun runMergePartialFailureRecovery(testWorld: World, previousReport: String, safetyStats: String) {
        val left = createRegion(Bounds(18, 20, testWorld.minHeight, testWorld.maxHeight - 1, 6, 8))
            .copy(home = RegionHome(19.5, testWorld.minHeight + 12.0, 7.5, 45f, 0f))
        val right = createRegion(Bounds(22, 24, testWorld.minHeight, testWorld.maxHeight - 1, 6, 8))
            .copy(home = RegionHome(23.5, testWorld.minHeight + 12.0, 7.5, 135f, 0f))
        services.regionService.updateRegion(left)
        services.regionService.updateRegion(right)
        operationSafetyRegions += left
        operationSafetyRegions += right
        val group = MergeGroupId(UUID.randomUUID())
        val prospective = listOf(left, right).map { it.copy(mergeGroupId = group) }
        val plan = plotWorldMutation.prepareMergeMutation(prospective)
        seedMutationScope(testWorld, plan, Material.BARREL)
        val expectedWorld = captureMutationScope(testWorld, plan)
        val expectedDomain = systems.diath.homeclaim.platform.paper.plot.mutation.MergeDomainSnapshotV1.capture(listOf(left, right))
        val jobId = createSafetyJob("MERGE", listOf(left, right))
        val bundleId = UUID.randomUUID()
        await(safetyBundleStore.create(jobId, MutationSafetyOperation.MERGE, homeWorld.id, area.id,
            listOf(left, right), plan, testWorld, ActionActor.System("paper-world-e2e"), CorrelationId.newId(), bundleId)) { bundle ->
            recordSafetyReady(jobId, bundle)
            val failingWriter = PaperPlotMutationService(
                homeClaimPlugin, worldAreaService = services.worldAreaService,
                faultInjector = PlotMutationFaultInjector { operation, applied ->
                    if (operation == "merge" && applied == 1) error("test-only injected partial merge write")
                }
            )
            failingWriter.applyPreparedMutation(plan, "merge").whenComplete { _, writeFailure -> server.scheduler.runTask(this, checkedRunnable {
                check(writeFailure != null) { "Injected merge failure did not fail the world write" }
                jobStore.finish(jobId, PlotMutationJobStatus.RECOVERY_REQUIRED, 0, plan.batches.size, "injected partial world write")
                check(jobStore.findById(jobId)?.status == PlotMutationJobStatus.RECOVERY_REQUIRED)
                val targets = listOf(left.id, right.id)
                val payloadPath = homeClaimPlugin.dataFolder.toPath().resolve("mutation-safety").resolve(jobId.toString())
                    .resolve(bundleId.toString()).resolve(bundle.worldSnapshot.relativeFile)
                val originalPayload = Files.readAllBytes(payloadPath)
                check(originalPayload.isNotEmpty()) { "Safety payload is empty" }
                val corruptPayload = originalPayload.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
                Files.write(payloadPath, corruptPayload)
                awaitFailure(safetyBundleStore.validate(jobId, bundleId, MutationSafetyOperation.MERGE, area.id, homeWorld.id,
                    testWorld.uid, targets, bundle.manifestSha256)) {
                    Files.write(payloadPath, originalPayload)
                    recoverMergeSafetyBundle(testWorld, plan, expectedWorld, expectedDomain, jobId, bundleId, bundle,
                        targets, previousReport, safetyStats)
                }
            }) }
        }
    }

    private fun runUnlinkPartialFailureRecovery(testWorld: World, previousReport: String, safetyStats: String) {
        val left = createRegion(Bounds(32, 34, testWorld.minHeight, testWorld.maxHeight - 1, 14, 16))
            .copy(home = RegionHome(33.5, testWorld.minHeight + 12.0, 15.5, 20f, 0f))
        val right = createRegion(Bounds(38, 40, testWorld.minHeight, testWorld.maxHeight - 1, 14, 16))
            .copy(home = RegionHome(39.5, testWorld.minHeight + 12.0, 15.5, 160f, 0f))
        services.regionService.updateRegion(left)
        services.regionService.updateRegion(right)
        operationSafetyRegions += left
        operationSafetyRegions += right
        val group = services.regionService.mergeRegions(listOf(left.id, right.id))
        val merged = listOf(left, right).map { before ->
            val state = before.copy(mergeGroupId = group, home = if (before.id == left.id) left.home else null)
            services.regionService.updateRegion(state)
            state
        }
        val plan = plotWorldMutation.prepareUnlinkMutation(merged, createRoads = true, MutationReason.UNCLAIM)
        seedMutationScope(testWorld, plan, Material.BARREL)
        val expectedWorld = captureMutationScope(testWorld, plan)
        val expectedDomain = systems.diath.homeclaim.platform.paper.plot.mutation.MergeDomainSnapshotV1.capture(merged)
        val jobId = createSafetyJob("UNLINK", merged)
        val bundleId = UUID.randomUUID()
        await(safetyBundleStore.create(jobId, MutationSafetyOperation.UNLINK, homeWorld.id, area.id,
            merged, plan, testWorld, ActionActor.System("paper-world-unlink-e2e"), CorrelationId.newId(), bundleId)) { bundle ->
            recordSafetyReady(jobId, bundle)
            val failingWriter = PaperPlotMutationService(
                homeClaimPlugin, worldAreaService = services.worldAreaService,
                faultInjector = PlotMutationFaultInjector { operation, applied ->
                    if (operation == "unlink" && applied == 1) error("test-only injected partial unlink write")
                }
            )
            failingWriter.applyPreparedMutation(plan, "unlink").whenComplete { _, writeFailure -> server.scheduler.runTask(this, checkedRunnable {
                check(writeFailure != null) { "Injected unlink failure did not fail the world write" }
                jobStore.finish(jobId, PlotMutationJobStatus.RECOVERY_REQUIRED, 0, plan.batches.size, "injected partial unlink world write")
                check(jobStore.findById(jobId)?.status == PlotMutationJobStatus.RECOVERY_REQUIRED)
                val targets = merged.map { it.id }
                when (val attempt = conflicts.reserveRecovery(jobId, targets, ActionActor.System("paper-world-unlink-recovery"), CorrelationId.newId())) {
                    is ReservationAttempt.Conflict -> error("Could not reserve unlink recovery: ${attempt.conflict}")
                    is ReservationAttempt.Acquired -> {
                        val lease = attempt.lease
                        check(jobStore.beginRecovery(jobId, "e2e unlink operation recovery"))
                        safetyBundleStore.validate(jobId, bundleId, MutationSafetyOperation.UNLINK, area.id, homeWorld.id,
                            testWorld.uid, targets, bundle.manifestSha256).whenComplete { validated, validationError -> server.scheduler.runTask(this, checkedRunnable {
                            if (validationError != null) throw IllegalStateException("Unlink safety bundle validation failed", validationError)
                            check(jobStore.updateRecoveryDetails(jobId, "phase=WORLD_RESTORE_RUNNING"))
                            safetyBundleStore.restoreWorld(validated, testWorld).whenComplete { _, restoreError -> server.scheduler.runTask(this, checkedRunnable {
                                if (restoreError != null) throw IllegalStateException("Unlink world restore failed", restoreError)
                                restoreDomainSnapshot(validated.domainSnapshot, area.id)
                                check(captureMutationScope(testWorld, plan) == expectedWorld) { "Recovered unlink world differs from its original image" }
                                val restoredRegions = targets.map { requireNotNull(services.regionService.getRegionById(it)) }
                                check(systems.diath.homeclaim.platform.paper.plot.mutation.MergeDomainSnapshotV1.capture(restoredRegions) == expectedDomain) {
                                    "Recovered unlink domain differs from its original MergeGroup/home pre-image"
                                }
                                check(restoredRegions.all { it.mergeGroupId == group }) { "Unlink recovery lost its original MergeGroup ID" }
                                check(jobStore.finishRecovery(jobId, PlotMutationJobStatus.RECOVERED, "phase=WORLD_AND_DOMAIN_VERIFIED"))
                                lease.close()
                                check(conflicts.checkAvailable(targets) == null) { "Unlink recovery left its regions blocked" }
                                runMergePartialFailureRecovery(testWorld, "$previousReport; unlinkPartialFailureRecovery=PASS", safetyStats)
                            }) }
                        }) }
                    }
                }
            }) }
        }
    }

    private fun recoverMergeSafetyBundle(
        testWorld: World,
        plan: PreparedPlotWorldMutation,
        expectedWorld: Map<Triple<Int, Int, Int>, BlockImage>,
        expectedDomain: systems.diath.homeclaim.platform.paper.plot.mutation.MergeDomainSnapshotV1,
        jobId: UUID,
        bundleId: UUID,
        bundle: MutationSafetyBundle,
        targets: List<RegionId>,
        previousReport: String,
        safetyStats: String
    ) {
        when (val attempt = conflicts.reserveRecovery(jobId, targets, ActionActor.System("paper-world-e2e-recovery"), CorrelationId.newId())) {
            is ReservationAttempt.Conflict -> error("Could not reserve merge recovery: ${attempt.conflict}")
            is ReservationAttempt.Acquired -> {
                val lease = attempt.lease
                check(jobStore.beginRecovery(jobId, "e2e operation recovery"))
                safetyBundleStore.validate(jobId, bundleId, MutationSafetyOperation.MERGE, area.id, homeWorld.id,
                    testWorld.uid, targets, bundle.manifestSha256).whenComplete { validated, validationError -> server.scheduler.runTask(this, checkedRunnable {
                    if (validationError != null) throw IllegalStateException("Merge safety bundle validation failed", validationError)
                    check(validated.worldSnapshot.sha256 == bundle.worldSnapshot.sha256)
                    check(jobStore.updateRecoveryDetails(jobId, "phase=WORLD_RESTORE_RUNNING"))
                    safetyBundleStore.restoreWorld(validated, testWorld).whenComplete { _, restoreError -> server.scheduler.runTask(this, checkedRunnable {
                        if (restoreError != null) throw IllegalStateException("Merge world restore failed", restoreError)
                        restoreDomainSnapshot(validated.domainSnapshot, area.id)
                        check(captureMutationScope(testWorld, plan) == expectedWorld) { "Recovered merge world differs from its original image" }
                        check(systems.diath.homeclaim.platform.paper.plot.mutation.MergeDomainSnapshotV1.capture(
                            listOfNotNull(services.regionService.getRegionById(targets[0]), services.regionService.getRegionById(targets[1]))
                        ) == expectedDomain) { "Recovered merge domain differs from its original image" }
                        check(jobStore.finishRecovery(jobId, PlotMutationJobStatus.RECOVERED, "phase=WORLD_AND_DOMAIN_VERIFIED"))
                        lease.close()
                        check(conflicts.checkAvailable(targets) == null) { "Merge recovery left its regions blocked" }
                        finish("$previousReport; merge=PASS; unlink=PASS; mergePartialFailureRecovery=PASS; safetyIntegrity=corrupt-payload-rejected; operationSafety=zstd-world+domain-v1; $safetyStats")
                    }) }
                }) }
            }
        }
    }

    private fun createSafetyJob(operation: String, regions: List<Region>): UUID {
        val id = UUID.randomUUID()
        val correlation = CorrelationId.newId()
        jobStore.create(PlotMutationJobRecord(
            id, correlation, operation, homeWorld.id, area.id, requireNotNull(world).name,
            regions.map { it.id }, "paper-world-e2e", PlotMutationJobStatus.QUEUED,
            details = "format=homeclaim-mutation-safety-v1\nphase=SAFETY_CAPTURE"
        ))
        jobStore.markRunning(id)
        return id
    }

    private fun recordSafetyReady(jobId: UUID, bundle: MutationSafetyBundle) {
        jobStore.updateDetails(jobId, "format=homeclaim-mutation-safety-v1\nsafetyBundleId=${bundle.bundleId}\nsafetyBundleManifestSha256=${bundle.manifestSha256}\nphase=WORLD_MUTATION")
        check(jobStore.findById(jobId)?.details?.contains("safetyBundleId=${bundle.bundleId}") == true) {
            "Could not persist operation safety bundle reference before mutation"
        }
    }

    private fun seedMutationScope(testWorld: World, plan: PreparedPlotWorldMutation, container: Material) {
        val coordinates = plan.batches.flatMap { it.operations }.distinctBy { Triple(it.x, it.y, it.z) }
        check(coordinates.isNotEmpty()) { "Merge/unlink plan has no concrete block coordinates" }
        coordinates.forEachIndexed { index, op ->
            val block = testWorld.getBlockAt(op.x, op.y, op.z)
            block.setType(if (index == 0) container else if (index % 2 == 0) Material.GOLD_BLOCK else Material.LAPIS_BLOCK, false)
        }
        val op = coordinates.first()
        val state = testWorld.getBlockAt(op.x, op.y, op.z).state as? Container
            ?: error("Operation safety fixture did not create a container TileState")
        state.inventory.setItem(2, ItemStack(Material.EMERALD, 11))
        check(state.update(true, false))
    }

    private fun captureMutationScope(testWorld: World, plan: PreparedPlotWorldMutation): Map<Triple<Int, Int, Int>, BlockImage> =
        plan.batches.flatMap { it.operations }.distinctBy { Triple(it.x, it.y, it.z) }.associate { op ->
            val block = testWorld.getBlockAt(op.x, op.y, op.z)
            val items = (block.state as? Container)?.snapshotInventory?.contents?.map { item ->
                item?.takeUnless { it.type.isAir }?.serializeAsBytes()?.let(Base64.getEncoder()::encodeToString)
            }
            Triple(op.x, op.y, op.z) to BlockImage(block.blockData.asString, items)
        }

    private fun restoreDomainSnapshot(snapshot: systems.diath.homeclaim.platform.paper.plot.mutation.MergeDomainSnapshotV1, areaId: systems.diath.homeclaim.core.model.AreaId) {
        systems.diath.homeclaim.core.service.MutationAuditScope.withinOperation {
            snapshot.regions.forEach { state ->
                val id = RegionId(UUID.fromString(state.regionId))
                val current = requireNotNull(services.regionService.getRegionById(id))
                check(current.areaId == areaId)
                val group = state.mergeGroupId?.let { MergeGroupId(UUID.fromString(it)) }
                val home = state.home?.let { RegionHome(it.x, it.y, it.z, it.yaw, it.pitch) }
                services.regionService.updateRegion(current.copy(mergeGroupId = group, home = home))
            }
        }
    }

    private fun readyPlan(operation: PlotTransferOperation, source: Region, target: Region): systems.diath.homeclaim.platform.paper.plot.mutation.PlotTransferPlan {
        val testWorld = requireNotNull(world)
        val ready = PlotTransferPlanner.plan(operation, source, target, homeWorld.id, homeWorld.id, testWorld.uid, testWorld.uid)
        return (ready as? PlotTransferPlanResult.Ready)?.plan ?: error("Transfer planner rejected $operation")
    }

    private fun recover(jobId: UUID, action: PlotMutationRecoveryAction, done: () -> Unit) {
        val job = requireNotNull(jobStore.findById(jobId))
        val plan = PlotMutationRecoveryPlanner.plan(job, action)
        val targets = (plan as? PlotMutationRecoveryPlanResult.Ready)?.targets
            ?: error("Recovery planner rejected $action for $jobId: $plan")
        val actor = ActionActor.System("paper-world-e2e-recovery")
        val correlation = CorrelationId.newId()
        val regionTargets = targets.map { target ->
            requireNotNull(services.regionService.getRegionById(target.regionId)) to target.backupId
        }
        val lease = when (val attempt = conflicts.reserveRecovery(job.id, regionTargets.map { it.first.id }, actor, correlation)) {
            is ReservationAttempt.Acquired -> attempt.lease
            is ReservationAttempt.Conflict -> error("Could not reserve recovery targets: ${attempt.conflict}")
        }
        check(jobStore.beginRecovery(job.id, "e2e recovery action=$action")) { "Could not persist recovery start" }
        var restoreChain = CompletableFuture.completedFuture(Unit)
        regionTargets.forEach { (region, backupId) ->
            restoreChain = restoreChain.thenCompose {
                testBackupService.restore(backupId, region, requireNotNull(world), actor, correlation,
                    lease, recoveryParentJobId = job.id).thenApply { Unit }
            }
        }
        restoreChain.whenComplete { _, failure -> server.scheduler.runTask(this, Runnable {
            val terminal = if (failure == null) PlotMutationJobStatus.RECOVERED else PlotMutationJobStatus.RECOVERY_REQUIRED
            val saved = jobStore.finishRecovery(job.id, terminal, "e2e recovery action=$action terminal=$terminal", failure?.message)
            lease.close()
            check(saved) { "Could not persist recovery terminal state" }
            if (failure != null) throw IllegalStateException("Recovery action failed", failure)
            check(conflicts.checkAvailable(regionTargets.map { it.first.id }) == null) { "Successful recovery did not release the persisted block" }
            done()
        }) }
    }

    private fun runTransfer(operation: PlotTransferOperation, source: Region, target: Region,
                            done: (PlotTransferResult) -> Unit) {
        val testWorld = requireNotNull(world)
        val plan = PlotTransferPlanner.plan(operation, source, target, homeWorld.id, homeWorld.id,
            testWorld.uid, testWorld.uid) as? PlotTransferPlanResult.Ready
            ?: error("Transfer planner rejected $operation")
        await(transferService.execute(plan.plan, testWorld, ActionActor.System("paper-world-e2e"), CorrelationId.newId()), done)
    }

    private fun verifyReadyBackup(id: UUID, done: (PlotBackupRecord) -> Unit) {
        val checked = backupService.validateReadyBackup(id)
        await(checked) { record ->
            check(record.status == PlotBackupStatus.READY)
            check(!record.checksumSha256.isNullOrBlank()) { "Backup checksum is missing" }
            done(record)
        }
    }

    private fun createRegion(bounds: Bounds): Region {
        val region = Region(RegionId(UUID.randomUUID()), requireNotNull(world).name, RegionShape.CUBOID,
            bounds, UUID.randomUUID(), areaId = area.id)
        services.regionService.createRegion(region, bounds)
        return requireNotNull(services.regionService.getRegionById(region.id))
    }

    private fun seed(region: Region, label: String) {
        val testWorld = requireNotNull(world)
        val x = region.bounds.minX
        for (y in testWorld.minHeight until testWorld.maxHeight) testWorld.getBlockAt(x, y, 0).setType(Material.AIR, false)
        val base = testWorld.minHeight + 10
        testWorld.getBlockAt(x, base, 0).setType(if (label.contains("target") || label.endsWith("b")) Material.DIAMOND_BLOCK else Material.STONE, false)
        val stairData = Bukkit.createBlockData("minecraft:oak_stairs[facing=${if (label.contains("target") || label.endsWith("b")) "west" else "east"},half=bottom,shape=straight,waterlogged=false]")
        testWorld.getBlockAt(x, base + 1, 0).setBlockData(stairData, false)
        putContainer(x, base + 2, Material.CHEST, label, Material.EMERALD)
        putContainer(x, base + 4, Material.BARREL, label, Material.GOLD_INGOT)
        putContainer(x, base + 6, Material.FURNACE, label, Material.IRON_INGOT)
        putContainer(x, base + 8, Material.PURPLE_SHULKER_BOX, label, Material.DIAMOND)
    }

    private fun putContainer(x: Int, y: Int, type: Material, label: String, item: Material) {
        val testWorld = requireNotNull(world)
        testWorld.getBlockAt(x, y, 0).setType(type, false)
        val container = testWorld.getBlockAt(x, y, 0).state as? Container ?: error("$type is not a Paper Container TileState")
        val slot = minOf(3, container.inventory.size - 1)
        container.inventory.setItem(slot, ItemStack(item, label.length.coerceIn(1, 64)))
        check(container.update(true, false)) { "Failed to store $type fixture at $x,$y,0" }
    }

    private fun capture(testWorld: World, x: Int): List<BlockImage> =
        (testWorld.minHeight until testWorld.maxHeight).map { y ->
            val block = testWorld.getBlockAt(x, y, 0)
            val state = block.state
            val items = when (state) {
                is Container -> state.snapshotInventory.contents.map { item ->
                    item?.takeUnless { it.type.isAir }?.serializeAsBytes()?.let(Base64.getEncoder()::encodeToString)
                }
                else -> {
                    check(state !is org.bukkit.block.TileState) { "Unexpected unsupported TileState ${state.type} at $x,$y,0" }
                    null
                }
            }
            BlockImage(block.blockData.asString, items)
        }

    private fun <T> await(future: CompletableFuture<T>, done: (T) -> Unit) {
        var ticks = 0
        lateinit var task: BukkitTask
        task = server.scheduler.runTaskTimer(this, Runnable {
            ticks++
            if (future.isDone) {
                task.cancel()
                runCatching { done(future.get()) }.onFailure(::fail)
            } else if (ticks > 20 * 60) {
                task.cancel()
                fail(IllegalStateException("E2E operation timed out after 60 seconds"))
            }
        }, 1L, 1L)
    }

    private fun <T> awaitFailure(future: CompletableFuture<T>, done: () -> Unit) {
        await(future.handle { _, failure ->
            requireNotNull(failure) { "Expected backup to reject unsupported TileState" }
            Unit
        }) { done() }
    }

    private fun finish(result: String = "PASS copy move swap reset restore; copy/move/swap/restore partial-failure recovery; zstd containers; unsupported-tilestate-fail-closed") {
        val testWorld = requireNotNull(world)
        services.regionService.deleteRegion(first.id)
        services.regionService.deleteRegion(second.id)
        if (::third.isInitialized) services.regionService.deleteRegion(third.id)
        operationSafetyRegions.distinctBy { it.id }.forEach { services.regionService.deleteRegion(it.id) }
        concurrencyRegion?.let { services.regionService.deleteRegion(it.id) }
        concurrencyTarget?.let { services.regionService.deleteRegion(it.id) }
        concurrencyLease?.close()
        concurrencyLease = null
        services.worldAreaService?.deleteArea(area.id)
        runCatching { testBackupService.shutdown() }
        runCatching { safetyBundleStore.shutdown() }
        runCatching { testWorld.save() }
        writeResult(result)
        Bukkit.shutdown()
    }

    private fun fail(error: Throwable) {
        logger.severe("Paper world E2E failed: ${error.stackTraceToString()}")
        concurrencyLease?.close()
        concurrencyLease = null
        runCatching { if (::testBackupService.isInitialized) testBackupService.shutdown() }
        runCatching { if (::safetyBundleStore.isInitialized) safetyBundleStore.shutdown() }
        runCatching { writeResult("FAIL ${error.javaClass.simpleName}: ${error.message}") }
        Bukkit.shutdown()
    }

    private fun writeResult(value: String) {
        Files.createDirectories(reportFile.parent)
        Files.writeString(reportFile, value + "\n")
    }

    private fun checkedRunnable(action: () -> Unit): Runnable = Runnable {
        runCatching(action).onFailure(::fail)
    }

    private fun <T> privateField(target: Any, name: String): T {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(target) as T
    }

    private data class BlockImage(val blockData: String, val items: List<String?>?)
}
