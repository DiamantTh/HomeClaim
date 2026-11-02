package systems.diath.homeclaim.platform.paper.plot.mutation

import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.WorldId
import java.util.UUID
import java.security.MessageDigest

enum class PlotTransferOperation { COPY, MOVE, SWAP }

enum class PlotTransferRejection {
    SAME_REGION,
    SOURCE_UNCLAIMED,
    TARGET_UNCLAIMED,
    AREA_MISMATCH,
    WORLD_MISMATCH,
    WORLD_BINDING_MISMATCH,
    MERGED_REGION_UNSUPPORTED,
    COMPONENTS_UNSUPPORTED,
    DIMENSION_MISMATCH,
    OVERLAPPING_BOUNDS,
    INVALID_BOUNDS
}

data class PlotTransferPlan(
    val operation: PlotTransferOperation,
    val source: Region,
    val target: Region,
    val worldId: WorldId,
    val paperWorldId: UUID,
    val blockCount: Long
) {
    val regionIds = listOf(source.id, target.id).sortedBy { it.value.toString() }
}

sealed interface PlotTransferPlanResult {
    data class Ready(val plan: PlotTransferPlan) : PlotTransferPlanResult
    data class Rejected(val reason: PlotTransferRejection) : PlotTransferPlanResult
}

/** Pure preflight for the deliberately narrow, same-world, unmerged transfer scope. */
object PlotTransferPlanner {
    fun plan(
        operation: PlotTransferOperation,
        source: Region,
        target: Region,
        sourceWorldId: WorldId?,
        targetWorldId: WorldId?,
        sourcePaperWorldId: UUID?,
        targetPaperWorldId: UUID?,
        sourceHasComponents: Boolean = false,
        targetHasComponents: Boolean = false
    ): PlotTransferPlanResult {
        if (source.id == target.id) return PlotTransferPlanResult.Rejected(PlotTransferRejection.SAME_REGION)
        if (systems.diath.homeclaim.core.model.RegionOwnership.isUnclaimed(source.owner)) {
            return PlotTransferPlanResult.Rejected(PlotTransferRejection.SOURCE_UNCLAIMED)
        }
        if (systems.diath.homeclaim.core.model.RegionOwnership.isUnclaimed(target.owner)) {
            return PlotTransferPlanResult.Rejected(PlotTransferRejection.TARGET_UNCLAIMED)
        }
        if (source.areaId != target.areaId) return PlotTransferPlanResult.Rejected(PlotTransferRejection.AREA_MISMATCH)
        if (sourceWorldId == null || targetWorldId == null || sourceWorldId != targetWorldId) {
            return PlotTransferPlanResult.Rejected(PlotTransferRejection.WORLD_MISMATCH)
        }
        if (sourcePaperWorldId == null || targetPaperWorldId == null || sourcePaperWorldId != targetPaperWorldId) {
            return PlotTransferPlanResult.Rejected(PlotTransferRejection.WORLD_BINDING_MISMATCH)
        }
        if (source.mergeGroupId != null || target.mergeGroupId != null) {
            return PlotTransferPlanResult.Rejected(PlotTransferRejection.MERGED_REGION_UNSUPPORTED)
        }
        if (sourceHasComponents || targetHasComponents) {
            return PlotTransferPlanResult.Rejected(PlotTransferRejection.COMPONENTS_UNSUPPORTED)
        }
        val sourceSize = dimensions(source)
        val targetSize = dimensions(target)
        if (sourceSize == null || targetSize == null) return PlotTransferPlanResult.Rejected(PlotTransferRejection.INVALID_BOUNDS)
        if (sourceSize != targetSize) return PlotTransferPlanResult.Rejected(PlotTransferRejection.DIMENSION_MISMATCH)
        if (source.bounds.minX <= target.bounds.maxX && source.bounds.maxX >= target.bounds.minX &&
            source.bounds.minZ <= target.bounds.maxZ && source.bounds.maxZ >= target.bounds.minZ) {
            return PlotTransferPlanResult.Rejected(PlotTransferRejection.OVERLAPPING_BOUNDS)
        }
        val count = try {
            Math.multiplyExact(Math.multiplyExact(sourceSize.first, sourceSize.second), sourceSize.third)
        } catch (_: ArithmeticException) {
            return PlotTransferPlanResult.Rejected(PlotTransferRejection.INVALID_BOUNDS)
        }
        if (count !in 1..PlotBackupFormat.MAX_BLOCKS) return PlotTransferPlanResult.Rejected(PlotTransferRejection.INVALID_BOUNDS)
        return PlotTransferPlanResult.Ready(PlotTransferPlan(operation, source, target, sourceWorldId, sourcePaperWorldId, count))
    }

    private fun dimensions(region: Region): Triple<Long, Long, Long>? {
        val bounds = region.bounds
        val x = bounds.maxX.toLong() - bounds.minX + 1
        val y = bounds.maxY.toLong() - bounds.minY + 1
        val z = bounds.maxZ.toLong() - bounds.minZ + 1
        return if (x > 0 && y > 0 && z > 0) Triple(x, y, z) else null
    }
}

data class PlotTransferJournalDetails(
    val operation: PlotTransferOperation,
    val sourceRegionId: UUID,
    val targetRegionId: UUID,
    val sourceBackupId: UUID? = null,
    val targetBackupId: UUID? = null,
    val phase: String,
    val areaId: AreaId? = null,
    val worldId: WorldId? = null,
    val paperWorldId: UUID? = null,
    val actorKind: String? = null,
    val actorRef: String? = null,
    val sourceChanged: Boolean = false,
    val targetChanged: Boolean = false,
    val completionProof: String? = null,
    val sourceDomainSha256: String? = null,
    val targetDomainSha256: String? = null
) {
    /** Stable line format for the job diagnostics column; values are enum names or UUIDs. */
    fun encode(): String = buildString {
        appendLine("format=homeclaim-transfer-v1")
        appendLine("operation=${operation.name}")
        appendLine("sourceRegion=$sourceRegionId")
        appendLine("targetRegion=$targetRegionId")
        sourceBackupId?.let { appendLine("sourceBackup=$it") }
        targetBackupId?.let { appendLine("targetSafetyBackup=$it") }
        areaId?.let { appendLine("areaId=${it.value}") }
        worldId?.let { appendLine("worldId=${it.value}") }
        paperWorldId?.let { appendLine("paperWorldId=$it") }
        actorKind?.let { appendLine("actorKind=$it") }
        actorRef?.let { appendLine("actorRef=$it") }
        appendLine("sourceChanged=$sourceChanged")
        appendLine("targetChanged=$targetChanged")
        sourceDomainSha256?.let { appendLine("sourceDomainSha256=$it") }
        targetDomainSha256?.let { appendLine("targetDomainSha256=$it") }
        completionProof?.let { appendLine("completion=$it") }
        append("phase=$phase")
    }
}

/** Stable fingerprint for the Region domain record, excluding runtime-only world contents. */
fun regionDomainFingerprint(region: Region): String {
    val canonical = listOf(
        region.id.value.toString(), region.areaId.value.toString(), region.world, region.shape.toString(),
        region.bounds.toString(), region.owner.toString(),
        region.roles.trusted.map { it.toString() }.sorted().joinToString(","),
        region.roles.members.map { it.toString() }.sorted().joinToString(","),
        region.roles.banned.map { it.toString() }.sorted().joinToString(","),
        region.flags.entries.sortedBy { it.key.toString() }.joinToString("|") { "${it.key}=${it.value}" },
        region.limits.entries.sortedBy { it.key.toString() }.joinToString("|") { "${it.key}=${it.value}" },
        region.metadata.toSortedMap().entries.joinToString("|") { "${it.key}=${it.value}" },
        region.home.toString(), region.mergeGroupId?.value?.toString().orEmpty(), region.price.toString()
    ).joinToString("\n")
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

sealed interface PlotTransferResult {
    data class Completed(val jobId: UUID, val sourceBackupId: UUID, val targetBackupId: UUID) : PlotTransferResult
    data class Conflict(val reason: String) : PlotTransferResult
    data class Failed(val jobId: UUID, val reason: String, val recoveryRequired: Boolean) : PlotTransferResult
}
