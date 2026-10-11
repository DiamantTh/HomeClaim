package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionRole
import systems.diath.homeclaim.core.model.RegionRoles
import systems.diath.homeclaim.core.model.RegionOwnership
import systems.diath.homeclaim.core.policy.ActionContext
import systems.diath.homeclaim.core.policy.ActionKey
import systems.diath.homeclaim.core.policy.ActionRequest
import systems.diath.homeclaim.core.policy.ActionResourceRef
import systems.diath.homeclaim.core.policy.ActionEndpoint
import systems.diath.homeclaim.core.policy.CorrelationId
import systems.diath.homeclaim.core.policy.HomeClaimOperationActions
import systems.diath.homeclaim.core.policy.ActionActor

/** Canonical role mutation path; updates the same region repository/cache used by policy resolution. */
class RegionRoleService(
    private val regions: RegionService,
    private val auditService: AuditService? = null,
    private val authorizer: RegionMutationAuthorizer = RegionMutationAuthorizer.SYSTEM_ONLY
) : RoleService {
    override fun mutateRole(request: RoleMutationRequest): OperationResult<Region> {
        val region = regions.getRegionById(request.regionId)
        val context = RegionMutationContext(
            actor = request.actor,
            platform = request.platform,
            eventName = "role_${request.kind.name.lowercase()}",
            correlationId = request.correlationId,
            parentCorrelationId = request.parentCorrelationId,
            attributes = mapOf(
                "targetPlayerId" to request.targetPlayerId.toString(),
                "role" to request.role.name,
                "mutation" to request.kind.name
            )
        )
        val actionRequest = context.actionRequest(
            HomeClaimOperationActions.ROLE_CHANGE,
            request.regionId,
            region
        )
        auditService?.append(AuditEntries.operationResult(actionRequest, AuditStatus.ATTEMPTED, targetCount = 1))

        fun reject(status: OperationStatus, detail: String): OperationResult<Region> {
            auditService?.append(AuditEntries.operationResult(
                actionRequest,
                if (status == OperationStatus.DENIED) AuditStatus.DENIED else AuditStatus.FAILED,
                error = detail,
                targetCount = 1
            ))
            return OperationResult.rejected(status, detail)
        }

        if (region == null) return reject(OperationStatus.NOT_FOUND, "REGION_NOT_FOUND")
        if (RegionOwnership.isUnclaimed(request.targetPlayerId)) return reject(OperationStatus.INVALID, "UNCLAIMED_OWNER_CANNOT_HAVE_REGION_ROLE")
        if (request.role == RegionRole.OWNER || request.role == RegionRole.VISITOR) {
            return reject(OperationStatus.INVALID, "USE_OWNERSHIP_OR_ROLE_REMOVAL_OPERATION")
        }
        if (!authorizer.isAuthorized(request.actor, region, RegionMutationCapability.ROLE_CHANGE)) {
            return reject(OperationStatus.DENIED, "ROLE_CHANGE_DENIED")
        }
        if (request.kind == RoleMutationKind.ADD && request.targetPlayerId == region.owner) {
            return reject(OperationStatus.CONFLICT, "REGION_OWNER_CANNOT_BE_ASSIGNED_ANOTHER_ROLE")
        }
        if (request.kind == RoleMutationKind.ADD && request.targetPlayerId == (request.actor as? ActionActor.Player)?.playerId && request.role == RegionRole.BANNED) {
            return reject(OperationStatus.INVALID, "ACTOR_CANNOT_BAN_SELF")
        }

        val currentlyAssigned = when (request.role) {
            RegionRole.TRUSTED -> request.targetPlayerId in region.roles.trusted
            RegionRole.MEMBER -> request.targetPlayerId in region.roles.members
            RegionRole.BANNED -> request.targetPlayerId in region.roles.banned
            RegionRole.OWNER, RegionRole.VISITOR -> false
        }
        if ((request.kind == RoleMutationKind.ADD && currentlyAssigned) ||
            (request.kind == RoleMutationKind.REMOVE && !currentlyAssigned)) {
            auditService?.append(AuditEntries.operationResult(
                actionRequest.copy(context = actionRequest.context.copy(
                    attributes = actionRequest.context.attributes + ("changed" to "false")
                )),
                AuditStatus.COMPLETED,
                targetCount = 1
            ))
            return OperationResult.success(region, "UNCHANGED")
        }

        val cleared = region.roles.copy(
            trusted = region.roles.trusted - request.targetPlayerId,
            members = region.roles.members - request.targetPlayerId,
            banned = region.roles.banned - request.targetPlayerId
        )
        val roles = when {
            request.kind == RoleMutationKind.REMOVE -> cleared
            request.role == RegionRole.TRUSTED -> cleared.copy(trusted = cleared.trusted + request.targetPlayerId)
            request.role == RegionRole.MEMBER -> cleared.copy(members = cleared.members + request.targetPlayerId)
            else -> cleared.copy(banned = cleared.banned + request.targetPlayerId)
        }
        val updated = region.copy(roles = roles)
        try {
            MutationAuditScope.withinOperation { regions.updateRegion(updated) }
        } catch (failure: Exception) {
            return reject(OperationStatus.FAILED, failure.message ?: "ROLE_PERSISTENCE_FAILED")
        }
        auditService?.append(AuditEntries.operationResult(
            actionRequest.copy(context = actionRequest.context.copy(
                attributes = actionRequest.context.attributes + ("changed" to "true")
            )),
            AuditStatus.COMPLETED,
            targetCount = 1
        ))
        return OperationResult.success(updated)
    }

}
