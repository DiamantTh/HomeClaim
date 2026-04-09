package systems.diath.homeclaim.platform.paper.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import systems.diath.homeclaim.platform.paper.I18n

/**
 * Base class for inventory-based GUI menus.
 */
abstract class GuiMenu(
    val title: String,
    val rows: Int = 6
) {
    protected val i18n = I18n()
    
    @Suppress("DEPRECATION")
    val inventory: Inventory = Bukkit.createInventory(null, rows * 9, title)
    
    abstract fun render(player: Player)
    
    open fun onClick(player: Player, slot: Int, item: ItemStack?, clickType: ClickType): Boolean {
        return false // true = cancel event
    }
    
    open fun onClose(player: Player) {}
    
    fun open(player: Player) {
        render(player)
        player.openInventory(inventory)
    }
    
    protected fun setItem(slot: Int, item: ItemStack?) {
        inventory.setItem(slot, item)
    }
    
    protected fun clearInventory() {
        inventory.clear()
    }
    
    protected fun createItem(
        material: Material,
        name: String,
        lore: List<String> = emptyList(),
        amount: Int = 1
    ): ItemStack {
        val item = ItemStack(material, amount)
        val meta = item.itemMeta ?: return item
        @Suppress("DEPRECATION")
        meta.setDisplayName(name)
        if (lore.isNotEmpty()) {
            @Suppress("DEPRECATION")
            meta.lore = lore
        }
        item.itemMeta = meta
        return item
    }
}

