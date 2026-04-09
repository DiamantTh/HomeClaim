package systems.diath.homeclaim.platform.paper.gui

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import systems.diath.homeclaim.core.model.PolicyValue
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.policy.FlagCatalog
import systems.diath.homeclaim.core.service.RegionService

class FlagEditorMenu(
    private var region: Region,
    private val regionService: RegionService,
    private val guiManager: GuiManager
) : GuiMenu(systems.diath.homeclaim.platform.paper.I18n().msg("gui.flag.title"), rows = 4) {
    
    /** Reload region from service to get fresh data after mutations */
    private fun refreshRegion() {
        regionService.getRegionById(region.id)?.let { region = it }
    }
    
    private val flags = listOf(
        FlagCatalog.BUILD to "Build",
        FlagCatalog.BREAK to "Break",
        FlagCatalog.INTERACT_BLOCK to "Interact",
        FlagCatalog.PVP to "PVP",
        FlagCatalog.FIRE_SPREAD to "Fire",
        FlagCatalog.EXPLOSION_DAMAGE to "Explosion"
    )
    
    override fun render(player: Player) {
        clearInventory()
        setItem(4, createItem(Material.WRITABLE_BOOK, i18n.msg("gui.flag.header")))
        
        flags.forEachIndexed { index, (flag, name) ->
            val value = region.flags[flag] as? PolicyValue.Bool
            val enabled = value?.allowed ?: false
            val mat = if (enabled) Material.LIME_DYE else Material.GRAY_DYE
            setItem(9 + index, createItem(mat, i18n.msg("gui.flag.item", name),
                listOf(
                    if (enabled) i18n.msg("gui.flag.enabled") else i18n.msg("gui.flag.disabled"),
                    "",
                    i18n.msg("gui.flag.toggle_hint")
                )))
        }

        setItem(31, createItem(Material.ARROW, i18n.msg("gui.back")))
    }
    
    override fun onClick(player: Player, slot: Int, item: ItemStack?, clickType: ClickType): Boolean {
        when {
            slot == 31 -> guiManager.openMenu(player, RegionManageMenu(region, regionService, guiManager))
            slot in 9..14 -> {
                val index = slot - 9
                if (index < flags.size) {
                    val (flag, name) = flags[index]
                    val currentValue = region.flags[flag] as? PolicyValue.Bool
                    val newValue = PolicyValue.Bool(!(currentValue?.allowed ?: false))
                    regionService.updateRegion(region.copy(flags = region.flags + (flag to newValue)))
                    refreshRegion()
                    val state = if (newValue.allowed) i18n.msg("gui.flag.enabled") else i18n.msg("gui.flag.disabled")
                    player.sendMessage(i18n.msg("gui.flag.changed", name, state))
                    render(player)
                }
            }
        }
        return true
    }
}
