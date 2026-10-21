package systems.diath.homeclaim.platform.paper.plot.mutation

import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.policy.CorrelationId
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.model.RegionId
import org.bukkit.World
import java.util.concurrent.CompletableFuture
import java.time.Instant
import java.util.UUID

/** Versioned, operation-scoped pre-image for the two state layers changed by merge/unlink. */
data class MutationSafetyBundle(
    val bundleId: UUID,
    val jobId: UUID,
    val correlationId: CorrelationId,
    val operation: MutationSafetyOperation,
    val actorKind: String,
    val actorRef: String?,
    val worldId: String,
    val areaId: String,
    val paperWorldId: UUID,
    val affectedRegionIds: List<String>,
    val snapshotVersion: Int,
    val domainSnapshot: MergeDomainSnapshotV1,
    val worldSnapshot: WorldSafetySnapshotRef,
    val manifestSha256: String,
    val createdAt: Instant,
    val state: MutationSafetyBundleState
)

enum class MutationSafetyOperation { MERGE, UNLINK }
enum class MutationSafetyBundleState { READY }

/** Domain fields changed by merge/unlink. Other Region fields remain owned by the live repository. */
data class MergeDomainSnapshotV1(
    val version: Int = 1,
    val regions: List<MergeRegionDomainStateV1>
) {
    companion object {
        fun capture(regions: Collection<Region>) = MergeDomainSnapshotV1(
            regions = regions.distinctBy { it.id }.sortedBy { it.id.value.toString() }.map { region ->
                MergeRegionDomainStateV1(
                    regionId = region.id.value.toString(),
                    mergeGroupId = region.mergeGroupId?.value?.toString(),
                    home = region.home?.let { home -> RegionHomeSnapshotV1(home.x, home.y, home.z, home.yaw, home.pitch) }
                )
            }
        )
    }
}

data class MergeRegionDomainStateV1(
    val regionId: String,
    val mergeGroupId: String?,
    val home: RegionHomeSnapshotV1?
)

data class RegionHomeSnapshotV1(val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)

/** The checksum covers the compressed payload bytes, matching HCBK's stored-payload convention. */
data class WorldSafetySnapshotRef(
    val relativeFile: String,
    val blockCount: Long,
    val storedBytes: Long,
    val rawBytes: Long,
    val compression: String,
    val sha256: String,
    val bounds: systems.diath.homeclaim.core.model.Bounds
)

data class MutationSafetyBundleManifestV1(
    val format: String = "homeclaim-mutation-safety-v1",
    val state: String = "READY",
    val snapshotVersion: Int = 1,
    val bundleId: String,
    val jobId: String,
    val correlationId: String,
    val operation: MutationSafetyOperation,
    val actorKind: String,
    val actorRef: String?,
    val worldId: String,
    val areaId: String,
    val paperWorldId: String,
    val affectedRegionIds: List<String>,
    val domainSnapshot: MergeDomainSnapshotV1,
    val domainSnapshotSha256: String,
    val worldSnapshot: WorldSafetySnapshotRef,
    val createdAt: String
)

interface MutationSafetyBundleService {
    fun create(
        jobId: UUID, operation: MutationSafetyOperation, worldId: WorldId, areaId: AreaId,
        regions: Collection<Region>, plan: PreparedPlotWorldMutation, world: World,
        actor: ActionActor, correlationId: CorrelationId, bundleId: UUID
    ): CompletableFuture<MutationSafetyBundle>

    fun validate(
        jobId: UUID, bundleId: UUID, operation: MutationSafetyOperation, areaId: AreaId, worldId: WorldId?,
        paperWorldId: UUID, regionIds: Collection<RegionId>, expectedManifestSha256: String
    ): CompletableFuture<MutationSafetyBundle>

    fun restoreWorld(bundle: MutationSafetyBundle, world: World): CompletableFuture<Long>
}
