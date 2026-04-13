package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.plugin.java.JavaPlugin
import systems.diath.homeclaim.core.mutation.MutationBatch
import systems.diath.homeclaim.core.mutation.MutationCapabilities
import systems.diath.homeclaim.core.mutation.MutationJobInfo
import systems.diath.homeclaim.core.mutation.MutationReason
import systems.diath.homeclaim.core.mutation.MutationTicket
import systems.diath.homeclaim.core.mutation.WorldMutationBackend
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class FoliaRegionScheduledWorldMutationBackend(
    private val plugin: JavaPlugin,
    private val jobRegistry: PlotJobRegistry = PlotJobRegistry(),
    private val maxConcurrentMutationsForWorld: (worldName: String) -> Int = { Int.MAX_VALUE }
) : WorldMutationBackend {
    override val backendId: String = "folia-region-scheduler"

    override fun capabilities(): MutationCapabilities {
        return MutationCapabilities(
            supportsAsyncApply = true,
            supportsChunkBatching = true,
            supportsUndo = false
        )
    }

    override fun submit(batch: MutationBatch): MutationTicket {
        val kind = if (batch.reason == MutationReason.RESET) PlotJobRegistry.JobKind.RESET else PlotJobRegistry.JobKind.MUTATION
        val limit = if (kind == PlotJobRegistry.JobKind.MUTATION) maxConcurrentMutationsForWorld(batch.world) else Int.MAX_VALUE
        val handle = jobRegistry.tryAcquire(
            key = batch.id,
            world = batch.world,
            kind = kind,
            reason = batch.reason,
            maxConcurrentPerWorld = limit,
            timeoutMillis = DEFAULT_TIMEOUT_MILLIS
        ) ?: throw IllegalStateException("submit rejected for batch ${batch.id} (duplicate or world limit reached)")

        val world = Bukkit.getWorld(batch.world)
            ?: run {
                handle.close()
                throw IllegalStateException("World not loaded for mutation batch: ${batch.world}")
            }

        if (batch.operations.isEmpty()) {
            handle.close()
            return MutationTicket(id = batch.id, backend = backendId)
        }

        val operationsByChunk = batch.operations.groupBy { (it.x shr 4) to (it.z shr 4) }
        val remainingBatches = AtomicInteger(operationsByChunk.size)
        operationsByChunk.forEach { (chunk, operations) ->
            val chunkBatch = batch.copy(id = "${batch.id}:chunk:${chunk.first}:${chunk.second}", operations = operations)
            val anchor = Location(world, (chunk.first shl 4).toDouble(), world.minHeight.toDouble(), (chunk.second shl 4).toDouble())
            scheduleChunkBatch(anchor, chunkBatch, world, kind, handle, remainingBatches)
        }

        return MutationTicket(id = batch.id, backend = backendId)
    }

    override fun cancel(ticketId: String): Boolean {
        return jobRegistry.requestCancel(ticketId, DEFAULT_TIMEOUT_MILLIS)
    }

    override fun activeJobs(world: String?): List<MutationJobInfo> {
        return jobRegistry.mutationJobInfo(
            world = world,
            timeoutMillis = DEFAULT_TIMEOUT_MILLIS,
            defaultReason = MutationReason.ADMIN
        )
    }

    private fun scheduleChunkBatch(
        anchor: Location,
        batch: MutationBatch,
        world: org.bukkit.World,
        kind: PlotJobRegistry.JobKind,
        handle: PlotJobRegistry.JobHandle,
        remainingBatches: AtomicInteger,
        attempt: Int = 0
    ) {
        Bukkit.getRegionScheduler().run(plugin, anchor) { _ ->
            if (handle.isCancellationRequested(DEFAULT_TIMEOUT_MILLIS)) {
                finishChunkBatch(remainingBatches, handle)
                return@run
            }

            val failure = runCatching {
                PlotMutationExecutor.apply(world, batch)
            }.exceptionOrNull()

            if (failure != null && attempt < MAX_BATCH_RETRIES) {
                Bukkit.getAsyncScheduler().runDelayed(plugin, { _ ->
                    scheduleChunkBatch(anchor, batch, world, kind, handle, remainingBatches, attempt + 1)
                }, RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
                return@run
            }

            if (failure != null) {
                jobRegistry.markFailed(batch.world, kind)
                plugin.logger.warning("Folia mutation backend batch failed for ${batch.id}: ${failure.message}")
            }
            finishChunkBatch(remainingBatches, handle)
        }
    }

    private fun finishChunkBatch(remainingBatches: AtomicInteger, handle: PlotJobRegistry.JobHandle) {
        if (remainingBatches.decrementAndGet() == 0) {
            handle.close()
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L
        const val MAX_BATCH_RETRIES = 1
        const val RETRY_DELAY_MS = 50L
    }
}