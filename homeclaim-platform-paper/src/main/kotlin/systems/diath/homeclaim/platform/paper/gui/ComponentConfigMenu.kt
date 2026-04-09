package systems.diath.homeclaim.platform.paper.gui

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import systems.diath.homeclaim.core.model.ComponentType

/**
 * GUI menu for configuring component settings during creation
 */
@Suppress("DEPRECATION", "UNUSED_PARAMETER")
class ComponentConfigMenu(
    private val creationService: BukkitComponentCreationService,
    private val regionId: String,
    private val componentType: ComponentType
) : GuiMenu(systems.diath.homeclaim.platform.paper.I18n().msg("gui.component.config.title"), rows = 4) {
    
    override fun render(player: Player) {
        // Confirm button
        inventory.setItem(31, ItemStack(Material.GREEN_WOOL).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName(i18n.msg("gui.component.config.confirm"))
                lore = listOf(i18n.msg("gui.component.config.confirm_lore"))
            }
        })
        
        // Cancel button
        inventory.setItem(35, ItemStack(Material.RED_WOOL).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName(i18n.msg("gui.component.config.cancel"))
            }
        })
    }
    
    override fun onClick(player: Player, slot: Int, item: ItemStack?, clickType: ClickType): Boolean {
        when (slot) {
            31 -> {
                player.closeInventory()
                player.sendMessage(i18n.msg("gui.component.configured"))
            }
            35 -> {
                player.closeInventory()
                player.sendMessage(i18n.msg("gui.component.cancelled"))
            }
        }
        return true
    }
}
