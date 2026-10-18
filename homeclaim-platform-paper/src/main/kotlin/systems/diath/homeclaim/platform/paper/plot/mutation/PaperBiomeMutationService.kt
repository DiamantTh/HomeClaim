package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.block.Biome
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.policy.CorrelationId
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.service.WorldAreaService
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded Paper biome writer. The block mutation backend cannot represent biome
 * cells, so biome jobs use the same persistent journal directly. Progress is
 * diagnostic only; it is never used as a resume cursor.
 */
class PaperBiomeMutationService(
    private val plugin: JavaPlugin,
    private val worldAreaService: WorldAreaService,
    private val jobStore: PlotMutationJobStore? = null,
    private val columnsPerTick: Int = 256
) {
    private val activeJobs = ConcurrentHashMap<UUID, () -> Unit>()

    init { require(columnsPerTick > 0) }

    fun apply(regions: Collection<Region>, biome: Biome): CompletableFuture<Unit> =
        apply(regions, biome, CorrelationId.newId(), null)

    fun apply(regions: Collection<Region>, biome: Biome, correlationId: CorrelationId): CompletableFuture<Unit> =
        apply(regions, biome, correlationId, null)

    fun apply(regions: Collection<Region>, biome: Biome, correlationId: CorrelationId, actor: ActionActor?): CompletableFuture<Unit> {
        if (regions.isEmpty()) return CompletableFuture.completedFuture(Unit)
        val first = regions.first()
        val world = resolveWorld(first)
            ?: return CompletableFuture.failedFuture(IllegalStateException("World binding unavailable for biome mutation"))
        if (regions.any { resolveWorld(it)?.uid != world.uid }) {
            return CompletableFuture.failedFuture(IllegalArgumentException("Biome mutation regions must share one Paper world"))
        }
        val totalColumns = regions.sumOf { region ->
            (region.bounds.maxX - region.bounds.minX + 1) * (region.bounds.maxZ - region.bounds.minZ + 1)
        }
        val jobId = UUID.randomUUID()
        val journal = PlotMutationJobRecord(
            id = jobId,
            correlationId = correlationId,
            operation = "BIOME",
            worldId = worldAreaService.getArea(first.areaId)?.worldId,
            areaId = first.areaId,
            worldName = world.name,
            regionIds = regions.map { it.id },
            backend = "paper-biome-batch",
            status = PlotMutationJobStatus.QUEUED,
            totalUnits = totalColumns,
            details = "format=homeclaim-biome-v1\nphase=APPLYING_BIOME\n" +
                "paperWorldId=${world.uid}\nactorKind=${actor?.kind ?: "UNKNOWN"}\nactorRef=${actor?.stableId ?: ""}"
        )
        try {
            jobStore?.create(journal)
        } catch (failure: Throwable) {
            return CompletableFuture.failedFuture(IllegalStateException("Could not persist biome job before mutation", failure))
        }

        val iterator = regions.asSequence().flatMap { region ->
            (region.bounds.minX..region.bounds.maxX).asSequence().flatMap { x ->
                (region.bounds.minZ..region.bounds.maxZ).asSequence().map { z -> x to z }
            }
        }.iterator()
        val result = CompletableFuture<Unit>()
        val completed = AtomicBoolean(false)
        var processed = 0
        var task: BukkitTask? = null

        fun finish(success: Boolean, failure: Throwable? = null) {
            if (!completed.compareAndSet(false, true)) return
            task?.cancel()
            activeJobs.remove(jobId)
            val status = when {
                success -> PlotMutationJobStatus.COMPLETED
                processed > 0 -> PlotMutationJobStatus.RECOVERY_REQUIRED
                failure is CancellationException -> PlotMutationJobStatus.CANCELLED
                else -> PlotMutationJobStatus.FAILED
            }
            val detail = failure?.message ?: failure?.javaClass?.simpleName
            val journalFailure = runCatching {
                jobStore?.finish(jobId, status, processed, totalColumns, detail)
            }.exceptionOrNull()
            if (success && journalFailure != null) {
                result.completeExceptionally(IllegalStateException("Biome mutation completed but its journal could not be finalized", journalFailure))
            } else if (success) {
                result.complete(Unit)
            } else {
                result.completeExceptionally(failure ?: IllegalStateException("Biome mutation failed"))
            }
        }

        try {
            Bukkit.getScheduler().runTask(plugin, Runnable {
                try {
                    jobStore?.markRunning(jobId)
                    task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
                        try {
                            repeat(columnsPerTick) {
                                if (!iterator.hasNext()) {
                                    finish(success = true)
                                    return@Runnable
                                }
                                val (x, z) = iterator.next()
                                world.setBiome(x, z, biome)
                                processed++
                            }
                            if (processed % PERSIST_PROGRESS_INTERVAL < columnsPerTick) {
                                jobStore?.updateProgress(jobId, processed, totalColumns)
                            }
                        } catch (failure: Throwable) {
                            finish(success = false, failure = failure)
                        }
                    }, 1L, 1L)
                    activeJobs[jobId] = { finish(false, CancellationException("Biome mutation cancelled by plugin shutdown")) }
                } catch (failure: Throwable) {
                    finish(success = false, failure = failure)
                }
            })
        } catch (failure: Throwable) {
            finish(success = false, failure = failure)
        }
        return result
    }

    fun shutdown() {
        activeJobs.values.toList().forEach { cancel -> runCatching(cancel) }
    }

    private fun resolveWorld(region: Region): World? {
        val area = worldAreaService.getArea(region.areaId) ?: return null
        val homeWorld = worldAreaService.getWorld(area.worldId) ?: return null
        return homeWorld.platformWorldId?.let(Bukkit::getWorld) ?: Bukkit.getWorld(homeWorld.name)
    }

    private companion object {
        const val PERSIST_PROGRESS_INTERVAL = 1024
    }
}
