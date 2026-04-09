package systems.diath.homeclaim.platform.paper.gui

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.service.ZoneService
import systems.diath.homeclaim.core.model.Position

class ZoneInfoMenu(
    private val region: Region,
    private val zoneService: ZoneService,
    private val guiManager: GuiManager
) : GuiMenu(systems.diath.homeclaim.platform.paper.I18n().msg("gui.zone.title"), rows = 4) {
    
    private var zones: List<systems.diath.homeclaim.core.model.Zone> = emptyList()
    private var currentPage = 0
    private val pageSize = 18
    
    override fun render(player: Player) {
        clearInventory()
        
        // Hole Zonen die diese Region beeinflussen
        // Nutze einen Punkt aus der Region als Referenz (Mitte des Bounding-Boxen)
        val centerX = (region.bounds.minX + region.bounds.maxX) / 2
        val centerY = (region.bounds.minY + region.bounds.maxY) / 2
        val centerZ = (region.bounds.minZ + region.bounds.maxZ) / 2
        val centerPos = Position(region.world, centerX, centerY, centerZ)
        zones = zoneService.getZonesAt(region.world, centerPos)
        
        if (zones.isEmpty()) {
            setItem(13, createItem(Material.BARRIER, i18n.msg("gui.zone.none.title"),
                listOf(i18n.msg("gui.zone.none.l1"))))
        } else {
            val start = currentPage * pageSize
            val end = minOf(start + pageSize, zones.size)
            
            for (i in start until end) {
                val zone = zones[i]
                val slot = i - start
                val lockedFlags = if (zone.lockedFlags.isNotEmpty()) {
                    zone.lockedFlags.take(3).joinToString(", ") { it.value.take(3) }
                } else {
                    i18n.msg("gui.zone.none_short")
                }

                val tags = if (zone.tags.isEmpty()) i18n.msg("gui.zone.none_short") else zone.tags.take(2).joinToString(", ")
                setItem(slot, createItem(
                    Material.PAPER,
                    i18n.msg("gui.zone.item.title", i + 1),
                    listOf(
                        i18n.msg("gui.zone.item.priority", zone.priority),
                        i18n.msg("gui.zone.item.locked_flags", lockedFlags),
                        i18n.msg("gui.zone.item.shape", zone.shape.name),
                        i18n.msg("gui.zone.item.tags", tags)
                    )
                ))
            }
        }
        
        // Pagination
        if (zones.size > pageSize) {
            if (currentPage > 0) setItem(45, createItem(Material.ARROW, i18n.msg("gui.prev")))
            val maxPage = (zones.size - 1) / pageSize
            if (currentPage < maxPage) setItem(53, createItem(Material.ARROW, i18n.msg("gui.next")))
        }
        
        setItem(49, createItem(Material.ARROW, i18n.msg("gui.back")))
    }
    
    override fun onClick(player: Player, slot: Int, item: ItemStack?, clickType: ClickType): Boolean {
        when (slot) {
            45 -> if (currentPage > 0) { currentPage--; render(player) }
            53 -> { 
                val max = (zones.size - 1) / pageSize
                if (currentPage < max) { currentPage++; render(player) }
            }
            49 -> guiManager.openMenu(player, RegionManageMenu(region, guiManager.regionService, guiManager))
        }
        return true
    }
}
