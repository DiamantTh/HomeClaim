package systems.diath.homeclaim.platform.paper.gui

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.service.RegionService

/**
 * GUI zum Verwalten des Region-Verkaufspreises
 */
class RegionPriceEditorMenu(
    private val region: Region,
    private val regionService: RegionService,
    private val guiManager: GuiManager
) : GuiMenu(systems.diath.homeclaim.platform.paper.I18n().msg("gui.price.title", region.price.toInt()), rows = 3) {
    
    private var currentPrice = region.price
    
    override fun render(player: Player) {
        clearInventory()
        
        // Info
        setItem(4, createItem(Material.SUNFLOWER, i18n.msg("gui.price.header"),
            listOf(
                i18n.msg("gui.price.current", currentPrice.toInt()),
                i18n.msg("gui.price.region_id", region.id.value.toString().take(8))
            )))
        
        // Schnell-Buttons
        setItem(10, createItem(Material.EMERALD, i18n.msg("gui.price.add_100"), listOf(i18n.msg("gui.price.add_hint"))))
        setItem(12, createItem(Material.REDSTONE, i18n.msg("gui.price.sub_100"), listOf(i18n.msg("gui.price.sub_hint"))))
        setItem(14, createItem(Material.BARRIER, i18n.msg("gui.price.zero"), listOf(i18n.msg("gui.price.disable_hint"))))
        
        // Bestätigen/Zurück
        setItem(22, createItem(Material.LIME_DYE, i18n.msg("gui.save"), listOf(i18n.msg("gui.price.value", currentPrice.toInt()))))
        setItem(26, createItem(Material.ARROW, i18n.msg("gui.back")))
    }
    
    override fun onClick(player: Player, slot: Int, item: ItemStack?, clickType: ClickType): Boolean {
        when (slot) {
            10 -> { currentPrice += 100; render(player) }
            12 -> { currentPrice = maxOf(0.0, currentPrice - 100); render(player) }
            14 -> { currentPrice = 0.0; render(player) }
            22 -> {
                val updated = region.copy(price = currentPrice)
                regionService.updateRegion(updated)
                player.sendMessage(i18n.msg("gui.price.saved", currentPrice.toInt()))
                guiManager.openMenu(player, RegionManageMenu(region, regionService, guiManager))
            }
            26 -> guiManager.openMenu(player, RegionManageMenu(region, regionService, guiManager))
        }
        return true
    }
}
