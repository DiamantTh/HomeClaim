package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionRole
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.CorrelationId

interface RoleService {
    /** Structured actor-aware role operation used by current adapters. */
    fun mutateRole(request: RoleMutationRequest): OperationResult<Region> =
        OperationResult.rejected(OperationStatus.FAILED, "ACTOR_AWARE_ROLE_OPERATION_UNAVAILABLE")

}

enum class RoleMutationKind { ADD, REMOVE }

data class RoleMutationRequest(
    val actor: ActionActor,
    val regionId: RegionId,
    val targetPlayerId: PlayerId,
    val role: RegionRole,
    val kind: RoleMutationKind,
    val platform: String,
    val correlationId: CorrelationId = CorrelationId.newId(),
    val parentCorrelationId: CorrelationId? = null
)
