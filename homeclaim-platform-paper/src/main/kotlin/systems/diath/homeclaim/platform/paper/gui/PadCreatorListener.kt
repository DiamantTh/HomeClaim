package systems.diath.homeclaim.platform.paper.gui

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import systems.diath.homeclaim.core.model.Position

/**
 * Handles block selection during pad creation.
 */
class PadCreatorListener : Listener {
    private val i18n = systems.diath.homeclaim.platform.paper.I18n()
    
    @EventHandler
    fun onBlockClick(e: PlayerInteractEvent) {
        if (e.action != Action.RIGHT_CLICK_BLOCK) return
        
        val player = e.player
        val block = e.clickedBlock ?: return
        val state = PadCreatorStateManager.getState(player.uniqueId) ?: return
        
        // Only process during block selection phase
        if (state !is PadCreatorState.SelectBlock) return
        
        e.isCancelled = true
        
        val blockPos = Position(
            world = block.world.name,
            x = block.x,
            y = block.y,
            z = block.z
        )
        
        // Move to configure state
        val configState = PadCreatorState.Configure(
            regionId = state.regionId,
            padType = state.padType,
            blockPos = blockPos
        )
        
        PadCreatorStateManager.setState(player.uniqueId, configState)
        player.sendMessage(i18n.msg("gui.pad.block_saved", block.x, block.y, block.z))
        player.sendMessage(i18n.msg("gui.pad.configure_prompt"))
    }
}
