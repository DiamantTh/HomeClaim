package systems.diath.homeclaim.core.ui

import java.util.UUID

/**
 * Click type for menu interactions (platform-agnostic).
 */
enum class MenuClickType {
    LEFT, RIGHT, MIDDLE, SHIFT_LEFT, SHIFT_RIGHT, DROP, CONTROL_DROP, NUMBER_KEY, DOUBLE_CLICK
}

/**
 * Platform-agnostic menu item representation.
 */
data class MenuItem(
    val material: String,  // "DIAMOND", "STONE", etc.
    val displayName: String,
    val lore: List<String> = emptyList(),
    val amount: Int = 1,
    val customData: Map<String, String> = emptyMap()  // For platform-specific data
)

/**
 * Platform-agnostic menu interface.
 * Implementations handle inventory display on specific platforms.
 */
interface Menu {
    val title: String
    val rows: Int
    val menuId: String  // Unique identifier for this menu instance
    
    /**
     * Render the menu for a player.
     * Called before opening or on refresh.
     */
    fun render(playerId: UUID)
    
    /**
     * Handle click event on menu slot.
     * @return true to cancel event, false to allow default behavior
     */
    fun onClick(playerId: UUID, slot: Int, clickType: MenuClickType): Boolean {
        return false
    }
    
    /**
     * Handle menu close.
     */
    fun onClose(playerId: UUID) {}
    
    /**
     * Get item at slot (for rendering).
     */
    fun getItem(slot: Int): MenuItem?
    
    /**
     * Set item at slot.
     */
    fun setItem(slot: Int, item: MenuItem?)
    
    /**
     * Clear all items.
     */
    fun clear()
}

/**
 * Base abstract implementation for platform-specific menus.
 */
abstract class AbstractMenu(
    override val title: String,
    override val rows: Int = 6
) : Menu {
    override val menuId: String = UUID.randomUUID().toString()
    
    protected val items: MutableMap<Int, MenuItem?> = (0 until rows * 9).associate { it to null as MenuItem? }.toMutableMap()
    
    override fun getItem(slot: Int): MenuItem? {
        return if (slot in 0 until rows * 9) items[slot] else null
    }
    
    override fun setItem(slot: Int, item: MenuItem?) {
        if (slot in 0 until rows * 9) {
            items[slot] = item
        }
    }
    
    override fun clear() {
        items.keys.forEach { items[it] = null }
    }
    
    protected fun createItem(
        material: String,
        name: String,
        lore: List<String> = emptyList(),
        amount: Int = 1,
        customData: Map<String, String> = emptyMap()
    ): MenuItem {
        return MenuItem(
            material = material,
            displayName = name,
            lore = lore,
            amount = amount,
            customData = customData
        )
    }
}
