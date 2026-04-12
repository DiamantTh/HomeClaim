package systems.diath.homeclaim.core.mutation

/**
 * Platform-neutral mutation backend abstraction.
 *
 * Implementations can be Paper+FAWE, Paper native, Fabric native, or NeoForge.
 */
enum class MutationReason {
    CLAIM,
    UNCLAIM,
    MERGE,
    RESET,
    IMPORT,
    ADMIN
}

enum class MutationPriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL
}

data class BlockMutationOp(
    val x: Int,
    val y: Int,
    val z: Int,
    val blockStateId: String
)

data class MutationBatch(
    val id: String,
    val world: String,
    val reason: MutationReason,
    val priority: MutationPriority = MutationPriority.NORMAL,
    val allowAsyncPlan: Boolean = true,
    val operations: List<BlockMutationOp>
)

data class MutationCapabilities(
    val supportsAsyncApply: Boolean,
    val supportsChunkBatching: Boolean,
    val supportsUndo: Boolean
)

data class MutationTicket(
    val id: String,
    val backend: String
)

data class MutationJobInfo(
    val ticketId: String,
    val world: String,
    val reason: MutationReason,
    val queuedMillis: Long,
    val running: Boolean,
    val cancelRequested: Boolean
)

interface WorldMutationBackend {
    val backendId: String

    fun capabilities(): MutationCapabilities

    fun submit(batch: MutationBatch): MutationTicket

    fun cancel(ticketId: String): Boolean

    fun activeJobs(world: String? = null): List<MutationJobInfo>
}
