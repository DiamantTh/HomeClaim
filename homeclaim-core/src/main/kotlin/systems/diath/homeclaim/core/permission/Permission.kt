package systems.diath.homeclaim.core.permission

import java.util.UUID

/**
 * Platform-agnostic permission system.
 */
interface PermissionProvider {
    
    /**
     * Check if player has permission.
     */
    fun hasPermission(playerId: UUID, permission: String): Boolean
    
    /**
     * Give permission to player.
     */
    fun grantPermission(playerId: UUID, permission: String): Boolean
    
    /**
     * Remove permission from player.
     */
    fun revokePermission(playerId: UUID, permission: String): Boolean
    
    /**
     * Check if player is operator/admin.
     */
    fun isOperator(playerId: UUID): Boolean
    
    /**
     * Get all permissions for player.
     */
    fun getPermissions(playerId: UUID): Set<String>
}

/**
 * Base permission nodes for HomeClaim.
 */
object HomeclaimPermissions {
    const val REGION_CREATE = "homeclaim.region.create"
    const val REGION_DELETE = "homeclaim.region.delete"
    const val REGION_EDIT = "homeclaim.region.edit"
    const val REGION_BUY = "homeclaim.region.buy"
    const val REGION_CLAIM = "homeclaim.region.claim"
    const val REGION_SELL = "homeclaim.region.sell"
    const val REGION_ADMIN = "homeclaim.region.admin"
    
    const val COMPONENT_CREATE = "homeclaim.component.create"
    const val COMPONENT_DELETE = "homeclaim.component.delete"
    const val COMPONENT_EDIT = "homeclaim.component.edit"
    
    const val ZONE_CREATE = "homeclaim.zone.create"
    const val ZONE_DELETE = "homeclaim.zone.delete"
    const val ZONE_EDIT = "homeclaim.zone.edit"
    
    const val ADMIN_RELOAD = "homeclaim.admin.reload"
    const val ADMIN_MIGRATE = "homeclaim.admin.migrate"
}

/**
 * Simple in-memory permission provider (for testing).
 */
class SimplePermissionProvider : PermissionProvider {
    private val permissions = mutableMapOf<UUID, MutableSet<String>>()
    private val operators = mutableSetOf<UUID>()
    
    override fun hasPermission(playerId: UUID, permission: String): Boolean {
        if (isOperator(playerId)) return true
        
        val playerPerms = permissions[playerId] ?: return false
        
        // Check exact permission and wildcard permissions
        if (permission in playerPerms) return true
        
        // Check parent permissions (homeclaim.* includes homeclaim.region.*)
        val parts = permission.split(".")
        for (i in parts.indices) {
            val wildcard = parts.subList(0, i + 1).joinToString(".") + ".*"
            if (wildcard in playerPerms) return true
        }
        
        return false
    }
    
    override fun grantPermission(playerId: UUID, permission: String): Boolean {
        permissions.getOrPut(playerId) { mutableSetOf() }.add(permission)
        return true
    }
    
    override fun revokePermission(playerId: UUID, permission: String): Boolean {
        return permissions[playerId]?.remove(permission) ?: false
    }
    
    override fun isOperator(playerId: UUID): Boolean {
        return playerId in operators
    }
    
    override fun getPermissions(playerId: UUID): Set<String> {
        return permissions[playerId]?.toSet() ?: emptySet()
    }
    
    fun setOperator(playerId: UUID, operator: Boolean) {
        if (operator) {
            operators.add(playerId)
        } else {
            operators.remove(playerId)
        }
    }
}

/**
 * Permission helper for checking common patterns.
 */
object PermissionHelper {
    
    /**
     * Check if player can manage region.
     */
    fun canManageRegion(provider: PermissionProvider, playerId: UUID): Boolean {
        return provider.hasPermission(playerId, HomeclaimPermissions.REGION_ADMIN)
            || provider.hasPermission(playerId, HomeclaimPermissions.REGION_CREATE)
    }
    
    /**
     * Check if player can manage components.
     */
    fun canManageComponent(provider: PermissionProvider, playerId: UUID): Boolean {
        return provider.hasPermission(playerId, HomeclaimPermissions.COMPONENT_CREATE)
    }
    
    /**
     * Check if player can buy regions.
     */
    fun canBuyRegion(provider: PermissionProvider, playerId: UUID): Boolean {
        return provider.hasPermission(playerId, HomeclaimPermissions.REGION_BUY)
    }
}
