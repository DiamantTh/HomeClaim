package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.Component
import systems.diath.homeclaim.core.model.ComponentConfig
import systems.diath.homeclaim.core.model.ComponentId
import systems.diath.homeclaim.core.model.ComponentPolicy
import systems.diath.homeclaim.core.model.ComponentState
import systems.diath.homeclaim.core.model.ComponentType
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.ActionEndpoint
import systems.diath.homeclaim.core.policy.ActionResourceRef
import systems.diath.homeclaim.core.policy.HomeClaimOperationActions

/** Actor-aware operations over the component repository used by the runtime index. */
class ComponentOperationService(
    private val components: ComponentService,
    private val regions: RegionService,
    private val audit: AuditService? = null,
    private val authorizer: RegionMutationAuthorizer = RegionMutationAuthorizer.SYSTEM_ONLY
) {
    @Synchronized
    fun create(
        context: RegionMutationContext,
        regionId: RegionId,
        type: ComponentType,
        position: Position,
        config: ComponentConfig,
        policy: ComponentPolicy
    ): OperationResult<Component> {
        val region = regions.getRegionById(regionId)
        val request = context.actionRequest(
            HomeClaimOperationActions.COMPONENT_CHANGE,
            regionId,
            region,
            mapOf("componentType" to type.name, "operation" to "CREATE"),
            target = ActionEndpoint(position = position, resource = ActionResourceRef.Region(regionId), areaId = region?.areaId)
        )
        audit?.append(AuditEntries.operationResult(request, AuditStatus.ATTEMPTED, targetCount = 1))
        if (region == null) return reject(request, OperationStatus.NOT_FOUND, "REGION_NOT_FOUND")
        if (!authorizer.isAuthorized(context.actor, region, RegionMutationCapability.COMPONENT_CHANGE)) {
            return reject(request, OperationStatus.DENIED, "COMPONENT_CHANGE_DENIED")
        }
        if (regions.getRegionAt(region.areaId, position.x, position.y, position.z) != regionId) {
            return reject(request, OperationStatus.INVALID, "COMPONENT_OUTSIDE_REGION")
        }
        val player = context.actor as? ActionActor.Player
            ?: return reject(request, OperationStatus.INVALID, "COMPONENT_CREATION_REQUIRES_PLAYER_ACTOR")
        return try {
            val id = components.createComponent(regionId, type, position, config, policy, player.playerId)
            val created = components.listComponents(regionId).firstOrNull { it.id == id }
                ?: return reject(request, OperationStatus.FAILED, "COMPONENT_NOT_VISIBLE_AFTER_CREATE")
            val completed = request.copy(target = ActionEndpoint(
                resource = ActionResourceRef.Component(id),
                areaId = region.areaId
            ))
            audit?.append(AuditEntries.operationResult(completed, AuditStatus.COMPLETED, targetCount = 1))
            OperationResult.success(created)
        } catch (failure: Exception) {
            reject(request, OperationStatus.FAILED, failure.message ?: "COMPONENT_CREATE_FAILED")
        }
    }

    @Synchronized
    fun update(
        context: RegionMutationContext,
        componentId: ComponentId,
        config: ComponentConfig? = null,
        policy: ComponentPolicy? = null,
        state: ComponentState? = null
    ): OperationResult<Component> {
        val component = findComponent(componentId)
            ?: return reject(
                context.actionRequest(HomeClaimOperationActions.COMPONENT_CHANGE, null, attributes = mapOf("operation" to "UPDATE")),
                OperationStatus.NOT_FOUND,
                "COMPONENT_NOT_FOUND"
            )
        val region = regions.getRegionById(component.regionId)
        val request = componentRequest(context, component, region, "UPDATE")
        audit?.append(AuditEntries.operationResult(request, AuditStatus.ATTEMPTED, targetCount = 1))
        if (region == null) return reject(request, OperationStatus.NOT_FOUND, "REGION_NOT_FOUND")
        if (!authorizer.isAuthorized(context.actor, region, RegionMutationCapability.COMPONENT_CHANGE)) {
            return reject(request, OperationStatus.DENIED, "COMPONENT_CHANGE_DENIED")
        }
        if (config == null && policy == null && state == null) {
            audit?.append(AuditEntries.operationResult(request, AuditStatus.COMPLETED, targetCount = 1))
            return OperationResult.success(component, "UNCHANGED")
        }
        return try {
            components.updateComponent(componentId, config, policy, state)
            val updated = findComponent(componentId)
                ?: return reject(request, OperationStatus.FAILED, "COMPONENT_NOT_VISIBLE_AFTER_UPDATE")
            audit?.append(AuditEntries.operationResult(request, AuditStatus.COMPLETED, targetCount = 1))
            OperationResult.success(updated)
        } catch (failure: Exception) {
            reject(request, OperationStatus.FAILED, failure.message ?: "COMPONENT_UPDATE_FAILED")
        }
    }

    @Synchronized
    fun remove(context: RegionMutationContext, componentId: ComponentId): OperationResult<Component> {
        val component = findComponent(componentId)
            ?: return reject(
                context.actionRequest(HomeClaimOperationActions.COMPONENT_CHANGE, null, attributes = mapOf("operation" to "REMOVE")),
                OperationStatus.NOT_FOUND,
                "COMPONENT_NOT_FOUND"
            )
        val region = regions.getRegionById(component.regionId)
        val request = componentRequest(context, component, region, "REMOVE")
        audit?.append(AuditEntries.operationResult(request, AuditStatus.ATTEMPTED, targetCount = 1))
        if (region == null) return reject(request, OperationStatus.NOT_FOUND, "REGION_NOT_FOUND")
        if (!authorizer.isAuthorized(context.actor, region, RegionMutationCapability.COMPONENT_CHANGE)) {
            return reject(request, OperationStatus.DENIED, "COMPONENT_CHANGE_DENIED")
        }
        return try {
            components.deleteComponent(componentId)
            if (findComponent(componentId) != null) return reject(request, OperationStatus.FAILED, "COMPONENT_REMAINS_AFTER_REMOVE")
            audit?.append(AuditEntries.operationResult(request, AuditStatus.COMPLETED, targetCount = 1))
            OperationResult.success(component)
        } catch (failure: Exception) {
            reject(request, OperationStatus.FAILED, failure.message ?: "COMPONENT_REMOVE_FAILED")
        }
    }

    private fun componentRequest(
        context: RegionMutationContext,
        component: Component,
        region: Region?,
        operation: String
    ) = context.actionRequest(
        HomeClaimOperationActions.COMPONENT_CHANGE,
        component.regionId,
        region,
        mapOf("componentType" to component.type.name, "operation" to operation),
        ActionEndpoint(resource = ActionResourceRef.Component(component.id), areaId = region?.areaId)
    )

    private fun findComponent(id: ComponentId): Component? = components.getComponent(id)

    private fun <T> reject(
        request: systems.diath.homeclaim.core.policy.ActionRequest,
        status: OperationStatus,
        detail: String
    ): OperationResult<T> {
        audit?.append(AuditEntries.operationResult(
            request,
            if (status == OperationStatus.DENIED) AuditStatus.DENIED else AuditStatus.FAILED,
            error = detail,
            targetCount = if (request.regionId == null) null else 1
        ))
        return OperationResult.rejected(status, detail)
    }
}
