package systems.diath.homeclaim.platform.paper.plot.mutation

import systems.diath.homeclaim.core.model.RegionId
import java.util.UUID

enum class PlotMutationRecoveryAction { RESTORE_TARGET, RESTORE_BOTH, RESTORE_OPERATION_ORIGINAL, ACKNOWLEDGE }

data class PlotMutationRecoveryTarget(val regionId: RegionId, val backupId: UUID)

sealed interface PlotMutationRecoveryPlanResult {
    data class Ready(val action: PlotMutationRecoveryAction, val targets: List<PlotMutationRecoveryTarget>) : PlotMutationRecoveryPlanResult
    data class Rejected(val reason: String) : PlotMutationRecoveryPlanResult
}

/** Maps only durable, operation-specific backup references to conservative recovery actions. */
object PlotMutationRecoveryPlanner {
    fun plan(job: PlotMutationJobRecord, action: PlotMutationRecoveryAction): PlotMutationRecoveryPlanResult {
        if (job.status != PlotMutationJobStatus.RECOVERY_REQUIRED) {
            return PlotMutationRecoveryPlanResult.Rejected("job_not_recovery_required")
        }
        if (action == PlotMutationRecoveryAction.ACKNOWLEDGE) {
            return PlotMutationRecoveryPlanResult.Ready(action, emptyList())
        }
        val fields = job.details.orEmpty().lineSequence().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }.toMap()
        fun id(name: String) = fields[name]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        fun target(regionKey: String, backupKey: String, fallback: RegionId? = null): PlotMutationRecoveryTarget? {
            val region = id(regionKey)?.let(::RegionId) ?: fallback ?: return null
            val backup = id(backupKey) ?: return null
            return PlotMutationRecoveryTarget(region, backup)
        }
        val plan = when (job.operation) {
            "MERGE", "UNLINK" -> if (action == PlotMutationRecoveryAction.RESTORE_OPERATION_ORIGINAL &&
                id("safetyBundleId") != null && job.regionIds.isNotEmpty()) emptyList() else null
            "COPY" -> if (action == PlotMutationRecoveryAction.RESTORE_TARGET) {
                listOfNotNull(target("targetRegion", "targetSafetyBackup"))
            } else null
            "MOVE", "SWAP" -> if (action == PlotMutationRecoveryAction.RESTORE_BOTH) {
                listOfNotNull(target("sourceRegion", "sourceBackup"), target("targetRegion", "targetSafetyBackup"))
                    .takeIf { it.size == 2 }
            } else null
            else -> if (action == PlotMutationRecoveryAction.RESTORE_TARGET &&
                (job.operation.startsWith("RESET_") || job.operation == "RESTORE")) {
                val region = id("regionId") ?: id("sourceRegion") ?: job.regionIds.firstOrNull()?.value
                // A restore's own pre-image is the correct recovery target. For old jobs
                // created before restore-safety existed, retain the historical ability to
                // reapply the requested backup, but never mislabel it as a pre-image.
                val backup = if (job.operation == "RESTORE") id("restoreSafetyBackupId") ?: id("backupId") else id("backupId")
                if (region == null || backup == null) null else listOf(PlotMutationRecoveryTarget(RegionId(region), backup))
            } else null
        }
        return if (plan == null) PlotMutationRecoveryPlanResult.Rejected("action_not_applicable_or_backup_missing")
        else PlotMutationRecoveryPlanResult.Ready(action, plan)
    }
}
