package systems.diath.homeclaim.platform.paper.gui

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import systems.diath.homeclaim.core.model.ComponentType
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.service.ComponentService
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.core.model.Component
import systems.diath.homeclaim.core.model.ComponentId
import systems.diath.homeclaim.core.model.ComponentState
import systems.diath.homeclaim.core.model.ElevatorConfig
import systems.diath.homeclaim.core.model.ElevatorMode
import systems.diath.homeclaim.core.model.ElevatorSearchRule
import systems.diath.homeclaim.core.model.TeleportConfig
import systems.diath.homeclaim.core.model.TeleportScope
import systems.diath.homeclaim.core.model.TeleportLinkMode
import java.util.UUID

class PadCreatorMenu(
    private val region: Region,
    private val componentService: ComponentService,
    private val regionService: RegionService,
    private val guiManager: GuiManager
) : GuiMenu(systems.diath.homeclaim.platform.paper.I18n().msg("gui.pad.title"), rows = 4) {
    
    override fun render(player: Player) {
        val state = PadCreatorStateManager.getState(player.uniqueId) 
            ?: PadCreatorState.SelectType(region.id)
        
        PadCreatorStateManager.setState(player.uniqueId, state)
        clearInventory()
        
        when (state) {
            is PadCreatorState.SelectType -> renderSelectType()
            is PadCreatorState.SelectBlock -> renderSelectBlock(state)
            is PadCreatorState.Configure -> renderConfigure(state)
        }
    }
    
    private fun renderSelectType() {
        setItem(11, createItem(Material.HEAVY_WEIGHTED_PRESSURE_PLATE, i18n.msg("gui.pad.select_type.elevator.title"),
            listOf(i18n.msg("gui.pad.select_type.elevator.l1"), i18n.msg("gui.pad.select_type.elevator.l2"))))
        setItem(15, createItem(Material.LIGHT_WEIGHTED_PRESSURE_PLATE, i18n.msg("gui.pad.select_type.teleport.title"),
            listOf(i18n.msg("gui.pad.select_type.teleport.l1"), i18n.msg("gui.pad.select_type.teleport.l2"))))
        setItem(49, createItem(Material.ARROW, i18n.msg("gui.back")))
    }
    
    private fun renderSelectBlock(state: PadCreatorState.SelectBlock) {
        setItem(11, createItem(Material.DAYLIGHT_DETECTOR, i18n.msg("gui.pad.select_block.title"),
            listOf(
                i18n.msg("gui.pad.select_block.l1"),
                i18n.msg("gui.pad.select_block.l2"),
                "",
                i18n.msg("gui.pad.select_block.type", state.padType.name)
            )))
        setItem(49, createItem(Material.ARROW, i18n.msg("gui.pad.select_block.back")))
    }
    
    private fun renderConfigure(state: PadCreatorState.Configure) {
        setItem(2, createItem(Material.WRITABLE_BOOK, i18n.msg("gui.pad.config.title"),
            listOf(
                i18n.msg("gui.pad.config.type", state.padType.name),
                i18n.msg("gui.pad.config.block", state.blockPos.x, state.blockPos.y, state.blockPos.z),
                i18n.msg("gui.pad.config.mode", state.mode),
                i18n.msg("gui.pad.config.floor", state.floorName)
            )))
        
        if (state.padType == ComponentType.ELEVATOR_PAD) {
            setItem(11, createItem(Material.COMPARATOR, i18n.msg("gui.component.mode", state.mode),
                listOf(i18n.msg("gui.component.mode.nearest"), i18n.msg("gui.component.mode.named"))))
            setItem(13, createItem(Material.NAME_TAG, i18n.msg("gui.component.floor_name", state.floorName),
                listOf(i18n.msg("gui.component.floor_desc"))))
        } else {
            setItem(11, createItem(Material.REDSTONE, i18n.msg("gui.component.link_id", state.linkId),
                listOf(i18n.msg("gui.component.link_desc"))))
        }
        
        setItem(31, createItem(Material.LIME_DYE, i18n.msg("gui.save"),
            listOf(i18n.msg("gui.pad.save_hint"))))
        setItem(49, createItem(Material.ARROW, i18n.msg("gui.back")))
    }
    
    override fun onClick(player: Player, slot: Int, item: ItemStack?, clickType: ClickType): Boolean {
        val state = PadCreatorStateManager.getState(player.uniqueId) ?: return true
        
        when (state) {
            is PadCreatorState.SelectType -> handleSelectType(player, slot)
            is PadCreatorState.SelectBlock -> handleSelectBlock(player, slot, state)
            is PadCreatorState.Configure -> handleConfigure(player, slot, state)
        }
        
        return true
    }
    
    private fun handleSelectType(player: Player, slot: Int) {
        when (slot) {
            11 -> {
                val newState = PadCreatorState.SelectBlock(region.id, ComponentType.ELEVATOR_PAD)
                PadCreatorStateManager.setState(player.uniqueId, newState)
                render(player)
                player.sendMessage(i18n.msg("gui.pad.select_block.prompt"))
            }
            15 -> {
                val newState = PadCreatorState.SelectBlock(region.id, ComponentType.TELEPORT_PAD)
                PadCreatorStateManager.setState(player.uniqueId, newState)
                render(player)
                player.sendMessage(i18n.msg("gui.pad.select_block.prompt"))
            }
            49 -> {
                PadCreatorStateManager.clearState(player.uniqueId)
                guiManager.openMenu(player, ComponentManagerMenu(region, guiManager.componentService, regionService, guiManager))
            }
        }
    }
    
    private fun handleSelectBlock(player: Player, slot: Int, @Suppress("UNUSED_PARAMETER") state: PadCreatorState.SelectBlock) {
        when (slot) {
            49 -> {
                PadCreatorStateManager.clearState(player.uniqueId)
                guiManager.openMenu(player, ComponentManagerMenu(region, guiManager.componentService, regionService, guiManager))
            }
        }
    }
    
    private fun handleConfigure(player: Player, slot: Int, state: PadCreatorState.Configure) {
        when (slot) {
            11 -> {
                if (state.padType == ComponentType.ELEVATOR_PAD) {
                    val modes = listOf("NEAREST_PAD", "NAMED_FLOOR")
                    val currentIdx = modes.indexOf(state.mode)
                    val nextMode = modes[(currentIdx + 1) % modes.size]
                    val newState = state.copy(mode = nextMode)
                    PadCreatorStateManager.setState(player.uniqueId, newState)
                    render(player)
                } else {
                    // Toggle linkId for teleport pads - just generate new UUID for demo
                    val newState = state.copy(linkId = UUID.randomUUID().toString())
                    PadCreatorStateManager.setState(player.uniqueId, newState)
                    render(player)
                }
            }
            31 -> savePad(player, state)
            49 -> {
                val backState = PadCreatorState.SelectBlock(region.id, state.padType)
                PadCreatorStateManager.setState(player.uniqueId, backState)
                render(player)
            }
        }
    }
    
    private fun savePad(player: Player, state: PadCreatorState.Configure) {
        try {
            val config: systems.diath.homeclaim.core.model.ComponentConfig = if (state.padType == ComponentType.ELEVATOR_PAD) {
                ElevatorConfig(
                    mode = ElevatorMode.VERTICAL,
                    searchRule = if (state.mode == "NAMED_FLOOR") ElevatorSearchRule.NAMED_FLOOR else ElevatorSearchRule.NEAREST_PAD,
                    floorName = state.floorName
                )
            } else {
                val linkId = if (state.linkId.isNotEmpty()) {
                    try { UUID.fromString(state.linkId) } catch (e: Exception) { UUID.randomUUID() }
                } else UUID.randomUUID()
                
                TeleportConfig(
                    linkId = linkId,
                    linkMode = TeleportLinkMode.PAIR,
                    withinScope = TeleportScope.REGION_ONLY
                )
            }
            
            componentService.createComponent(
                regionId = region.id,
                type = state.padType,
                position = state.blockPos,
                config = config,
                policy = systems.diath.homeclaim.core.model.ComponentPolicy()
            )
            player.sendMessage(i18n.msg("gui.pad.created"))
            PadCreatorStateManager.clearState(player.uniqueId)
            guiManager.openMenu(player, ComponentManagerMenu(region, guiManager.componentService, regionService, guiManager))
            
        } catch (e: Exception) {
            player.sendMessage(i18n.msg("gui.pad.error", e.message ?: "unknown"))
        }
    }
}
