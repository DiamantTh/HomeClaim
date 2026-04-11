package systems.diath.homeclaim.platform.paper.util

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import systems.diath.homeclaim.core.model.RegionRole
import systems.diath.homeclaim.platform.paper.I18n

/**
 * Central permission management for HomeClaim.
 * 
 * Permissions hierarchy:
 * - homeclaim.admin.*       - Full admin access
 * - homeclaim.mod.*         - Moderator access
 * - homeclaim.plot.*        - Plot management
 * - homeclaim.component.*   - Component usage
 */
object Permissions {
    
    // ============================================
    // ADMIN PERMISSIONS
    // ============================================
    const val ADMIN_ALL = "homeclaim.admin.*"
    const val ADMIN_RELOAD = "homeclaim.admin.reload"
    const val ADMIN_SETUP = "homeclaim.admin.setup"
    const val ADMIN_MIGRATE = "homeclaim.admin.migrate"
    const val ADMIN_DEBUG = "homeclaim.admin.debug"
    const val ADMIN_BYPASS_RATELIMIT = "homeclaim.admin.bypass.ratelimit"
    const val ADMIN_BYPASS_CLAIM_LIMIT = "homeclaim.admin.bypass.claimlimit"
    const val ADMIN_BYPASS_PROTECTION = "homeclaim.admin.bypass.protection"
    const val ADMIN_DELETE_ANY = "homeclaim.admin.delete.any"
    const val ADMIN_EDIT_ANY = "homeclaim.admin.edit.any"
    
    // ============================================
    // MODERATOR PERMISSIONS
    // ============================================
    const val MOD_ALL = "homeclaim.mod.*"
    const val MOD_INSPECT = "homeclaim.mod.inspect"
    const val MOD_TELEPORT = "homeclaim.mod.teleport"
    const val MOD_KICK = "homeclaim.mod.kick"
    
    // ============================================
    // PLOT PERMISSIONS
    // ============================================
    const val PLOT_ALL = "homeclaim.plot.*"
    const val PLOT_CLAIM = "homeclaim.plot.claim"
    const val PLOT_HOME = "homeclaim.plot.home"
    const val PLOT_INFO = "homeclaim.plot.info"
    const val PLOT_LIST = "homeclaim.plot.list"
    const val PLOT_VISIT = "homeclaim.plot.visit"
    const val PLOT_SELL = "homeclaim.plot.sell"
    const val PLOT_BUY = "homeclaim.plot.buy"
    const val PLOT_MERGE = "homeclaim.plot.merge"
    const val PLOT_UNLINK = "homeclaim.plot.unlink"
    const val PLOT_RESET = "homeclaim.plot.reset"
    const val PLOT_JOBS = "homeclaim.plot.jobs"
    
    // ============================================
    // COMPONENT PERMISSIONS
    // ============================================
    const val COMPONENT_ALL = "homeclaim.component.*"
    const val COMPONENT_CREATE = "homeclaim.component.create"
    const val COMPONENT_USE = "homeclaim.component.use"
    const val COMPONENT_MODIFY = "homeclaim.component.modify"
    const val COMPONENT_DELETE = "homeclaim.component.delete"
    
    // ============================================
    // UTILITY METHODS
    // ============================================
    
    /**
     * Check if sender has any of the given permissions.
     */
    fun hasAny(sender: CommandSender, vararg permissions: String): Boolean {
        if (sender.isOp) return true
        return permissions.any { sender.hasPermission(it) }
    }
    
    /**
     * Check if sender has all of the given permissions.
     */
    fun hasAll(sender: CommandSender, vararg permissions: String): Boolean {
        if (sender.isOp) return true
        return permissions.all { sender.hasPermission(it) }
    }
    
    /**
     * Check if player can bypass protection (admin mode).
     */
    fun canBypassProtection(player: Player): Boolean {
        return player.hasPermission(ADMIN_BYPASS_PROTECTION) || player.hasPermission(ADMIN_ALL)
    }
    
    /**
     * Check if player can modify any plot (not just their own).
     */
    fun canEditAnyPlot(player: Player): Boolean {
        return player.hasPermission(ADMIN_EDIT_ANY) || player.hasPermission(ADMIN_ALL)
    }
    
    /**
     * Check if player can delete any plot.
     */
    fun canDeleteAnyPlot(player: Player): Boolean {
        return player.hasPermission(ADMIN_DELETE_ANY) || player.hasPermission(ADMIN_ALL)
    }
    
    /**
     * Check if player has admin privileges.
     */
    fun isAdmin(player: Player): Boolean {
        return player.hasPermission(ADMIN_ALL) || player.isOp
    }
    
    /**
     * Check if player has moderator privileges.
     */
    fun isModerator(player: Player): Boolean {
        return isAdmin(player) || player.hasPermission(MOD_ALL)
    }
    
    /**
     * Convert region role to effective permission level.
     * Returns the highest permission that should be checked.
     */
    fun roleToPermission(role: RegionRole, action: String): String {
        return when (role) {
            RegionRole.OWNER -> "homeclaim.plot.owner.$action"
            RegionRole.TRUSTED -> "homeclaim.plot.trusted.$action"
            RegionRole.MEMBER -> "homeclaim.plot.member.$action"
            RegionRole.VISITOR -> "homeclaim.plot.visitor.$action"
            RegionRole.BANNED -> "homeclaim.plot.banned.$action"
        }
    }
    
    /**
     * Check if player has permission or is admin.
     * This is the standard permission check that should be used.
     */
    fun check(sender: CommandSender, permission: String): Boolean {
        if (sender.isOp) return true
        if (sender.hasPermission(ADMIN_ALL)) return true
        return sender.hasPermission(permission)
    }
    
    /**
     * Check permission and send denial message if not permitted.
     * 
     * @return true if permitted, false if denied
     */
    fun checkWithMessage(sender: CommandSender, permission: String, denialMessage: String = I18n().msg("permission.denied")): Boolean {
        if (!check(sender, permission)) {
            sender.sendMessage(denialMessage)
            return false
        }
        return true
    }
}
