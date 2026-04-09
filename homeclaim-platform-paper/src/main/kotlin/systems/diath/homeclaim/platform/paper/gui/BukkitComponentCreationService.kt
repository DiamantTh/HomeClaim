package systems.diath.homeclaim.platform.paper.gui

import systems.diath.homeclaim.core.ui.ComponentCreationService
import systems.diath.homeclaim.core.ui.InMemoryComponentCreationService
import systems.diath.homeclaim.core.ui.ComponentCreationState
import systems.diath.homeclaim.core.ui.ComponentCreationEvent
import java.util.UUID

/**
 * Bukkit-specific wrapper around component creation service.
 * Bridges core logic with Paper platform (menus, block listeners, etc).
 */
class BukkitComponentCreationService(
    private val coreService: ComponentCreationService = InMemoryComponentCreationService(),
    private val guiManager: GuiManager? = null
) : ComponentCreationService by coreService {
    private val i18n = systems.diath.homeclaim.platform.paper.I18n()
    
    /**
     * Start creation and open initial menu.
     */
    fun startCreationWithMenu(creatorId: UUID, regionId: String, guiManager: GuiManager): ComponentCreationState? {
        val state = startCreation(creatorId, regionId)
        
        if (state is ComponentCreationState.SelectType) {
            val player = org.bukkit.Bukkit.getPlayer(creatorId)
            if (player != null) {
                // Open component type selection menu
                guiManager.openMenu(player, ComponentTypeSelectionMenu(this, regionId, creatorId))
            }
        }
        
        return state
    }
    
    /**
     * Handle creation event and update UI if needed.
     */
    fun handleEventWithMenu(event: ComponentCreationEvent, guiManager: GuiManager): ComponentCreationState? {
        val newState = handleEvent(event)
        
        val player = org.bukkit.Bukkit.getPlayer(event.creatorId)
        if (player != null && newState != null) {
            // Open appropriate menu for new state
            when (newState) {
                is ComponentCreationState.SelectBlock -> {
                    player.sendMessage(i18n.msg("gui.creation.select_block"))
                }
                is ComponentCreationState.Configure -> {
                    // Open configuration menu
                    guiManager.openMenu(player, ComponentConfigMenu(this, newState.regionId, newState.componentType))
                }
                is ComponentCreationState.Confirm -> {
                    player.sendMessage(i18n.msg("gui.creation.created"))
                }
                else -> {}
            }
        }
        
        return newState
    }
    
    /**
     * Cancel creation and notify player.
     */
    fun cancelCreationWithMessage(creatorId: UUID) {
        cancelCreation(creatorId)
        
        val player = org.bukkit.Bukkit.getPlayer(creatorId)
        player?.sendMessage(i18n.msg("gui.creation.cancelled"))
    }
}
