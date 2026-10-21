package systems.diath.homeclaim.platform.paper.plot.mutation

import systems.diath.homeclaim.core.mutation.MutationReason
import systems.diath.homeclaim.core.mutation.MutationBatch
import systems.diath.homeclaim.core.model.Region
import java.util.UUID
import java.util.concurrent.CompletableFuture

data class PreparedPlotWorldMutation(val worldName: String, val worldId: UUID, val batches: List<MutationBatch>)

/** Merge/unlink expose the exact batches used by their Paper writer so safety capture cannot drift. */
interface OperationScopedPlotMutationService {
    fun prepareMergeMutation(regions: Collection<Region>, reason: MutationReason = MutationReason.MERGE): PreparedPlotWorldMutation
    fun prepareUnlinkMutation(regions: Collection<Region>, createRoads: Boolean, reason: MutationReason = MutationReason.UNCLAIM): PreparedPlotWorldMutation
    fun applyPreparedMutation(plan: PreparedPlotWorldMutation, label: String): CompletableFuture<Unit>
}
