package systems.diath.homeclaim.platform.paper.plot.mutation

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.block.Biome
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.CorrelationId
import systems.diath.homeclaim.core.service.WorldAreaService
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded, cell-aligned biome mutation with a separately versioned durable pre-image. */
class PaperBiomeMutationService(
    private val plugin: JavaPlugin,
    private val worldAreaService: WorldAreaService,
    private val jobStore: PlotMutationJobStore? = null,
    private val cellsPerTick: Int = 256,
    private val snapshotStore: PaperBiomeSnapshotStore = PaperBiomeSnapshotStore(plugin),
    private val observer: BiomeMutationObserver = BiomeMutationObserver.NONE
) : AutoCloseable {
    private val activeJobs = java.util.concurrent.ConcurrentHashMap<UUID, () -> Unit>()

    init { require(cellsPerTick > 0) }

    fun apply(regions: Collection<Region>, biome: Biome): CompletableFuture<Unit> =
        apply(regions, biome, CorrelationId.newId(), null)

    fun apply(regions: Collection<Region>, biome: Biome, correlationId: CorrelationId): CompletableFuture<Unit> =
        apply(regions, biome, correlationId, null)

    fun apply(regions: Collection<Region>, biome: Biome, correlationId: CorrelationId, actor: ActionActor?): CompletableFuture<Unit> {
        if (regions.isEmpty()) return CompletableFuture.completedFuture(Unit)
        if (jobStore == null) return CompletableFuture.failedFuture(IllegalStateException("Persistent mutation-job storage is required for recoverable biome changes"))
        val regionList = regions.distinctBy { it.id }
        val first = regionList.first()
        val world = resolveWorld(first)
            ?: return CompletableFuture.failedFuture(IllegalStateException("World binding unavailable for biome mutation"))
        if (regionList.any { it.areaId != first.areaId || resolveWorld(it)?.uid != world.uid }) {
            return CompletableFuture.failedFuture(IllegalArgumentException("Biome mutation regions must share one Area and Paper world"))
        }
        val cells = try { BiomeCellScope.cells(regionList) }
        catch (failure: Throwable) { return CompletableFuture.failedFuture(failure) }
        val outOfWorld = firstOutOfWorldHeight(cells, world)
        if (outOfWorld != null) return CompletableFuture.failedFuture(IllegalArgumentException(
            "Biome cell quart ${outOfWorld.quartX},${outOfWorld.quartY},${outOfWorld.quartZ} is outside Paper world height ${world.minHeight}..${world.maxHeight - 1}"))
        val unloadedBeforeCapture = firstUnloadedChunk(cells, world)
        if (unloadedBeforeCapture != null) {
            return CompletableFuture.failedFuture(IllegalStateException(
                "Biome mutation requires affected chunk ${unloadedBeforeCapture.first},${unloadedBeforeCapture.second} to be loaded; no snapshot or world write was started"))
        }
        val homeWorldId = worldAreaService.getArea(first.areaId)?.worldId
            ?: return CompletableFuture.failedFuture(IllegalStateException("Biome mutation Area has no HomeClaim World binding"))
        val snapshotId = UUID.randomUUID()
        val captured = ArrayList<BiomeSnapshotCell>(cells.size)
        val result = CompletableFuture<Unit>()
        val captureIndex = intArrayOf(0)
        var task: BukkitTask? = null
        var jobId: UUID? = null
        var applied = 0
        val finished = AtomicBoolean()

        fun completeMutation(failure: Throwable?) {
            if (!finished.compareAndSet(false, true)) return
            task?.cancel()
            val id = jobId
            id?.let(activeJobs::remove)
            if (id == null) {
                result.completeExceptionally(failure ?: IllegalStateException("Biome operation did not create its durable job"))
                return
            }
            val status = if (failure == null) PlotMutationJobStatus.COMPLETED
                else if (applied > 0) PlotMutationJobStatus.RECOVERY_REQUIRED else PlotMutationJobStatus.FAILED
            runAsync {
                jobStore?.finish(id, status, if (failure == null) cells.size else applied, cells.size,
                    failure?.let { it.message ?: it.javaClass.simpleName })
            }.whenComplete { _, journalFailure ->
                if (failure == null && journalFailure == null) result.complete(Unit)
                else if (failure == null) result.completeExceptionally(IllegalStateException("Biome mutation completed but its job could not be finalized", journalFailure))
                else if (journalFailure != null) {
                    failure.addSuppressed(journalFailure)
                    result.completeExceptionally(IllegalStateException("Biome mutation failed; its durable job remains for startup recovery classification", failure))
                } else result.completeExceptionally(failure)
            }
        }

        fun beginApply(reference: BiomeSnapshotReference) {
            val id = UUID.randomUUID()
            val record = PlotMutationJobRecord(
                id = id,
                correlationId = correlationId,
                operation = "BIOME",
                worldId = homeWorldId,
                areaId = first.areaId,
                worldName = world.name,
                regionIds = regionList.map { it.id },
                backend = "paper-biome-cell-batch",
                status = PlotMutationJobStatus.QUEUED,
                totalUnits = cells.size,
                details = listOf(
                    "format=biome-snapshot-v1", "captureMode=${BiomeSnapshotV1.CAPTURE_MODE}",
                    "snapshotId=${reference.id}", "snapshotSha256=${reference.sha256}",
                    "snapshotBytes=${reference.sizeBytes}", "snapshotCellCount=${cells.size}", "paperWorldId=${world.uid}",
                    "targetBiome=${biome.key}", "phase=APPLYING_BIOME",
                    "actorKind=${actor?.kind ?: "UNKNOWN"}", "actorRef=${actor?.stableId ?: ""}"
                ).joinToString("\n")
            )
            jobId = id
            runAsync {
                jobStore?.create(record)
                jobStore?.markRunning(id)
            }.whenComplete { _, persistenceFailure -> onMain {
                if (persistenceFailure != null) {
                    completeMutation(IllegalStateException("Could not persist biome job before mutation", persistenceFailure))
                    return@onMain
                }
                var index = 0
                task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
                    try {
                        repeat(cellsPerTick) {
                            if (index >= cells.size) {
                                task?.cancel()
                                task = null
                                verify(cells, biome.key.toString(), world).whenComplete { _, verifyFailure -> onMain {
                                    completeMutation(verifyFailure?.let { unwrap(it) })
                                } }
                                return@Runnable
                            }
                            val cell = cells[index++]
                            check(world.isChunkLoaded(Math.floorDiv(cell.blockX(), 16), Math.floorDiv(cell.blockZ(), 16))) {
                                "Biome chunk unloaded during mutation at quart ${cell.quartX},${cell.quartY},${cell.quartZ}"
                            }
                            observer.beforeCellApplied(applied)
                            world.setBiome(cell.blockX(), cell.blockY(), cell.blockZ(), biome)
                            applied++
                            observer.afterCellApplied(applied)
                        }
                        if (applied % PROGRESS_INTERVAL < cellsPerTick) runAsync {
                            jobStore?.updateProgress(id, applied.coerceAtMost(cells.size), cells.size)
                        }
                    } catch (failure: Throwable) { completeMutation(failure) }
                }, 1L, 1L)
                activeJobs[id] = { completeMutation(CancellationException("Biome mutation cancelled by plugin shutdown")) }
            } }
        }

        fun captureNext() {
            if (finished.get()) return
            try {
                repeat(cellsPerTick) {
                    if (captureIndex[0] >= cells.size) {
                        task?.cancel(); task = null
                        val snapshot = BiomeSnapshotV1(snapshotId, homeWorldId, world.uid, first.areaId,
                            regionList.map { it.id }, Instant.now(), captured.toList())
                        snapshotStore.write(snapshot).whenComplete { reference, writeFailure ->
                            if (writeFailure != null) completeMutation(unwrap(writeFailure)) else beginApply(reference)
                        }
                        return
                    }
                    val cell = cells[captureIndex[0]++]
                    check(world.isChunkLoaded(Math.floorDiv(cell.blockX(), 16), Math.floorDiv(cell.blockZ(), 16))) {
                        "Biome chunk unloaded during pre-image capture at quart ${cell.quartX},${cell.quartY},${cell.quartZ}"
                    }
                    val key = world.getBiome(cell.blockX(), cell.blockY(), cell.blockZ()).key.toString()
                    captured += BiomeSnapshotCell(cell, key)
                }
            } catch (failure: Throwable) { completeMutation(failure) }
        }

        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable(::captureNext), 1L, 1L)
        return result
    }

    /** Loads and validates all registry keys before writing any cell, then applies and verifies the exact pre-image. */
    fun restoreOriginal(
        snapshotId: UUID,
        expectedSha256: String,
        world: World,
        expectedWorldId: WorldId,
        expectedRegions: Collection<Region>,
        expectedPaperWorldId: UUID
    ): CompletableFuture<Int> = snapshotStore.read(snapshotId, expectedSha256).thenCompose { snapshot ->
        val prepared = CompletableFuture<List<Pair<BiomeCell, Biome>>>()
        onMain {
            runCatching {
                require(expectedRegions.isNotEmpty())
                require(snapshot.homeClaimWorldId == expectedWorldId && snapshot.areaId == expectedRegions.first().areaId &&
                    snapshot.paperWorldId == expectedPaperWorldId && world.uid == expectedPaperWorldId) {
                    "Biome snapshot HomeClaim/Paper world or Area binding mismatch"
                }
                require(snapshot.regionIds.toSet() == expectedRegions.map { it.id }.toSet()) { "Biome snapshot region scope mismatch" }
                val expectedCells = BiomeCellScope.cells(expectedRegions)
                require(snapshot.cells.map { it.cell }.toSet() == expectedCells.toSet()) {
                    "Biome snapshot cell scope no longer matches the affected plot bounds"
                }
                require(firstOutOfWorldHeight(expectedCells, world) == null) { "Biome snapshot contains a cell outside Paper world height" }
                val unloaded = firstUnloadedChunk(snapshot.cells.map { it.cell }, world)
                require(unloaded == null) {
                    "Biome recovery requires affected chunk ${unloaded!!.first},${unloaded.second} to be loaded; no restore write was started"
                }
                val registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME)
                snapshot.cells.map { entry ->
                    val key = org.bukkit.NamespacedKey.fromString(entry.biomeKey)
                        ?: error("Biome snapshot contains invalid key ${entry.biomeKey}")
                    val biome = registry.get(key) ?: error("Biome registry key ${entry.biomeKey} is unavailable; recovery was not started")
                    entry.cell to biome
                }.also(prepared::complete)
            }.onFailure(prepared::completeExceptionally)
        }
        prepared.thenCompose { resolved -> applyAndVerifyCells(world, resolved).thenApply { resolved.size } }
    }

    private fun verify(cells: List<BiomeCell>, expectedKey: String, world: World): CompletableFuture<Unit> {
        val result = CompletableFuture<Unit>()
        var index = 0
        lateinit var task: BukkitTask
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            try {
                repeat(cellsPerTick) {
                    if (index >= cells.size) { task.cancel(); result.complete(Unit); return@Runnable }
                    val cell = cells[index++]
                    check(world.isChunkLoaded(Math.floorDiv(cell.blockX(), 16), Math.floorDiv(cell.blockZ(), 16))) {
                        "Biome chunk unloaded during verification at quart ${cell.quartX},${cell.quartY},${cell.quartZ}"
                    }
                    val actual = world.getBiome(cell.blockX(), cell.blockY(), cell.blockZ()).key.toString()
                    check(actual == expectedKey) { "Biome verification mismatch at quart ${cell.quartX},${cell.quartY},${cell.quartZ}: expected $expectedKey, found $actual" }
                }
            } catch (failure: Throwable) { task.cancel(); result.completeExceptionally(failure) }
        }, 1L, 1L)
        return result
    }

    private fun applyAndVerifyCells(world: World, cells: List<Pair<BiomeCell, Biome>>): CompletableFuture<Unit> {
        val applied = CompletableFuture<Unit>()
        var index = 0
        lateinit var task: BukkitTask
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            try {
                repeat(cellsPerTick) {
                    if (index >= cells.size) { task.cancel(); applied.complete(Unit); return@Runnable }
                    val (cell, biome) = cells[index++]
                    check(world.isChunkLoaded(Math.floorDiv(cell.blockX(), 16), Math.floorDiv(cell.blockZ(), 16))) {
                        "Biome chunk unloaded during recovery at quart ${cell.quartX},${cell.quartY},${cell.quartZ}"
                    }
                    world.setBiome(cell.blockX(), cell.blockY(), cell.blockZ(), biome)
                }
            } catch (failure: Throwable) { task.cancel(); applied.completeExceptionally(failure) }
        }, 1L, 1L)
        return applied.thenCompose {
            val verified = CompletableFuture<Unit>()
            var verifyIndex = 0
            lateinit var verifyTask: BukkitTask
            verifyTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
                try {
                    repeat(cellsPerTick) {
                        if (verifyIndex >= cells.size) { verifyTask.cancel(); verified.complete(Unit); return@Runnable }
                        val (cell, expected) = cells[verifyIndex++]
                        check(world.getBiome(cell.blockX(), cell.blockY(), cell.blockZ()).key == expected.key) {
                            "Biome recovery verification mismatch at quart ${cell.quartX},${cell.quartY},${cell.quartZ}"
                        }
                    }
                } catch (failure: Throwable) { verifyTask.cancel(); verified.completeExceptionally(failure) }
            }, 1L, 1L)
            verified
        }
    }

    fun shutdown() {
        activeJobs.values.toList().forEach { cancel -> runCatching(cancel) }
        snapshotStore.close()
    }

    override fun close() = shutdown()

    private fun resolveWorld(region: Region): World? {
        val area = worldAreaService.getArea(region.areaId) ?: return null
        val homeWorld = worldAreaService.getWorld(area.worldId) ?: return null
        return homeWorld.platformWorldId?.let(Bukkit::getWorld) ?: Bukkit.getWorld(homeWorld.name)
    }

    private fun firstUnloadedChunk(cells: Collection<BiomeCell>, world: World): Pair<Int, Int>? = cells.asSequence()
        .map { Math.floorDiv(it.blockX(), 16) to Math.floorDiv(it.blockZ(), 16) }.distinct()
        .firstOrNull { (x, z) -> !world.isChunkLoaded(x, z) }

    private fun firstOutOfWorldHeight(cells: Collection<BiomeCell>, world: World): BiomeCell? = cells.firstOrNull { cell ->
        cell.blockY() < world.minHeight || cell.blockY().toLong() + 3L >= world.maxHeight.toLong()
    }

    private fun runAsync(action: () -> Unit): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()
        try { Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable { runCatching(action).fold({ future.complete(Unit) }, future::completeExceptionally) }) }
        catch (failure: Throwable) { future.completeExceptionally(failure) }
        return future
    }

    private fun onMain(action: () -> Unit) {
        try { Bukkit.getScheduler().runTask(plugin, Runnable(action)) }
        catch (failure: Throwable) { plugin.logger.severe("Could not schedule biome mutation continuation: ${failure.message}") }
    }

    private fun unwrap(failure: Throwable): Throwable = failure.cause ?: failure

    private companion object { const val PROGRESS_INTERVAL = 1024 }
}

/** No production activation path; implementations can observe bounded mutation progress. */
fun interface BiomeMutationObserver {
    fun afterCellApplied(applied: Int)
    fun beforeCellApplied(applied: Int) {}
    companion object { val NONE = BiomeMutationObserver { } }
}
