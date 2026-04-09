package systems.diath.homeclaim.platform.paper.inventory

import org.bukkit.inventory.ItemStack as BukkitItemStack
import org.bukkit.inventory.Inventory as BukkitInventory
import org.bukkit.enchantments.Enchantment
import org.bukkit.Material
import systems.diath.homeclaim.core.inventory.ItemStack
import systems.diath.homeclaim.core.inventory.Inventory
import systems.diath.homeclaim.core.inventory.PlayerInventory as CorePlayerInventory

/**
 * Convert core ItemStack to Bukkit ItemStack.
 */
@Suppress("DEPRECATION")
fun ItemStack.toBukkit(): BukkitItemStack {
    val material = try {
        Material.valueOf(this.type.uppercase())
    } catch (e: Exception) {
        Material.AIR
    }
    
    val item = BukkitItemStack(material, this.amount)
    val meta = item.itemMeta ?: return item
    
    if (displayName != null) {
        meta.setDisplayName(displayName)
    }
    
    if (lore.isNotEmpty()) {
        meta.lore = lore
    }
    
    item.itemMeta = meta
    
    // Add enchantments
    enchantments.forEach { (enchant, level) ->
        val enchantment = Enchantment.getByName(enchant)
        if (enchantment != null) {
            item.addUnsafeEnchantment(enchantment, level)
        }
    }
    
    return item
}

/**
 * Convert Bukkit ItemStack to core ItemStack.
 */
@Suppress("DEPRECATION")
fun BukkitItemStack?.toCore(): ItemStack? {
    if (this == null || type == Material.AIR) return null
    
    val meta = itemMeta
    
    return ItemStack(
        type = type.name,
        amount = amount,
        displayName = meta?.displayName,
        lore = meta?.lore ?: emptyList(),
        enchantments = enchantments.mapKeys { it.key.name }
    )
}

/**
 * Bukkit implementation of Inventory.
 */
class BukkitInventoryAdapter(private val inventory: BukkitInventory) : Inventory {
    override val size: Int = inventory.size
    override val title: String? = null // Title property removed in Paper 1.21
    
    override fun getItem(slot: Int): ItemStack? {
        return inventory.getItem(slot).toCore()
    }
    
    override fun setItem(slot: Int, item: ItemStack?) {
        inventory.setItem(slot, item?.toBukkit())
    }
    
    override fun addItem(item: ItemStack): Int {
        val result = inventory.addItem(item.toBukkit())
        return result.values.sumOf { it.amount }
    }
    
    override fun removeItem(item: ItemStack) {
        inventory.removeItem(item.toBukkit())
    }
    
    override fun clear() {
        inventory.clear()
    }
    
    override fun getContents(): List<ItemStack?> {
        return inventory.contents.map { it.toCore() }
    }
}

/**
 * Bukkit implementation of PlayerInventory.
 */
class BukkitPlayerInventoryAdapter(private val inventory: org.bukkit.inventory.PlayerInventory) : CorePlayerInventory {
    override val size: Int = inventory.size
    override val title: String? = null
    
    override fun getItem(slot: Int): ItemStack? {
        return inventory.getItem(slot).toCore()
    }
    
    override fun setItem(slot: Int, item: ItemStack?) {
        inventory.setItem(slot, item?.toBukkit())
    }
    
    override fun addItem(item: ItemStack): Int {
        val result = inventory.addItem(item.toBukkit())
        return result.values.sumOf { it.amount }
    }
    
    override fun removeItem(item: ItemStack) {
        inventory.removeItem(item.toBukkit())
    }
    
    override fun clear() {
        inventory.clear()
    }
    
    override fun getContents(): List<ItemStack?> {
        return inventory.contents.map { it.toCore() }
    }
    
    override fun getHelmet(): ItemStack? {
        return inventory.helmet.toCore()
    }
    
    override fun setHelmet(item: ItemStack?) {
        inventory.helmet = item?.toBukkit()
    }
    
    override fun getChestplate(): ItemStack? {
        return inventory.chestplate.toCore()
    }
    
    override fun setChestplate(item: ItemStack?) {
        inventory.chestplate = item?.toBukkit()
    }
    
    override fun getLeggings(): ItemStack? {
        return inventory.leggings.toCore()
    }
    
    override fun setLeggings(item: ItemStack?) {
        inventory.leggings = item?.toBukkit()
    }
    
    override fun getBoots(): ItemStack? {
        return inventory.boots.toCore()
    }
    
    override fun setBoots(item: ItemStack?) {
        inventory.boots = item?.toBukkit()
    }
}
