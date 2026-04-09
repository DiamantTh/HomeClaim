package systems.diath.homeclaim.platform.paper.command

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.core.service.ZoneService
import systems.diath.homeclaim.platform.paper.I18n

class ZoneCommand(
    private val zoneService: ZoneService,
    private val regionService: RegionService,
    private val i18n: I18n
) : CommandExecutor, TabCompleter {
    
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
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
    
    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return listOf("info").filter { it.startsWith(args[0], ignoreCase = true) }
        }
        return emptyList()
    }
    
    private fun showZoneInfo(player: Player) {
        val loc = player.location
        val worldName = loc.world.name
        val regionId = regionService.getRegionAt2D(worldName, loc.blockX, loc.blockZ)
        
        if (regionId == null) {
            player.sendMessage(i18n.msg("zone.not_in_region"))
            return
        }
        
        val pos = Position(worldName, loc.blockX, loc.blockY, loc.blockZ)
        val zones = zoneService.getZonesAt(worldName, pos)
        
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
