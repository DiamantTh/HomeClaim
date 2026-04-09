package systems.diath.homeclaim.platform.paper.gui

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.core.service.ZoneService
import systems.diath.homeclaim.core.service.ComponentService
import systems.diath.homeclaim.platform.paper.listener.PlayerCleanupManager
import systems.diath.homeclaim.platform.paper.util.SafeEventHandler
import java.util.concurrent.ConcurrentHashMap

/**
 * Central GUI manager that tracks open menus and handles events.
 */
class GuiManager(
    val regionService: RegionService,
    val zoneService: ZoneService,
    val componentService: ComponentService
) : Listener {
    private val activeMenus = ConcurrentHashMap<Player, GuiMenu>()
    private val plugin by lazy { Bukkit.getPluginManager().getPlugin("HomeClaim")!! }
    
    private fun isFolia(): Boolean {
        return try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
    
    init {
        // Register cleanup for player quit
        PlayerCleanupManager.registerCleanup("GuiManager") { uuid ->
            val player = Bukkit.getPlayer(uuid)
            if (player != null) {
                activeMenus.remove(player)?.onClose(player)
            } else {
                // Player already offline, just remove from map by UUID
                activeMenus.keys.removeIf { it.uniqueId == uuid }
            }
        }
    }
    
    fun openMenu(player: Player, menu: GuiMenu) {
        activeMenus[player] = menu
        menu.open(player)
    }
    
    fun closeMenu(player: Player) {
        activeMenus.remove(player)
        player.closeInventory()
    }
    
    /**
     * Close all open menus (used during shutdown).
     * On Folia, each player.closeInventory() must run on the player's entity scheduler.
     */
    fun closeAll() {
        val players = activeMenus.keys.toList()
        for (player in players) {
            try {
                val menu = activeMenus.remove(player)
                menu?.onClose(player)
                
                // Close inventory on proper thread
                if (isFolia()) {
                    // Folia: Must run on entity scheduler
                    player.scheduler.run(plugin, { _ -> player.closeInventory() }, null)
                } else {
                    // Paper/Spigot: Can call directly
                    player.closeInventory()
                }
            } catch (_: Exception) {
                // Player might be offline
            }
        }
        activeMenus.clear()
    }
    
    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) = SafeEventHandler.handle(event, "GuiManager.onClick") {
        val player = event.whoClicked as? Player ?: return@handle
        val menu = activeMenus[player] ?: return@handle
        
        if (event.inventory != menu.inventory) return@handle
        
        val cancel = menu.onClick(player, event.slot, event.currentItem, event.click)
        if (cancel) {
            event.isCancelled = true
        }
    }
    
    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) = SafeEventHandler.handle(event, "GuiManager.onClose", failSafe = false) {
        val player = event.player as? Player ?: return@handle
        val menu = activeMenus.remove(player) ?: return@handle
        menu.onClose(player)
    }
}
