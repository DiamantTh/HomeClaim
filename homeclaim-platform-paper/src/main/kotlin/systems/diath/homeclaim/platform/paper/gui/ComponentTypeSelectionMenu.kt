package systems.diath.homeclaim.platform.paper.gui

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import systems.diath.homeclaim.core.model.ComponentType
import systems.diath.homeclaim.core.ui.ComponentCreationEvent
import java.util.UUID

/**
 * GUI menu for selecting component type during creation
 */
@Suppress("DEPRECATION", "UNUSED_PARAMETER")
class ComponentTypeSelectionMenu(
    private val creationService: BukkitComponentCreationService,
    private val regionId: String,
    private val creatorId: UUID
) : GuiMenu(systems.diath.homeclaim.platform.paper.I18n().msg("gui.component.type.title"), rows = 3) {
    
    override fun render(player: Player) {
        // Elevator option
        inventory.setItem(11, ItemStack(Material.REDSTONE_BLOCK).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName(i18n.msg("gui.component.type.elevator"))
                lore = listOf(i18n.msg("gui.component.type.elevator_lore"))
            }
        })
        
        // Teleport option
        inventory.setItem(13, ItemStack(Material.ENDER_PEARL).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName(i18n.msg("gui.component.type.teleport"))
                lore = listOf(i18n.msg("gui.component.type.teleport_lore"))
            }
        })
        
        // Cancel
        inventory.setItem(26, ItemStack(Material.BARRIER).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName(i18n.msg("gui.component.type.cancel"))
            }
        })
    }
    
    override fun onClick(player: Player, slot: Int, item: ItemStack?, clickType: ClickType): Boolean {
        when (slot) {
            11 -> {
                creationService.handleEvent(ComponentCreationEvent.TypeSelected(
                    creatorId, ComponentType.ELEVATOR_PAD
                ))
                player.closeInventory()
            }
            13 -> {
                creationService.handleEvent(ComponentCreationEvent.TypeSelected(
                    creatorId, ComponentType.TELEPORT_PAD
                ))
                player.closeInventory()
            }
            26 -> {
                player.closeInventory()
                player.sendMessage(i18n.msg("gui.component_creation.cancelled"))
            }
        }
        return true
    }
}
