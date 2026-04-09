package systems.diath.homeclaim.platform.paper.command

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.core.economy.EconService
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.platform.paper.gui.GuiManager
import systems.diath.homeclaim.platform.paper.util.CommandRateLimiter
import systems.diath.homeclaim.platform.paper.util.Permissions
import systems.diath.homeclaim.platform.paper.I18n

/**
 * Command handler for /plot commands (P2-style).
 * 
 * /plot claim    - Claim the plot you're standing on
 * /plot auto     - Claim next available plot
 * /plot home     - Teleport to your plot
 * /plot info     - Info about current plot
 * /plot list     - List your plots
 * /plot visit <player> - Visit player's plot
 */
class PlotCommand(
    private val regionService: RegionService,
    private val guiManager: GuiManager,
    private val econService: EconService?,
    private val i18n: I18n = I18n()
) : CommandExecutor, TabCompleter {
    
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(i18n.msg("plot.only_players"))
            return true
        }
        
        val subCommand = args.getOrNull(0)?.lowercase() ?: "help"
        
        // Rate limiting check
        val (allowed, remainingMs) = CommandRateLimiter.checkAndRecord(sender, "plot.$subCommand")
        if (!allowed) {
            sender.sendMessage(i18n.msg("plot.ratelimit", CommandRateLimiter.getRemainingFormatted(remainingMs)))
            return true
        }
        
        when (subCommand) {
            "claim", "c" -> {
                if (!Permissions.checkWithMessage(sender, Permissions.PLOT_CLAIM)) return true
                handleClaim(sender)
            }
            "auto", "a" -> {
                if (!Permissions.checkWithMessage(sender, Permissions.PLOT_CLAIM)) return true
                handleAuto(sender)
            }
            "home", "h" -> {
                if (!Permissions.checkWithMessage(sender, Permissions.PLOT_HOME)) return true
                handleHome(sender)
            }
            "info", "i" -> {
                if (!Permissions.checkWithMessage(sender, Permissions.PLOT_INFO)) return true
                handleInfo(sender)
            }
            "list", "l" -> {
                if (!Permissions.checkWithMessage(sender, Permissions.PLOT_LIST)) return true
                handleList(sender)
            }
            "visit", "v" -> {
                if (!Permissions.checkWithMessage(sender, Permissions.PLOT_VISIT)) return true
                handleVisit(sender, args.getOrNull(1))
            }
            else -> showHelp(sender)
        }
        
        return true
    }
    
    companion object {
        // UUID for unclaimed plots (all zeros)
        private val UNCLAIMED_UUID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000000")
    }
    
    private fun handleClaim(player: Player) {
        val loc = player.location
        val worldId: systems.diath.homeclaim.core.model.WorldId = loc.world.name
        val regionId = regionService.getRegionAt2D(worldId, loc.blockX, loc.blockZ)
        
        if (regionId == null) {
            player.sendMessage(i18n.msg("plot.not_on_plot"))
            return
        }
        
        val region = regionService.getRegionById(regionId)
        if (region == null) {
            player.sendMessage(i18n.msg("plot.not_found"))
            return
        }
        
        // Check if unclaimed (owner is empty UUID)
        if (region.owner != UNCLAIMED_UUID) {
            player.sendMessage(i18n.msg("plot.already_claimed"))
            return
        }
        
        // Claim the plot
        val playerId: PlayerId = player.uniqueId
        if (econService != null) {
            val success = regionService.claimRegion(regionId, playerId, 0.0, econService)
            if (success) {
                player.sendMessage(i18n.msg("plot.claim_success"))
            } else {
                player.sendMessage(i18n.msg("plot.claim_failed"))
            }
        } else {
            player.sendMessage(i18n.msg("plot.economy_unavailable"))
        }
    }
    
    private fun handleAuto(player: Player) {
        // TODO: Find next unclaimed plot and teleport + claim
        player.sendMessage(i18n.msg("plot.auto_searching"))
        player.sendMessage(i18n.msg("plot.auto_not_implemented"))
    }
    
    private fun handleHome(player: Player) {
        val playerId: PlayerId = player.uniqueId
        val plots = regionService.listRegionsByOwner(playerId)
        
        if (plots.isEmpty()) {
            player.sendMessage(i18n.msg("plot.no_plots"))
            return
        }
        
        val firstPlot = plots.first()
        val world = org.bukkit.Bukkit.getWorld(firstPlot.world)
        if (world == null) {
            player.sendMessage(i18n.msg("plot.world_not_found"))
            return
        }
        
        // Teleport to center of plot
        val centerX = (firstPlot.bounds.minX + firstPlot.bounds.maxX) / 2.0
        val centerZ = (firstPlot.bounds.minZ + firstPlot.bounds.maxZ) / 2.0
        val y = world.getHighestBlockYAt(centerX.toInt(), centerZ.toInt()) + 1.0
        
        player.teleportAsync(org.bukkit.Location(world, centerX, y, centerZ))
        player.sendMessage(i18n.msg("plot.teleported"))
    }
    
    private fun handleInfo(player: Player) {
        val loc = player.location
        val worldId: systems.diath.homeclaim.core.model.WorldId = loc.world.name
        val regionId = regionService.getRegionAt2D(worldId, loc.blockX, loc.blockZ)
        
        if (regionId == null) {
            player.sendMessage(i18n.msg("plot.not_on_plot"))
            return
        }
        
        val region = regionService.getRegionById(regionId)
        if (region == null) {
            player.sendMessage(i18n.msg("plot.not_found"))
            return
        }
        
        val ownerName = if (region.owner == UNCLAIMED_UUID) {
            i18n.msg("plot.info_owner_unclaimed")
        } else {
            org.bukkit.Bukkit.getOfflinePlayer(region.owner).name ?: "Unbekannt"
        }
        
        player.sendMessage(i18n.msg("plot.info_header"))
        player.sendMessage(i18n.msg("plot.info_id", regionId.value.toString().take(8)))
        player.sendMessage(i18n.msg("plot.info_owner", ownerName))
        player.sendMessage(i18n.msg("plot.info_size", region.bounds.maxX - region.bounds.minX, region.bounds.maxZ - region.bounds.minZ))
        val priceStr = if (region.price > 0) "${region.price}$" else i18n.msg("plot.info_not_for_sale")
        player.sendMessage(i18n.msg("plot.info_price", priceStr))
    }
    
    private fun handleList(player: Player) {
        val playerId: PlayerId = player.uniqueId
        val plots = regionService.listRegionsByOwner(playerId)
        
        if (plots.isEmpty()) {
            player.sendMessage(i18n.msg("plot.list_no_plots"))
            return
        }
        
        player.sendMessage(i18n.msg("plot.list_header", plots.size))
        plots.forEachIndexed { index, region ->
            val world = org.bukkit.Bukkit.getWorld(region.world)
            val worldName = world?.name ?: i18n.msg("plot.list_unknown_world")
            val centerX = (region.bounds.minX + region.bounds.maxX) / 2
            val centerZ = (region.bounds.minZ + region.bounds.maxZ) / 2
            player.sendMessage(i18n.msg("plot.list_entry", index + 1, region.id.value, worldName, centerX, centerZ))
        }
        player.sendMessage(i18n.msg("plot.list_footer"))
    }
    
    private fun handleVisit(player: Player, targetName: String?) {
        if (targetName == null) {
            player.sendMessage(i18n.msg("plot.visit_usage"))
            return
        }
        
        // Validate player name input
        val validation = systems.diath.homeclaim.platform.paper.util.InputValidator.validatePlayerName(targetName)
        if (!validation.valid) {
            player.sendMessage(i18n.msg("plot.visit_invalid_name"))
            return
        }
        
        val target = org.bukkit.Bukkit.getOfflinePlayer(validation.sanitized!!)
        if (!target.hasPlayedBefore() && !target.isOnline) {
            player.sendMessage(i18n.msg("plot.visit_player_not_found"))
            return
        }
        
        val targetId: PlayerId = target.uniqueId
        val plots = regionService.listRegionsByOwner(targetId)
        
        if (plots.isEmpty()) {
            player.sendMessage(i18n.msg("plot.visit_no_plots", target.name ?: "unknown"))
            return
        }
        
        val firstPlot = plots.first()
        val world = org.bukkit.Bukkit.getWorld(firstPlot.world)
        if (world == null) {
            player.sendMessage(i18n.msg("plot.visit_world_not_found"))
            return
        }
        
        val centerX = (firstPlot.bounds.minX + firstPlot.bounds.maxX) / 2.0
        val centerZ = (firstPlot.bounds.minZ + firstPlot.bounds.maxZ) / 2.0
        val y = world.getHighestBlockYAt(centerX.toInt(), centerZ.toInt()) + 1.0
        
        player.teleportAsync(org.bukkit.Location(world, centerX, y, centerZ))
        player.sendMessage(i18n.msg("plot.visit_success", target.name ?: "unknown"))
    }
    
    private fun showHelp(player: Player) {
        player.sendMessage(i18n.msg("plot.help_header"))
        player.sendMessage(i18n.msg("plot.help_claim"))
        player.sendMessage(i18n.msg("plot.help_auto"))
        player.sendMessage(i18n.msg("plot.help_home"))
        player.sendMessage(i18n.msg("plot.help_info"))
        player.sendMessage(i18n.msg("plot.help_list"))
        player.sendMessage(i18n.msg("plot.help_visit"))
    }
    
    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (args.size == 1) {
            return listOf("claim", "auto", "home", "info", "list", "visit")
                .filter { it.startsWith(args[0].lowercase()) }
        }
        if (args.size == 2 && args[0].lowercase() == "visit") {
            return org.bukkit.Bukkit.getOnlinePlayers()
                .map { it.name }
                .filter { it.lowercase().startsWith(args[1].lowercase()) }
        }
        return emptyList()
    }
}
