package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.Bukkit
import systems.diath.homeclaim.core.mutation.MutationBatch
import systems.diath.homeclaim.core.mutation.MutationCapabilities
import systems.diath.homeclaim.core.mutation.MutationJobInfo
import systems.diath.homeclaim.core.mutation.MutationReason
import systems.diath.homeclaim.core.mutation.MutationTicket
import systems.diath.homeclaim.core.mutation.WorldMutationBackend

internal class PaperSynchronousWorldMutationBackend(
    private val jobRegistry: PlotJobRegistry = PlotJobRegistry(),
    private val maxConcurrentMutationsForWorld: (worldName: String) -> Int = { Int.MAX_VALUE }
) : WorldMutationBackend {
    override val backendId: String = "paper-sync"
    
    // Expose registry for persistence operations
    internal fun getJobRegistry(): PlotJobRegistry = jobRegistry

    override fun capabilities(): MutationCapabilities {
        return MutationCapabilities(
            supportsAsyncApply = false,
            supportsChunkBatching = true,
            supportsUndo = true  // Sync execution allows undo-stack
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

        return try {
            if (!handle.isCancellationRequested(DEFAULT_TIMEOUT_MILLIS)) {
                PlotMutationExecutor.apply(world, batch)
            }
            MutationTicket(id = batch.id, backend = backendId)
        } catch (failure: Throwable) {
            jobRegistry.markFailed(batch.world, kind)
            throw failure
        } finally {
            handle.close()
        }
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

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L
    }
}