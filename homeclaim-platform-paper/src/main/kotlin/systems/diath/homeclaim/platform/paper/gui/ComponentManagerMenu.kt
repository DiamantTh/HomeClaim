package systems.diath.homeclaim.platform.paper.gui

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import systems.diath.homeclaim.core.model.ComponentId
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.ComponentType
import systems.diath.homeclaim.core.model.RegionRole
import systems.diath.homeclaim.core.service.ComponentService
import systems.diath.homeclaim.core.service.RegionService

class ComponentManagerMenu(
    private val region: Region,
    private val componentService: ComponentService,
    private val regionService: RegionService,
    private val guiManager: GuiManager
) : GuiMenu(systems.diath.homeclaim.platform.paper.I18n().msg("gui.components.title"), rows = 4) {
    
    private var components: List<systems.diath.homeclaim.core.model.Component> = emptyList()
    private var currentPage = 0
    private val pageSize = 18
    private var pendingDelete: ComponentId? = null
    
    private fun canManageComponents(player: Player): Boolean {
        // Owner can always manage
        if (region.owner == player.uniqueId) return true
        // Trusted can manage
        if (region.roles.trusted.contains(player.uniqueId)) return true
        // Check admin permission
        return player.hasPermission("homeclaim.admin.components")
    }
    
    @Suppress("UNUSED_PARAMETER")
    private fun renderNoPermission(player: Player) {
        clearInventory()
        setItem(13, createItem(Material.BARRIER, i18n.msg("gui.components.no_permission.title"),
            listOf(
                i18n.msg("gui.components.no_permission.l1"),
                i18n.msg("gui.components.no_permission.l2"),
                "",
                i18n.msg("gui.components.no_permission.back_hint")
            )))
        setItem(49, createItem(Material.ARROW, i18n.msg("gui.back")))
    }
    
    override fun render(player: Player) {
        // Permission check
        if (!canManageComponents(player)) {
            renderNoPermission(player)
            return
        }
        
        clearInventory()
        components = componentService.listComponents(region.id)
        
        if (components.isEmpty()) {
            setItem(13, createItem(Material.BARRIER, i18n.msg("gui.components.none.title"),
                listOf(
                    i18n.msg("gui.components.none.l1"),
                    "",
                    i18n.msg("gui.components.none.l2")
                )))
            setItem(29, createItem(Material.LIME_DYE, i18n.msg("gui.components.new_pad")))
        } else {
            val start = currentPage * pageSize
            val end = minOf(start + pageSize, components.size)
            
            for (i in start until end) {
                val comp = components[i]
                val material = when (comp.type) {
                    ComponentType.ELEVATOR_PAD -> Material.HEAVY_WEIGHTED_PRESSURE_PLATE
                    ComponentType.TELEPORT_PAD -> Material.LIGHT_WEIGHTED_PRESSURE_PLATE
                }
                
                val deleteHint = if (pendingDelete == comp.id) {
                    i18n.msg("gui.components.delete_confirm_hint")
                } else {
                    i18n.msg("gui.components.delete_hint")
                }

                val stateIcon = if (comp.state.name == "ENABLED") i18n.msg("gui.state.enabled") else i18n.msg("gui.state.disabled")
                val lore = listOf(
                    i18n.msg("gui.components.type", comp.type.name),
                    i18n.msg("gui.components.pos", comp.position.x, comp.position.y, comp.position.z),
                    i18n.msg("gui.components.state", stateIcon, comp.state.name),
                    "",
                    i18n.msg("gui.components.edit_hint"),
                    deleteHint
                )
                
                setItem(i - start, createItem(material,
                    i18n.msg("gui.components.id", comp.id.value.toString().take(8)),
                    lore))
            }
            
            // Buttons
            if (currentPage > 0) setItem(45, createItem(Material.ARROW, i18n.msg("gui.prev")))
            val maxPage = (components.size - 1) / pageSize
            if (currentPage < maxPage) setItem(53, createItem(Material.ARROW, i18n.msg("gui.next")))
            setItem(29, createItem(Material.LIME_DYE, i18n.msg("gui.components.new_pad")))
        }
        
        setItem(49, createItem(Material.ARROW, i18n.msg("gui.back")))
    }
    
    override fun onClick(player: Player, slot: Int, item: ItemStack?, clickType: ClickType): Boolean {
        // Permission check
        if (!canManageComponents(player)) {
            when (slot) {
                49 -> guiManager.openMenu(player, RegionManageMenu(region, regionService, guiManager))
            }
            return true
        }
        
        when (slot) {
            45 -> if (currentPage > 0) { pendingDelete = null; currentPage--; render(player) }
            53 -> { 
                val max = (components.size - 1) / pageSize
                if (currentPage < max) { pendingDelete = null; currentPage++; render(player) }
            }
            29 -> {
                // Open PadCreatorMenu
                pendingDelete = null
                PadCreatorStateManager.clearState(player.uniqueId)
                guiManager.openMenu(player, PadCreatorMenu(region, componentService, regionService, guiManager))
            }
            49 -> {
                pendingDelete = null
                guiManager.openMenu(player, RegionManageMenu(region, regionService, guiManager))
            }
            in 0..17 -> {
                val idx = currentPage * pageSize + slot
                if (idx < components.size) {
                    val comp = components[idx]
                    if (clickType.isRightClick) {
                        if (pendingDelete == comp.id) {
                            componentService.deleteComponent(comp.id)
                            pendingDelete = null
                            player.sendMessage(i18n.msg("gui.components.deleted"))
                            render(player)
                        } else {
                            pendingDelete = comp.id
                            player.sendMessage(i18n.msg("gui.components.delete_confirm_msg"))
                            render(player)
                        }
                    } else {
                        pendingDelete = null
                        guiManager.openMenu(player, ComponentEditorMenu(comp, region, componentService, regionService, guiManager))
                    }
                }
            }
        }
        return true
    }
}
