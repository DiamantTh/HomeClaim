package systems.diath.homeclaim.platform.paper.plot.mutation

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.block.Container
import org.bukkit.block.TileState
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import systems.diath.homeclaim.core.model.*
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.CorrelationId
import systems.diath.homeclaim.core.mutation.BlockMutationOp
import systems.diath.homeclaim.core.mutation.MutationBatch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Durable, operation-scoped pre-image for the exact write coordinates and changed merge domain fields. */
class PaperMutationSafetyBundleStore(private val plugin: JavaPlugin) : MutationSafetyBundleService {
    private val root = plugin.dataFolder.toPath().resolve("mutation-safety").toAbsolutePath().normalize()
    private val io = Executors.newSingleThreadExecutor { task -> Thread(task, "HomeClaim-MutationSafety-IO").apply { isDaemon = true } }
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val captures = ConcurrentHashMap<UUID, Capture>()
    @Volatile private var closed = false

    init {
        io.execute {
            runCatching {
                Files.createDirectories(root)
                Files.list(root).use { entries -> entries.filter { it.fileName.toString().startsWith(".staging-") }
                    .forEach { it.toFile().deleteRecursively() } }
            }.onFailure { plugin.logger.warning("Could not clean incomplete mutation safety staging data: ${it.message}") }
        }
    }

    override fun create(
        jobId: UUID,
        operation: MutationSafetyOperation,
        worldId: WorldId,
        areaId: AreaId,
        regions: Collection<Region>,
        plan: PreparedPlotWorldMutation,
        world: World,
        actor: ActionActor,
        correlationId: CorrelationId,
        bundleId: UUID
    ): CompletableFuture<MutationSafetyBundle> {
        require(Bukkit.isPrimaryThread()) { "Safety capture must be started on Paper's server thread" }
        check(!closed) { "Mutation safety store is shutting down" }
        require(world.uid == plan.worldId) { "Prepared mutation world binding changed before safety capture" }
        require(world.minHeight <= world.maxHeight && world.maxHeight > world.minHeight)
        val ordered = regions.distinctBy { it.id }.sortedBy { it.id.value.toString() }
        require(ordered.isNotEmpty() && ordered.all { it.areaId == areaId })
        val operationCount = plan.batches.sumOf { it.operations.size.toLong() }
        require(operationCount > 0) { "Mutation plan has no world writes" }
        require(operationCount <= MAX_BLOCKS) { "Mutation safety scope exceeds $MAX_BLOCKS block writes" }
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
        var minZ = Int.MAX_VALUE; var maxZ = Int.MIN_VALUE
        plan.batches.forEach { batch -> batch.operations.forEach { op ->
            minX = minOf(minX, op.x); maxX = maxOf(maxX, op.x)
            minY = minOf(minY, op.y); maxY = maxOf(maxY, op.y)
            minZ = minOf(minZ, op.z); maxZ = maxOf(maxZ, op.z)
        } }
        val bounds = Bounds(minX, maxX, minY, maxY, minZ, maxZ)
        require(bounds.minY >= world.minHeight && bounds.maxY < world.maxHeight) { "Mutation scope is outside Paper world bounds" }
        val staging = root.resolve(".staging-${bundleId}")
        val ready = root.resolve(jobId.toString()).resolve(bundleId.toString())
        val domainSnapshot = MergeDomainSnapshotV1.capture(ordered)
        val raw = staging.resolve("world.payload.raw.part")
        val payload = staging.resolve("world.payload.zst.part")
        val result = CompletableFuture<MutationSafetyBundle>()
        val capture = Capture(
            jobId, bundleId, operation, worldId, areaId, ordered, plan, world, actor, correlationId,
            plan.batches, operationCount, bounds, world.uid, domainSnapshot, staging, ready, raw, payload, result
        )
        check(captures.putIfAbsent(bundleId, capture) == null) { "Mutation safety bundle id collision" }
        result.whenComplete { _, _ -> captures.remove(bundleId, capture) }
        try {
            io.execute {
                try { Files.createDirectories(staging); Files.createFile(raw); scheduleCapture(capture) }
                catch (failure: Throwable) { fail(capture, failure) }
            }
        } catch (failure: Throwable) { fail(capture, failure) }
        return result
    }

    /** Checks every component and binding before any recovery write begins. */
    override fun validate(
        jobId: UUID,
        bundleId: UUID,
        operation: MutationSafetyOperation,
        areaId: AreaId,
        worldId: WorldId?,
        paperWorldId: UUID,
        regionIds: Collection<RegionId>,
        expectedManifestSha256: String
    ): CompletableFuture<MutationSafetyBundle> = CompletableFuture.supplyAsync({
        val directory = root.resolve(jobId.toString()).resolve(bundleId.toString()).normalize()
        require(directory.startsWith(root) && Files.isDirectory(directory)) { "Safety bundle is missing" }
        val manifestPath = directory.resolve("manifest.json")
        require(Files.isRegularFile(manifestPath) && Files.size(manifestPath) in 1..MAX_MANIFEST_BYTES) { "Safety bundle manifest is missing or oversized" }
        val manifestBytes = Files.readAllBytes(manifestPath)
        require(sha256(manifestBytes) == expectedManifestSha256) { "Safety bundle manifest checksum mismatch" }
        val manifest = mapper.readValue(manifestBytes, MutationSafetyBundleManifestV1::class.java)
        require(manifest.format == FORMAT && manifest.state == "READY" && manifest.snapshotVersion == 1 &&
            manifest.bundleId == bundleId.toString() && manifest.jobId == jobId.toString()) { "Unsupported or mismatched safety bundle manifest" }
        require(manifest.operation == operation && manifest.areaId == areaId.value.toString()) { "Safety bundle operation or Area binding is wrong" }
        require(worldId == null || manifest.worldId == worldId.value.toString()) { "Safety bundle HomeClaim World binding is wrong" }
        require(manifest.paperWorldId == paperWorldId.toString()) { "Safety bundle Paper world binding is wrong" }
        require(manifest.affectedRegionIds == regionIds.map { it.value.toString() }.distinct().sorted()) { "Safety bundle Region set is wrong" }
        require(manifest.domainSnapshot.version == DOMAIN_VERSION) { "Unsupported merge domain snapshot version" }
        require(manifest.domainSnapshot.regions.map { it.regionId }.sorted() == manifest.affectedRegionIds) { "Domain snapshot Region set is incomplete" }
        val domainBytes = mapper.writeValueAsBytes(manifest.domainSnapshot)
        require(sha256(domainBytes) == manifest.domainSnapshotSha256) { "Safety bundle domain snapshot checksum mismatch" }
        val ref = manifest.worldSnapshot
        require(ref.compression == "zstd" && ref.relativeFile == "world.payload.zst") { "Unsupported world safety segment encoding" }
        val payload = directory.resolve(ref.relativeFile).normalize()
        require(payload.parent == directory) { "Safety world payload path escapes its bundle directory" }
        require(Files.isRegularFile(payload)) {
            val entries = runCatching { Files.list(directory).use { paths -> paths.map { it.fileName.toString() }.toList() } }.getOrDefault(emptyList())
            "Safety world payload is missing at $payload; bundle entries=$entries"
        }
        require(Files.size(payload) == ref.storedBytes && sha256(payload) == ref.sha256) { "Safety world payload checksum mismatch" }
        require(ref.blockCount in 1..MAX_BLOCKS && ref.rawBytes in 1..MAX_PAYLOAD_BYTES)
        validatePayload(payload, ref)
        MutationSafetyBundle(
            bundleId, jobId, CorrelationId(UUID.fromString(manifest.correlationId)), operation,
            manifest.actorKind, manifest.actorRef, manifest.worldId, manifest.areaId, paperWorldId,
            manifest.affectedRegionIds, manifest.snapshotVersion, manifest.domainSnapshot, ref,
            expectedManifestSha256, Instant.parse(manifest.createdAt), MutationSafetyBundleState.READY
        )
    }, io)

    /** Idempotently reapplies the verified pre-image in bounded Paper-thread batches. */
    override fun restoreWorld(bundle: MutationSafetyBundle, world: World): CompletableFuture<Long> {
        require(Bukkit.isPrimaryThread()) { "World restore must be started on Paper's server thread" }
        if (world.uid != bundle.paperWorldId) return CompletableFuture.failedFuture(IllegalArgumentException("Recovery world binding changed"))
        val directory = root.resolve(bundle.jobId.toString()).resolve(bundle.bundleId.toString()).normalize()
        val payload = directory.resolve(bundle.worldSnapshot.relativeFile)
        val result = CompletableFuture<Long>()
        try {
            io.execute {
                try {
                    validatePayload(payload, bundle.worldSnapshot)
                    val raw = LimitedDecodedInputStream(ZstdInputStream(BufferedInputStream(Files.newInputStream(payload))), bundle.worldSnapshot.rawBytes)
                    val input = DataInputStream(raw)
                    val task = Restore(bundle, world, payload, raw, input, result)
                    Bukkit.getScheduler().runTask(plugin, Runnable { restoreBatch(task) })
                } catch (failure: Throwable) { result.completeExceptionally(failure) }
            }
        } catch (failure: Throwable) { result.completeExceptionally(failure) }
        return result
    }

    fun shutdown() {
        closed = true
        captures.values.toList().forEach { fail(it, IllegalStateException("Mutation safety capture interrupted by shutdown")) }
        io.shutdown()
        runCatching { if (!io.awaitTermination(5, TimeUnit.SECONDS)) io.shutdownNow() }
    }

    private fun scheduleCapture(task: Capture) {
        var batchIndex = 0
        var opIndex = 0
        var cursor = 0L
        fun next() {
            if (task.failed.get()) return
            try {
                val end = minOf(task.operationCount, cursor + CAPTURE_BATCH)
                val snapshots = ArrayList<PlotBackupFormat.BlockSnapshot>((end - cursor).toInt())
                while (cursor < end) {
                    val batch = task.batches[batchIndex]
                    val op = batch.operations[opIndex++]
                    if (opIndex == batch.operations.size) { batchIndex++; opIndex = 0 }
                    snapshots += captureBlock(task.world, op)
                    cursor++
                }
                if (snapshots.isEmpty()) { io.execute { finalizeCapture(task) }; return }
                io.execute {
                    try {
                        PlotBackupFormat.appendPayload(task.raw, snapshots)
                        Bukkit.getScheduler().runTask(plugin, Runnable { next() })
                    } catch (failure: Throwable) { fail(task, failure) }
                }
            } catch (failure: Throwable) { fail(task, failure) }
        }
        Bukkit.getScheduler().runTask(plugin, Runnable { next() })
    }

    private fun captureBlock(world: World, op: BlockMutationOp): PlotBackupFormat.BlockSnapshot {
        val block = world.getBlockAt(op.x, op.y, op.z)
        val inventory = when (val state = block.state) {
            is Container -> state.snapshotInventory.contents.map { item ->
                item?.takeUnless { it.type.isAir }?.serializeAsBytes()?.also { require(it.size <= PlotBackupFormat.MAX_ITEM_BYTES) }
            }
            is TileState -> throw IllegalStateException("Mutation safety cannot preserve ${block.type.key} TileState at ${op.x},${op.y},${op.z}; mutation was not started")
            else -> null
        }
        return PlotBackupFormat.BlockSnapshot(op.x, op.y, op.z, block.blockData.asString, inventory)
    }

    private fun finalizeCapture(task: Capture) {
        try {
            require(Files.size(task.raw) <= MAX_PAYLOAD_BYTES) { "Mutation safety raw payload is too large" }
            val rawBytes = Files.size(task.raw)
            Files.newInputStream(task.raw).use { input ->
                Files.newOutputStream(task.payload, StandardOpenOption.CREATE_NEW).use { output ->
                    ZstdOutputStream(BufferedOutputStream(output), ZSTD_LEVEL).use { zstd -> input.copyTo(zstd) }
                }
            }
            val storedBytes = Files.size(task.payload)
            val payloadHash = sha256(task.payload)
            val domain = task.domainSnapshot
            val domainHash = sha256(mapper.writeValueAsBytes(domain))
            val ref = WorldSafetySnapshotRef("world.payload.zst", task.operationCount, storedBytes, rawBytes, "zstd", payloadHash, task.bounds)
            validatePayload(task.payload, ref)
            Files.move(task.payload, task.staging.resolve(ref.relativeFile), StandardCopyOption.ATOMIC_MOVE)
            val manifest = MutationSafetyBundleManifestV1(
                bundleId = task.bundleId.toString(), jobId = task.jobId.toString(),
                correlationId = task.correlationId.toString(), operation = task.operation,
                actorKind = task.actor.kind, actorRef = task.actor.stableId,
                worldId = task.worldId.value.toString(), areaId = task.areaId.value.toString(),
                paperWorldId = task.paperWorldId.toString(), affectedRegionIds = task.regions.map { it.id.value.toString() }.sorted(),
                domainSnapshot = domain, domainSnapshotSha256 = domainHash, worldSnapshot = ref, createdAt = Instant.now().toString()
            )
            val manifestBytes = mapper.writeValueAsBytes(manifest)
            require(manifestBytes.size <= MAX_MANIFEST_BYTES) { "Mutation safety domain manifest is oversized" }
            val stagingManifest = task.staging.resolve("manifest.json.part")
            Files.write(stagingManifest, manifestBytes, StandardOpenOption.CREATE_NEW)
            Files.move(stagingManifest, task.staging.resolve("manifest.json"), StandardCopyOption.ATOMIC_MOVE)
            Files.deleteIfExists(task.raw)
            val parent = task.ready.parent
            Files.createDirectories(parent)
            try { Files.move(task.staging, task.ready, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(task.staging, task.ready) }
            task.result.complete(
                MutationSafetyBundle(task.bundleId, task.jobId, task.correlationId, task.operation, task.actor.kind,
                    task.actor.stableId, task.worldId.value.toString(), task.areaId.value.toString(), task.paperWorldId,
                    manifest.affectedRegionIds, 1, domain, ref,
                    sha256(manifestBytes), Instant.parse(manifest.createdAt), MutationSafetyBundleState.READY)
            )
        } catch (failure: Throwable) { fail(task, failure) }
    }

    private fun restoreBatch(task: Restore) {
        if (task.done.get()) return
        io.execute {
            try {
                val remaining = task.bundle.worldSnapshot.blockCount - task.applied
                if (remaining == 0L) {
                    require(task.input.read() == -1) { "Safety payload has trailing decompressed data" }
                    task.decoded.verifyExactSize()
                    task.input.close()
                    if (task.done.compareAndSet(false, true)) task.result.complete(task.applied)
                    return@execute
                }
                val batch = PlotBackupFormat.readBatch(task.input, task.bundle.worldSnapshot.bounds, minOf(RESTORE_BATCH.toLong(), remaining).toInt())
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (task.done.get()) return@Runnable
                    try {
                        for (snapshot in batch) {
                            val block = task.world.getBlockAt(snapshot.x, snapshot.y, snapshot.z)
                            block.setBlockData(Bukkit.createBlockData(snapshot.blockData), false)
                            val items = snapshot.inventoryItems
                            if (items != null) {
                                val state = block.state
                                require(state is Container) { "Safety snapshot expected a container at ${snapshot.x},${snapshot.y},${snapshot.z}" }
                                val inventory = state.snapshotInventory
                                require(inventory.size == items.size) { "Container inventory size changed during recovery" }
                                inventory.contents = items.map { bytes -> bytes?.let(ItemStack::deserializeBytes) }.toTypedArray()
                                check(state.update(true, false)) { "Could not restore safety container at ${snapshot.x},${snapshot.y},${snapshot.z}" }
                                val restoredItems = block.state.let { restored ->
                                    require(restored is Container) { "Restored container vanished at ${snapshot.x},${snapshot.y},${snapshot.z}" }
                                    restored.snapshotInventory.contents.map { item -> item?.takeUnless { it.type.isAir }?.serializeAsBytes() }
                                }
                                check(restoredItems.contentEqualsNullable(items)) { "Restored container contents differ at ${snapshot.x},${snapshot.y},${snapshot.z}" }
                            }
                            check(block.blockData.asString == snapshot.blockData) { "World state did not match safety snapshot at ${snapshot.x},${snapshot.y},${snapshot.z}" }
                            task.applied++
                        }
                        restoreBatch(task)
                    } catch (failure: Throwable) { finishRestore(task, failure) }
                })
            } catch (failure: Throwable) { finishRestore(task, failure) }
        }
    }

    private fun finishRestore(task: Restore, failure: Throwable) {
        if (task.done.compareAndSet(false, true)) {
            runCatching { task.input.close() }
            task.result.completeExceptionally(failure)
        }
    }

    private fun validatePayload(file: Path, ref: WorldSafetySnapshotRef) {
        require(Files.isRegularFile(file) && Files.size(file) == ref.storedBytes) { "Safety snapshot payload size mismatch" }
        require(sha256(file) == ref.sha256) { "Safety snapshot SHA-256 mismatch" }
        LimitedDecodedInputStream(ZstdInputStream(BufferedInputStream(Files.newInputStream(file))), ref.rawBytes).use { decoded ->
            val input = DataInputStream(decoded)
            var read = 0L
            while (read < ref.blockCount) {
                val count = minOf(VALIDATE_BATCH.toLong(), ref.blockCount - read).toInt()
                val batch = PlotBackupFormat.readBatch(input, ref.bounds, count)
                require(batch.size == count)
                read += batch.size
            }
            require(input.read() == -1) { "Safety snapshot payload has trailing data" }
            decoded.verifyExactSize()
        }
    }

    private fun fail(task: Capture, failure: Throwable) {
        if (!task.failed.compareAndSet(false, true)) return
        if (!io.isShutdown) runCatching { io.execute { runCatching { task.staging.toFile().deleteRecursively() } } }
        task.result.completeExceptionally(failure)
    }

    private class LimitedDecodedInputStream(input: InputStream, private val maximumBytes: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) { count++; require(count <= maximumBytes) { "Decoded safety payload exceeds its declared size" } }
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) {
                count = Math.addExact(count, read.toLong())
                require(count <= maximumBytes) { "Decoded safety payload exceeds its declared size" }
            }
            return read
        }
        fun verifyExactSize() {
            require(count == maximumBytes) { "Decoded safety payload size differs from its manifest" }
        }
    }

    private fun sha256(path: Path): String = Files.newInputStream(path).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun List<ByteArray?>.contentEqualsNullable(other: List<ByteArray?>): Boolean =
        size == other.size && indices.all { index ->
            val first = this[index]; val second = other[index]
            if (first == null) second == null else second != null && first.contentEquals(second)
        }

    private data class Capture(
        val jobId: UUID, val bundleId: UUID, val operation: MutationSafetyOperation, val worldId: WorldId,
        val areaId: AreaId, val regions: List<Region>, val plan: PreparedPlotWorldMutation,
        val world: World, val actor: ActionActor, val correlationId: CorrelationId, val batches: List<MutationBatch>,
        val operationCount: Long, val bounds: Bounds, val paperWorldId: UUID, val domainSnapshot: MergeDomainSnapshotV1,
        val staging: Path, val ready: Path, val raw: Path, val payload: Path,
        val result: CompletableFuture<MutationSafetyBundle>, val failed: AtomicBoolean = AtomicBoolean()
    )
    private data class Restore(
        val bundle: MutationSafetyBundle, val world: World, val file: Path,
        val decoded: LimitedDecodedInputStream, val input: DataInputStream,
        val result: CompletableFuture<Long>, var applied: Long = 0, val done: AtomicBoolean = AtomicBoolean()
    )

    companion object {
        const val DOMAIN_VERSION = 1
        private const val FORMAT = "homeclaim-mutation-safety-v1"
        private const val MAX_BLOCKS = 10_000_000L
        private const val MAX_PAYLOAD_BYTES = 1_073_741_824L
        private const val MAX_MANIFEST_BYTES = 1_048_576L
        private const val CAPTURE_BATCH = 256
        private const val RESTORE_BATCH = 256
        private const val VALIDATE_BATCH = 4096
        private const val ZSTD_LEVEL = 3
    }
}
