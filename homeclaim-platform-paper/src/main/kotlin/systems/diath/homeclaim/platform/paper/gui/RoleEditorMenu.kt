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
import systems.diath.homeclaim.core.service.RoleMutationKind
import systems.diath.homeclaim.core.service.RoleMutationRequest
import systems.diath.homeclaim.core.service.AuditService

class RoleEditorMenu(
    private var region: Region,
    private val regionService: RegionService,
    private val guiManager: GuiManager,
    private val roleService: RoleService,
    @Suppress("unused") @Deprecated("Role mutations audit through RoleService") private val auditService: AuditService? = null
) : GuiMenu(systems.diath.homeclaim.platform.paper.I18n().msg("gui.role.title"), rows = 6) {
    
    private var selectedRole: RegionRole? = null
    private var selectingTarget = false
    
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
        } else if (selectingTarget) {
            val candidates = onlineCandidates(player)
            setItem(4, createItem(Material.PLAYER_HEAD, "§a${selectedRole!!.name}: choose online player", listOf("§7Only currently online players are selectable.")))
            candidates.take(45).forEachIndexed { index, candidate ->
                setItem(index, createItem(Material.PLAYER_HEAD, "§f${candidate.name}", listOf("§7${candidate.uniqueId}")))
            }
            if (candidates.isEmpty()) {
                setItem(22, createItem(Material.BARRIER, "§eNo eligible online players", listOf("§7The region owner cannot receive another role.")))
            }
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
            setItem(47, createItem(Material.LIME_DYE, "§aAdd online player", listOf("§7Select a currently online player.")))
        }
        setItem(49, createItem(Material.ARROW, i18n.msg("gui.back")))
    }
    
    override fun onClick(player: Player, slot: Int, item: ItemStack?, clickType: ClickType): Boolean {
        when {
            slot == 49 -> if (selectingTarget) { selectingTarget = false; render(player) }
                         else if (selectedRole != null) { selectedRole = null; render(player) }
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
            slot == 47 && selectedRole != null && !selectingTarget -> {
                selectingTarget = true
                render(player)
            }
            selectingTarget && slot in 0..44 -> {
                val target = onlineCandidates(player).getOrNull(slot) ?: return true
                selectingTarget = false
                mutate(player, target.uniqueId, selectedRole!!, RoleMutationKind.ADD)
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
                    mutate(player, players[slot], role, RoleMutationKind.REMOVE)
                }
            }
        }
        return true
    }

    private fun onlineCandidates(actor: Player): List<Player> = Bukkit.getOnlinePlayers()
        .asSequence()
        .filter { it.uniqueId != region.owner }
        .filter { it.uniqueId != actor.uniqueId || selectedRole != RegionRole.BANNED }
        .sortedBy { it.name.lowercase() }
        .toList()

    private fun mutate(player: Player, targetPlayerId: java.util.UUID, role: RegionRole, kind: RoleMutationKind) {
        val result = roleService.mutateRole(RoleMutationRequest(
            actor = systems.diath.homeclaim.core.policy.ActionActor.Player(player.uniqueId),
            regionId = region.id,
            targetPlayerId = targetPlayerId,
            role = role,
            kind = kind,
            platform = "paper",
            correlationId = systems.diath.homeclaim.core.policy.CorrelationId.newId()
        ))
        if (result.succeeded) {
            refreshRegion()
            player.sendMessage(if (kind == RoleMutationKind.ADD) "§aRole updated." else i18n.msg("gui.role.player_removed"))
            render(player)
        } else {
            player.showOperationFailure(result.detail)
            render(player)
        }
    }
}
