package systems.diath.homeclaim.platform.paper.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionRole
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.core.service.RoleService
import systems.diath.homeclaim.core.service.AuditService
import systems.diath.homeclaim.core.service.AuditEntries
import systems.diath.homeclaim.core.service.AuditStatus
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.ActionEndpoint
import systems.diath.homeclaim.core.policy.ActionRequest
import systems.diath.homeclaim.core.policy.ActionResourceRef
import systems.diath.homeclaim.core.policy.ActionContext
import systems.diath.homeclaim.core.policy.CorrelationId
import systems.diath.homeclaim.core.policy.HomeClaimOperationActions

class RoleEditorMenu(
    private var region: Region,
    private val regionService: RegionService,
    private val guiManager: GuiManager,
    private val roleService: RoleService? = null,
    private val auditService: AuditService? = null
) : GuiMenu(systems.diath.homeclaim.platform.paper.I18n().msg("gui.role.title"), rows = 6) {
    
    private var selectedRole: RegionRole? = null
    
    /** Reload region from service to get fresh data after mutations */
    private fun refreshRegion() {
        regionService.getRegionById(region.id)?.let { region = it }
    }
    
    override fun render(player: Player) {
        clearInventory()
        
        if (selectedRole == null) {
            setItem(11, createItem(Material.EMERALD, i18n.msg("gui.role.trusted", region.roles.trusted.size)))
            setItem(13, createItem(Material.IRON_INGOT, i18n.msg("gui.role.members", region.roles.members.size)))
            setItem(15, createItem(Material.REDSTONE, i18n.msg("gui.role.banned", region.roles.banned.size)))
        } else {
            val role = selectedRole!!
            val players = when (role) {
                RegionRole.TRUSTED -> region.roles.trusted
                RegionRole.MEMBER -> region.roles.members
                RegionRole.BANNED -> region.roles.banned
                else -> emptySet()
            }
            setItem(4, createItem(Material.PLAYER_HEAD, i18n.msg("gui.role.role_header", role.name, players.size)))
            players.forEachIndexed { index, playerId ->
                if (index >= 45) return@forEachIndexed
                val playerName = Bukkit.getOfflinePlayer(playerId).name ?: playerId.toString().take(8)
                setItem(index, createItem(Material.PLAYER_HEAD, i18n.msg("gui.player.name", playerName), listOf(i18n.msg("gui.role.remove_hint"))))
            }
        }
        setItem(49, createItem(Material.ARROW, i18n.msg("gui.back")))
    }
    
    override fun onClick(player: Player, slot: Int, item: ItemStack?, clickType: ClickType): Boolean {
        when {
            slot == 49 -> if (selectedRole != null) { selectedRole = null; render(player) }
                         else guiManager.openMenu(player, RegionManageMenu(region, regionService, guiManager))
            selectedRole == null -> {
                selectedRole = when (slot) {
                    11 -> RegionRole.TRUSTED
                    13 -> RegionRole.MEMBER
                    15 -> RegionRole.BANNED
                    else -> null
                }
                render(player)
            }
            slot in 0..44 -> {
                val role = selectedRole!!
                val players = when (role) {
                    RegionRole.TRUSTED -> region.roles.trusted.toList()
                    RegionRole.MEMBER -> region.roles.members.toList()
                    RegionRole.BANNED -> region.roles.banned.toList()
                    else -> emptyList()
                }
                if (slot < players.size) {
                    val playerId = players[slot]
                    val updated = when (role) {
                        RegionRole.TRUSTED -> region.copy(roles = region.roles.copy(trusted = region.roles.trusted - playerId))
                        RegionRole.MEMBER -> region.copy(roles = region.roles.copy(members = region.roles.members - playerId))
                        RegionRole.BANNED -> region.copy(roles = region.roles.copy(banned = region.roles.banned - playerId))
                        else -> region
                    }
                    if (roleService != null) {
                        roleService.removeRole(region.id, playerId, role)
                    } else {
                        @Suppress("DEPRECATION")
                        regionService.updateRegion(updated)
                    }
                    val request = ActionRequest(
                        actor = ActionActor.Player(player.uniqueId),
                        actionKey = HomeClaimOperationActions.ROLE_CHANGE,
                        target = ActionEndpoint(resource = ActionResourceRef.Region(region.id), areaId = region.areaId),
                        correlationId = CorrelationId.newId(),
                        context = ActionContext(platform = "paper", eventName = "gui_role_remove", attributes = mapOf(
                            "playerId" to playerId.toString(),
                            "role" to role.name
                        ))
                    )
                    auditService?.append(AuditEntries.operationResult(request, AuditStatus.COMPLETED, targetCount = 1))
                    refreshRegion()
                    player.sendMessage(i18n.msg("gui.role.player_removed"))
                    render(player)
                }
            }
        }
        return true
    }
}
