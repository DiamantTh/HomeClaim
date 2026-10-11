package systems.diath.homeclaim.platform.paper.command

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.core.service.ZoneService
import systems.diath.homeclaim.core.service.WorldAreaService
import systems.diath.homeclaim.platform.paper.I18n
import systems.diath.homeclaim.platform.paper.PaperWorldAreaResolver

class ZoneCommand(
    private val zoneService: ZoneService,
    private val regionService: RegionService,
    private val i18n: I18n,
    private val worldAreaService: WorldAreaService? = null
) {
    fun execute(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(i18n.msg("zone.player_only"))
            return true
        }
        
        if (args.isEmpty() || args[0] != "info") {
            sender.sendMessage(i18n.msg("zone.usage"))
            return true
        }
        
        showZoneInfo(sender)
        return true
    }
    
    private fun showZoneInfo(player: Player) {
        val loc = player.location
        val area = worldAreaService?.let {
            PaperWorldAreaResolver.resolveArea(it, loc.world.uid, loc.world.name, loc.blockX, loc.blockY, loc.blockZ)
        }
        if (area == null) {
            player.sendMessage(i18n.msg("zone.not_in_region"))
            return
        }
        val regionId = regionService.getRegionAt(area.id, loc.blockX, loc.blockY, loc.blockZ)
        
        if (regionId == null) {
            player.sendMessage(i18n.msg("zone.not_in_region"))
            return
        }
        
        val pos = Position(area.worldId, loc.blockX, loc.blockY, loc.blockZ)
        val zones = zoneService.getZonesAt(area.id, pos)
        
        if (zones.isEmpty()) {
            player.sendMessage(i18n.msg("zone.no_zones_found"))
            return
        }
        
        // Display first zone (highest priority)
        val zone = zones.first()
        
        player.sendMessage(i18n.msg("zone.separator"))
        player.sendMessage(i18n.msg("zone.info_id", zone.id.value))
        player.sendMessage(i18n.msg("zone.info_priority", zone.priority))
        player.sendMessage(i18n.msg("zone.info_default_flags"))
        
        val flags = zone.defaults.defaultFlags
        if (flags.isEmpty()) {
            player.sendMessage(i18n.msg("zone.no_flags_set"))
        } else {
            flags.entries.take(5).forEach { entry ->
                player.sendMessage(i18n.msg("zone.flag_entry", entry.key.value, entry.value))
            }
            if (flags.size > 5) {
                player.sendMessage(i18n.msg("zone.flags_more", flags.size - 5))
            }
        }
        
        player.sendMessage(i18n.msg("zone.info_locked_flags"))
        if (zone.lockedFlags.isEmpty()) {
            player.sendMessage(i18n.msg("zone.no_flags_locked"))
        } else {
            zone.lockedFlags.take(5).forEach { flagKey ->
                player.sendMessage(i18n.msg("zone.locked_flag_entry", flagKey.value))
            }
            if (zone.lockedFlags.size > 5) {
                player.sendMessage(i18n.msg("zone.flags_more", zone.lockedFlags.size - 5))
            }
        }
        
        player.sendMessage(i18n.msg("zone.separator"))
    }
}
