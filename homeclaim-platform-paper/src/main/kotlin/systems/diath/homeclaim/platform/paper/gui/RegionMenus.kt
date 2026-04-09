package systems.diath.homeclaim.platform.paper.gui

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.service.RegionService

class RegionListMenu(
    private val regionService: RegionService,
    private val guiManager: GuiManager
) : GuiMenu(systems.diath.homeclaim.platform.paper.I18n().msg("gui.region.list.title"), rows = 6) {
    
    private var regions: List<Region> = emptyList()
    private var currentPage = 0
    private val pageSize = 45
    
    override fun render(player: Player) {
        clearInventory()
        regions = regionService.listRegionsByOwner(player.uniqueId)
        
        if (regions.isEmpty()) {
            setItem(22, createItem(Material.BARRIER, i18n.msg("gui.region.list.none.title"),
                listOf(i18n.msg("gui.region.list.none.l1"))))
            return
        }
        
        val start = currentPage * pageSize
        val end = minOf(start + pageSize, regions.size)
        
        for (i in start until end) {
            val region = regions[i]
            val slot = i - start
            setItem(slot, createItem(Material.GRASS_BLOCK,
                i18n.msg("gui.region.list.item.title", region.metadata["name"] ?: region.id.value),
                listOf(
                    i18n.msg("gui.region.list.item.world", region.world),
                    i18n.msg("gui.region.list.item.shape", region.shape.name),
                    "",
                    i18n.msg("gui.region.list.item.manage_hint")
                )))
        }
        
        if (currentPage > 0) setItem(45, createItem(Material.ARROW, i18n.msg("gui.prev")))
        if (end < regions.size) setItem(53, createItem(Material.ARROW, i18n.msg("gui.next")))
        setItem(49, createItem(Material.BARRIER, i18n.msg("gui.close")))
    }
    
    override fun onClick(player: Player, slot: Int, item: ItemStack?, clickType: ClickType): Boolean {
        when (slot) {
            45 -> if (currentPage > 0) { currentPage--; render(player) }
            53 -> { val max = (regions.size - 1) / pageSize; if (currentPage < max) { currentPage++; render(player) } }
            49 -> guiManager.closeMenu(player)
            in 0..44 -> {
                val idx = currentPage * pageSize + slot
                if (idx < regions.size) guiManager.openMenu(player, RegionManageMenu(regions[idx], regionService, guiManager))
            }
        }
        return true
    }
}

class RegionManageMenu(
    private val region: Region,
    private val regionService: RegionService,
    private val guiManager: GuiManager
) : GuiMenu(systems.diath.homeclaim.platform.paper.I18n().msg("gui.region.manage.title", region.metadata["name"] ?: region.id.value.toString().take(8)), rows = 3) {
    
    override fun render(player: Player) {
        clearInventory()
        setItem(4, createItem(Material.MAP, i18n.msg("gui.region.manage.info.title"),
            listOf(
                i18n.msg("gui.region.manage.info.id", region.id.value),
                i18n.msg("gui.region.manage.info.world", region.world),
                i18n.msg("gui.region.manage.info.trusted", region.roles.trusted.size),
                i18n.msg("gui.region.manage.info.members", region.roles.members.size)
            )))
        setItem(10, createItem(Material.PLAYER_HEAD, i18n.msg("gui.region.manage.roles")))
        setItem(11, createItem(Material.EMERALD, i18n.msg("gui.region.manage.price"),
            listOf(i18n.msg("gui.region.manage.price.value", region.price.toInt()))))
        setItem(12, createItem(Material.WRITABLE_BOOK, i18n.msg("gui.region.manage.flags")))
        setItem(13, createItem(Material.HEAVY_WEIGHTED_PRESSURE_PLATE, i18n.msg("gui.region.manage.components")))
        setItem(14, createItem(Material.SUNFLOWER, i18n.msg("gui.region.manage.zones")))
        setItem(22, createItem(Material.ARROW, i18n.msg("gui.back")))
    }
    
    override fun onClick(player: Player, slot: Int, item: ItemStack?, clickType: ClickType): Boolean {
        when (slot) {
            10 -> guiManager.openMenu(player, RoleEditorMenu(region, regionService, guiManager))
            11 -> guiManager.openMenu(player, RegionPriceEditorMenu(region, regionService, guiManager))
            12 -> guiManager.openMenu(player, FlagEditorMenu(region, regionService, guiManager))
            13 -> guiManager.openMenu(player, ComponentManagerMenu(region, guiManager.componentService, regionService, guiManager))
            14 -> guiManager.openMenu(player, ZoneInfoMenu(region, guiManager.zoneService, guiManager))
            22 -> guiManager.openMenu(player, RegionListMenu(regionService, guiManager))
        }
        return true
    }
}
