package systems.diath.homeclaim.platform.paper.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import systems.diath.homeclaim.core.ui.AbstractMenu
import systems.diath.homeclaim.core.ui.MenuItem
import systems.diath.homeclaim.core.ui.MenuClickType
import java.util.UUID

/**
 * Bukkit-specific implementation of Menu interface.
 * Wraps Bukkit Inventory and provides platform-specific rendering.
 */
abstract class BukkitMenu(
    title: String,
    rows: Int = 6
) : AbstractMenu(title, rows) {
    
    @Suppress("DEPRECATION")
    private val bukkitInventory: Inventory = Bukkit.createInventory(null, rows * 9, title)
    
    /**
     * Convert MenuItem to Bukkit ItemStack.
     */
    protected fun menuItemToBukkit(item: MenuItem?): ItemStack? {
        if (item == null) return null
        
        return try {
            val material = Material.valueOf(item.material.uppercase())
            val itemStack = ItemStack(material, item.amount)
            val meta = itemStack.itemMeta ?: return itemStack
            
            meta.setDisplayName(item.displayName)
            if (item.lore.isNotEmpty()) {
                meta.lore = item.lore
            }
            
            // Store custom data in item meta (could use NBT if needed)
            if (item.customData.isNotEmpty()) {
                // For now, can extend with NBT implementation if needed
            }
            
            itemStack.itemMeta = meta
            itemStack
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Render menu to Bukkit inventory.
     */
    override fun render(playerId: UUID) {
        bukkitInventory.clear()
        
        items.forEach { (slot, menuItem) ->
            val bukitItem = menuItemToBukkit(menuItem)
            bukkitInventory.setItem(slot, bukitItem)
        }
    }
    
    /**
     * Open menu for player.
     */
    fun open(player: Player) {
        render(player.uniqueId)
        player.openInventory(bukkitInventory)
    }
    
    /**
     * Handle Bukkit click event and convert to core click type.
     */
    @Suppress("UNUSED_PARAMETER")
    fun handleBukkitClick(player: Player, slot: Int, item: ItemStack?, clickType: ClickType): Boolean {
        val coreClickType = when (clickType) {
            ClickType.LEFT -> MenuClickType.LEFT
            ClickType.RIGHT -> MenuClickType.RIGHT
            ClickType.MIDDLE -> MenuClickType.MIDDLE
            ClickType.SHIFT_LEFT -> MenuClickType.SHIFT_LEFT
            ClickType.SHIFT_RIGHT -> MenuClickType.SHIFT_RIGHT
            ClickType.DROP -> MenuClickType.DROP
            ClickType.CONTROL_DROP -> MenuClickType.CONTROL_DROP
            ClickType.NUMBER_KEY -> MenuClickType.NUMBER_KEY
            ClickType.DOUBLE_CLICK -> MenuClickType.DOUBLE_CLICK
            else -> MenuClickType.LEFT
        }
        
        return onClick(player.uniqueId, slot, coreClickType)
    }
}
