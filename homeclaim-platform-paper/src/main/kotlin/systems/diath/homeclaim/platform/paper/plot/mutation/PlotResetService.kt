package systems.diath.homeclaim.platform.paper.plot.mutation

import systems.diath.homeclaim.core.mutation.MutationJobInfo
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.policy.CorrelationId

/**
 * Separate, potentially heavier reset path for restoring a plot's interior.
 * This is intentionally decoupled from the lightweight border mutation service.
 */
enum class PlotResetReason {
    MANUAL,
    DELETE,
    UNCLAIM
}

interface PlotResetService {
    fun queueReset(region: Region, reason: PlotResetReason = PlotResetReason.MANUAL): Boolean

    /**
     * Queue a reset and report its terminal state on the server thread. Implementations that
     * cannot expose job lifecycle may keep the compatibility behavior below.
     */
    fun queueReset(region: Region, reason: PlotResetReason, onComplete: (PlotResetCompletion) -> Unit): Boolean {
        return queueReset(region, reason, CorrelationId.newId(), onComplete)
    }

    fun queueReset(
        region: Region,
        reason: PlotResetReason,
        correlationId: CorrelationId,
        onComplete: (PlotResetCompletion) -> Unit
    ): Boolean {
        val queued = queueReset(region, reason)
        if (queued) onComplete(PlotResetCompletion(PlotResetCompletionStatus.COMPLETED, 0, false))
        return queued
    }

    fun cancelPendingJobs(worldName: String? = null): Int = 0

    fun activeJobDiagnostics(worldName: String? = null): List<String> = emptyList()

    fun recoveryRequiredDiagnostics(worldName: String? = null): List<String> = emptyList()

    fun activeJobs(worldName: String? = null): List<PlotJobSnapshot> = emptyList()

    fun activeJobInfo(worldName: String? = null): List<MutationJobInfo> = emptyList()

    /** Complete/cancel platform jobs while Paper's server thread is still available. */
    fun shutdown() {
        cancelPendingJobs()
    }
}

enum class PlotResetCompletionStatus { COMPLETED, FAILED, CANCELLED }

data class PlotResetCompletion(
    val status: PlotResetCompletionStatus,
    val completedColumns: Int,
    val partialMutation: Boolean,
    val error: String? = null
)

internal fun PlotResetCompletion.persistentJobStatus(): PlotMutationJobStatus = when {
    status == PlotResetCompletionStatus.COMPLETED -> PlotMutationJobStatus.COMPLETED
    partialMutation -> PlotMutationJobStatus.RECOVERY_REQUIRED
    status == PlotResetCompletionStatus.CANCELLED -> PlotMutationJobStatus.CANCELLED
    else -> PlotMutationJobStatus.FAILED
}

object NoOpPlotResetService : PlotResetService {
    override fun queueReset(region: Region, reason: PlotResetReason): Boolean = false
}
