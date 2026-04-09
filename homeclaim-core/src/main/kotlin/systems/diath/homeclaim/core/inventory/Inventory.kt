package systems.diath.homeclaim.core.inventory

import java.util.UUID

/**
 * Item abstraction - platform-independent.
 */
data class ItemStack(
    val type: String,  // "DIAMOND_PICKAXE", "STONE", etc.
    val amount: Int = 1,
    val displayName: String? = null,
    val lore: List<String> = emptyList(),
    val enchantments: Map<String, Int> = emptyMap(),
    val customData: Map<String, Any> = emptyMap()
)

/**
 * Inventory abstraction.
 */
interface Inventory {
    val size: Int
    val title: String?
    
    /**
     * Get item at slot.
     */
    fun getItem(slot: Int): ItemStack?
    
    /**
     * Set item at slot.
     */
    fun setItem(slot: Int, item: ItemStack?)
    
    /**
     * Add item to inventory.
     * @return amount that couldn't fit
     */
    fun addItem(item: ItemStack): Int
    
    /**
     * Remove item from inventory.
     */
    fun removeItem(item: ItemStack)
    
    /**
     * Clear inventory.
     */
    fun clear()
    
    /**
     * Get all items.
     */
    fun getContents(): List<ItemStack?>
    
    /**
     * Check if inventory contains item.
     */
    fun contains(type: String, amount: Int = 1): Boolean {
        var count = 0
        for (item in getContents()) {
            if (item?.type?.uppercase() == type.uppercase()) {
                count += item.amount
            }
        }
        return count >= amount
    }
}

/**
 * Player inventory abstraction.
 */
interface PlayerInventory : Inventory {
    
    /**
     * Get helmet slot.
     */
    fun getHelmet(): ItemStack?
    
    /**
     * Set helmet slot.
     */
    fun setHelmet(item: ItemStack?)
    
    /**
     * Get chestplate slot.
     */
    fun getChestplate(): ItemStack?
    
    /**
     * Set chestplate slot.
     */
    fun setChestplate(item: ItemStack?)
    
    /**
     * Get leggings slot.
     */
    fun getLeggings(): ItemStack?
    
    /**
     * Set leggings slot.
     */
    fun setLeggings(item: ItemStack?)
    
    /**
     * Get boots slot.
     */
    fun getBoots(): ItemStack?
    
    /**
     * Set boots slot.
     */
    fun setBoots(item: ItemStack?)
}

/**
 * In-memory inventory implementation (for testing).
 */
open class SimpleInventory(
    override val size: Int = 36,
    override val title: String? = null
) : Inventory {
    private val items: MutableMap<Int, ItemStack?> = (0 until size).associate { it to null as ItemStack? }.toMutableMap()
    
    override fun getItem(slot: Int): ItemStack? {
        return if (slot in 0 until size) items[slot] else null
    }
    
    override fun setItem(slot: Int, item: ItemStack?) {
        if (slot in 0 until size) {
            items[slot] = item
        }
    }
    
    override fun addItem(item: ItemStack): Int {
        var remaining = item.amount
        
        // Try to add to existing stacks
        for (i in 0 until size) {
            val existing = items[i]
            if (existing?.type == item.type) {
                val canAdd = minOf(remaining, 64 - existing.amount)
                items[i] = existing.copy(amount = existing.amount + canAdd)
                remaining -= canAdd
                if (remaining <= 0) return 0
            }
        }
        
        // Add to empty slots
        for (i in 0 until size) {
            if (items[i] == null && remaining > 0) {
                val amount = minOf(remaining, 64)
                items[i] = item.copy(amount = amount)
                remaining -= amount
                if (remaining <= 0) return 0
            }
        }
        
        return remaining
    }
    
    override fun removeItem(item: ItemStack) {
        var remaining = item.amount
        
        for (i in 0 until size) {
            val existing = items[i]
            if (existing?.type == item.type && remaining > 0) {
                val remove = minOf(remaining, existing.amount)
                remaining -= remove
                
                if (remove == existing.amount) {
                    items[i] = null
                } else {
                    items[i] = existing.copy(amount = existing.amount - remove)
                }
            }
        }
    }
    
    override fun clear() {
        items.keys.forEach { items[it] = null }
    }
    
    override fun getContents(): List<ItemStack?> {
        return items.values.toList()
    }
}

/**
 * Simple player inventory implementation.
 */
class SimplePlayerInventory : SimpleInventory(36, null), PlayerInventory {
    private var helmet: ItemStack? = null
    private var chestplate: ItemStack? = null
    private var leggings: ItemStack? = null
    private var boots: ItemStack? = null
    
    override fun getHelmet(): ItemStack? = helmet
    override fun setHelmet(item: ItemStack?) { helmet = item }
    
    override fun getChestplate(): ItemStack? = chestplate
    override fun setChestplate(item: ItemStack?) { chestplate = item }
    
    override fun getLeggings(): ItemStack? = leggings
    override fun setLeggings(item: ItemStack?) { leggings = item }
    
    override fun getBoots(): ItemStack? = boots
    override fun setBoots(item: ItemStack?) { boots = item }
}
