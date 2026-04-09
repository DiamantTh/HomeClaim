package systems.diath.homeclaim.platform.paper.gui

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import systems.diath.homeclaim.core.model.Component
import systems.diath.homeclaim.core.model.ComponentState
import systems.diath.homeclaim.core.model.ComponentType
import systems.diath.homeclaim.core.model.ElevatorConfig
import systems.diath.homeclaim.core.model.ElevatorMode
import systems.diath.homeclaim.core.model.ElevatorSearchRule
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.TeleportConfig
import systems.diath.homeclaim.core.model.TeleportLinkMode
import systems.diath.homeclaim.core.model.TeleportScope
import systems.diath.homeclaim.core.service.ComponentService
import systems.diath.homeclaim.core.service.RegionService
import java.util.UUID

class ComponentEditorMenu(
    private val component: Component,
    private val region: Region,
    private val componentService: ComponentService,
    private val regionService: RegionService,
    private val guiManager: GuiManager
) : GuiMenu(systems.diath.homeclaim.platform.paper.I18n().msg("gui.component.editor.title", component.id.value.toString().take(8)), rows = 4) {
    
    private var mode: String = when (val config = component.config) {
        is ElevatorConfig -> {
            if (config.searchRule == ElevatorSearchRule.NAMED_FLOOR) 
                "NAMED_FLOOR" else "NEAREST_PAD"
        }
        is TeleportConfig -> "LINK"
    }
    
    private var floorName: String = (component.config as? ElevatorConfig)?.floorName ?: "Ground"
    private var linkId: String = (component.config as? TeleportConfig)?.linkId?.toString() ?: ""
    private var enabled: Boolean = component.state == ComponentState.ENABLED
    
    override fun render(player: Player) {
        clearInventory()
        
        // Komponenten-Info
        val stateText = if (enabled) i18n.msg("gui.component.state.enabled_text") else i18n.msg("gui.component.state.disabled_text")
        setItem(2, createItem(Material.WRITABLE_BOOK, i18n.msg("gui.component.config_header"),
            listOf(
                i18n.msg("gui.component.info.id", component.id.value.toString().take(12)),
                i18n.msg("gui.component.info.type", component.type.name),
                i18n.msg("gui.component.info.block", component.position.x, component.position.y, component.position.z),
                i18n.msg("gui.component.info.state", stateText)
            )))
        
        // State Toggle
        val stateItem = if (enabled) Material.LIME_DYE else Material.RED_DYE
        setItem(11, createItem(stateItem, i18n.msg("gui.component.state_item", stateText),
            listOf(i18n.msg("gui.toggle_hint"))))
        
        if (component.type == ComponentType.ELEVATOR_PAD) {
            // Elevator-spezifische Config
            setItem(13, createItem(Material.COMPARATOR, i18n.msg("gui.component.mode", mode),
                listOf(
                    i18n.msg("gui.component.mode.nearest"),
                    i18n.msg("gui.component.mode.named"),
                    i18n.msg("gui.toggle_hint")
                )))
            setItem(15, createItem(Material.NAME_TAG, i18n.msg("gui.component.floor_name", floorName),
                listOf(
                    i18n.msg("gui.component.floor_desc"),
                    i18n.msg("gui.component.floor_current", floorName)
                )))
        } else {
            // Teleport-spezifische Config
            setItem(13, createItem(Material.REDSTONE, i18n.msg("gui.component.link_id", linkId.take(8)),
                listOf(
                    i18n.msg("gui.component.link_desc"),
                    i18n.msg("gui.component.link_new")
                )))
        }
        
        // Buttons
        setItem(31, createItem(Material.LIME_DYE, i18n.msg("gui.save"),
            listOf(i18n.msg("gui.save_hint"))))
        setItem(49, createItem(Material.ARROW, i18n.msg("gui.back")))
    }
    
    override fun onClick(player: Player, slot: Int, item: ItemStack?, clickType: ClickType): Boolean {
        when (slot) {
            11 -> {
                // Toggle enabled
                enabled = !enabled
                render(player)
                player.sendMessage(if (enabled) i18n.msg("gui.state.activated") else i18n.msg("gui.state.deactivated"))
            }
            13 -> {
                if (component.type == ComponentType.ELEVATOR_PAD) {
                    // Toggle Mode
                    mode = if (mode == "NEAREST_PAD") "NAMED_FLOOR" else "NEAREST_PAD"
                    render(player)
                    player.sendMessage(i18n.msg("gui.component.mode", mode))
                } else {
                    // Regenerate LinkId
                    linkId = UUID.randomUUID().toString()
                    render(player)
                    player.sendMessage(i18n.msg("gui.component.link_new_id", linkId.take(12)))
                }
            }
            15 -> {
                if (component.type == ComponentType.ELEVATOR_PAD) {
                    // TODO: Klick auf Floor-Name für Text-Input
                    // Für jetzt: einfach einen Test-Namen setzen
                    floorName = if (floorName == "Ground") "Floor_1" else if (floorName == "Floor_1") "Floor_2" else "Ground"
                    render(player)
                    player.sendMessage(i18n.msg("gui.component.floor_name", floorName))
                }
            }
            31 -> saveComponent(player)
            49 -> {
                guiManager.openMenu(player, ComponentManagerMenu(region, componentService, regionService, guiManager))
            }
        }
        return true
    }
    
    private fun saveComponent(player: Player) {
        try {
            val newState = if (enabled) ComponentState.ENABLED else ComponentState.DISABLED
            
            val newConfig = if (component.type == ComponentType.ELEVATOR_PAD) {
                ElevatorConfig(
                    mode = ElevatorMode.VERTICAL,
                    searchRule = if (mode == "NAMED_FLOOR") ElevatorSearchRule.NAMED_FLOOR else ElevatorSearchRule.NEAREST_PAD,
                    floorName = floorName
                )
            } else {
                val linkUuid = try { UUID.fromString(linkId) } catch (e: Exception) { UUID.randomUUID() }
                TeleportConfig(
                    linkId = linkUuid,
                    linkMode = TeleportLinkMode.PAIR,
                    withinScope = TeleportScope.REGION_ONLY
                )
            }
            
            componentService.updateComponent(
                componentId = component.id,
                config = newConfig,
                state = newState
            )
            
            player.sendMessage(i18n.msg("gui.component.updated"))
            guiManager.openMenu(player, ComponentManagerMenu(region, componentService, regionService, guiManager))
        } catch (e: Exception) {
            player.sendMessage(i18n.msg("gui.component.error", e.message ?: "unknown"))
        }
    }
}
