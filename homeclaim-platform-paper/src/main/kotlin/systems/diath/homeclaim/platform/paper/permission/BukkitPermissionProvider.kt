package systems.diath.homeclaim.platform.paper.permission

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import systems.diath.homeclaim.core.permission.PermissionProvider
import java.util.UUID

/**
 * Bukkit implementation of PermissionProvider using Bukkit permissions.
 */
class BukkitPermissionProvider(private val plugin: Plugin) : PermissionProvider {
    
    override fun hasPermission(playerId: UUID, permission: String): Boolean {
        val player = Bukkit.getPlayer(playerId) ?: return false
        return player.hasPermission(permission)
    }
    
    override fun grantPermission(playerId: UUID, permission: String): Boolean {
        val player = Bukkit.getPlayer(playerId) ?: return false
        player.addAttachment(plugin, permission, true)
        return true
    }
    
    override fun revokePermission(playerId: UUID, permission: String): Boolean {
        val player = Bukkit.getPlayer(playerId) ?: return false
        player.removeAttachment(
            player.effectivePermissions
                .firstOrNull { it.permission == permission }
                ?.attachment
                ?: return false
        )
        return true
    }
    
    override fun isOperator(playerId: UUID): Boolean {
        val player = Bukkit.getPlayer(playerId) ?: return false
        return player.isOp
    }
    
    override fun getPermissions(playerId: UUID): Set<String> {
        val player = Bukkit.getPlayer(playerId) ?: return emptySet()
        return player.effectivePermissions.map { it.permission }.toSet()
    }
}
