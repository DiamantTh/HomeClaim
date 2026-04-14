package systems.diath.homeclaim.platform.paper.command

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.core.economy.EconService
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionRole
import systems.diath.homeclaim.core.store.RatingRepository
import systems.diath.homeclaim.platform.paper.gui.GuiManager
import systems.diath.homeclaim.platform.paper.util.CommandRateLimiter
import systems.diath.homeclaim.platform.paper.util.InputValidator
import systems.diath.homeclaim.platform.paper.util.Permissions
import systems.diath.homeclaim.platform.paper.I18n
import systems.diath.homeclaim.platform.paper.plot.mutation.NoOpPlotMutationService
import systems.diath.homeclaim.platform.paper.plot.mutation.NoOpPlotResetService
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationService
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotResetReason
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotResetService
import systems.diath.homeclaim.core.mutation.WorldMutationBackend

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
    private val plotResetService: PlotResetService = NoOpPlotResetService,
    private val plotMutationService: PlotMutationService = NoOpPlotMutationService,
    private val ratingRepository: RatingRepository? = null,
    private val i18n: I18n = I18n(),
    private val mutationBackend: WorldMutationBackend? = null
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
            "merge", "m" -> {
                if (!Permissions.checkWithMessage(sender, Permissions.PLOT_MERGE)) return true
                handleMerge(sender, args.getOrNull(1))
            }
            "unlink", "u", "unmerge" -> {
                if (!Permissions.checkWithMessage(sender, Permissions.PLOT_UNLINK)) return true
                handleUnlink(sender, args.getOrNull(1))
            }
            "reset", "clear" -> {
                if (!Permissions.checkWithMessage(sender, Permissions.PLOT_RESET)) return true
                handleReset(sender)
            }
            "jobs", "job" -> {
                if (!Permissions.checkWithMessage(sender, Permissions.PLOT_JOBS)) return true
                handleJobs(sender, args)
            }
            "alias" -> {
                if (!Permissions.checkWithMessage(sender, Permissions.PLOT_ALIAS)) return true
                handleAlias(sender, args)
            }
            "desc", "description" -> {
                if (!Permissions.checkWithMessage(sender, Permissions.PLOT_DESCRIPTION)) return true
                handleDescription(sender, args)
            }
            else -> showHelp(sender)
        }
        
        return true
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
        player.sendMessage(i18n.msg("plot.auto_searching"))

        val current = currentRegion(player)
        if (current != null && current.owner == UNCLAIMED_UUID) {
            handleClaim(player)
            return
        }

        val worldName = player.world.name
        val nextPlot = regionService.listAllRegions()
            .asSequence()
            .filter { it.world == worldName && it.owner == UNCLAIMED_UUID }
            .minByOrNull { region ->
                val centerX = (region.bounds.minX + region.bounds.maxX) / 2.0
                val centerZ = (region.bounds.minZ + region.bounds.maxZ) / 2.0
                val dx = player.location.x - centerX
                val dz = player.location.z - centerZ
                (dx * dx) + (dz * dz)
            }

        if (nextPlot == null) {
            player.sendMessage("§cNo unclaimed plot was found in this world.")
            return
        }

        val centerX = (nextPlot.bounds.minX + nextPlot.bounds.maxX) / 2.0
        val centerZ = (nextPlot.bounds.minZ + nextPlot.bounds.maxZ) / 2.0
        val y = player.world.getHighestBlockYAt(centerX.toInt(), centerZ.toInt()) + 1.0

        player.teleportAsync(org.bukkit.Location(player.world, centerX, y, centerZ)).thenAccept { success ->
            if (!success) {
                player.sendMessage("§cTeleport to the selected plot failed.")
                return@thenAccept
            }
            org.bukkit.Bukkit.getScheduler().runTask(
                org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(PlotCommand::class.java),
                Runnable { handleClaim(player) }
            )
        }
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
        } else if (player.hasPermission(Permissions.PLOT_INFO_OWNER_NAME) || player.isOp) {
            org.bukkit.Bukkit.getOfflinePlayer(region.owner).name ?: region.owner.toString().take(8)
        } else {
            i18n.msg("plot.info_owner_redacted")
        }
        
        val noneText = i18n.msg("plot.info_none")
        
        fun resolveNames(uuids: Set<java.util.UUID>): String {
            if (uuids.isEmpty()) return noneText
            return uuids.mapNotNull { org.bukkit.Bukkit.getOfflinePlayer(it).name }
                .ifEmpty { listOf(noneText) }
                .joinToString(", ")
        }
        
        fun formatFlags(): String {
            if (region.flags.isEmpty()) return noneText
            val rendered = region.flags.entries.take(8).joinToString(", ") { (key, value) ->
                when (value) {
                    is systems.diath.homeclaim.core.model.PolicyValue.Bool -> "${key.value}: ${value.allowed}"
                    is systems.diath.homeclaim.core.model.PolicyValue.IntValue -> "${key.value}: ${value.value}"
                    is systems.diath.homeclaim.core.model.PolicyValue.Text -> "${key.value}: ${value.value}"
                }
            }
            return if (region.flags.size > 8) "$rendered (+${region.flags.size - 8})" else rendered
        }
        
        val biome = try { loc.block.biome.key.toString() } catch (_: Exception) { "?" }
        val width = region.bounds.maxX - region.bounds.minX
        val depth = region.bounds.maxZ - region.bounds.minZ
        val priceStr = if (region.price > 0) "${region.price}$" else i18n.msg("plot.info_not_for_sale")
        val playerRole = region.roles.resolve(player.uniqueId, region.owner)
        val canBuild = playerRole == RegionRole.OWNER || playerRole == RegionRole.TRUSTED
        val canBuildStr = if (canBuild) i18n.msg("plot.info_can_build_yes") else i18n.msg("plot.info_can_build_no")
        val ratingStr = if (ratingRepository != null) {
            val stats = ratingRepository.getStats(regionId)
            if (stats.totalRatings > 0) stats.toStarDisplay() else i18n.msg("plot.info_rating_no_ratings")
        } else {
            i18n.msg("plot.info_rating_no_ratings")
        }
        
        player.sendMessage(i18n.msg("plot.info_header"))
        player.sendMessage(i18n.msg("plot.info_id", regionId.value.toString()))
        player.sendMessage(i18n.msg("plot.info_world", region.world))
        player.sendMessage(i18n.msg("plot.info_size", width, depth))
        player.sendMessage(i18n.msg("plot.info_biome", biome))
        val aliasText = region.metadata["alias"] ?: noneText
        val descriptionText = region.metadata["description"] ?: noneText
        player.sendMessage(i18n.msg("plot.info_owner", ownerName))
        player.sendMessage(i18n.msg("plot.info_alias", aliasText))
        player.sendMessage(i18n.msg("plot.info_description", descriptionText))
        player.sendMessage(i18n.msg("plot.info_trusted", resolveNames(region.roles.trusted)))
        player.sendMessage(i18n.msg("plot.info_members", resolveNames(region.roles.members)))
        player.sendMessage(i18n.msg("plot.info_banned", resolveNames(region.roles.banned)))
        player.sendMessage(i18n.msg("plot.info_can_build", canBuildStr))
        player.sendMessage(i18n.msg("plot.info_rating", ratingStr))
        player.sendMessage(i18n.msg("plot.info_flags", formatFlags()))
        player.sendMessage(i18n.msg("plot.info_price", priceStr))
        player.sendMessage(i18n.msg("plot.info_footer"))
        
        if (region.owner != UNCLAIMED_UUID && Permissions.check(player, Permissions.PLOT_INFO_PLATFORM)) {
            val platformInfo = systems.diath.homeclaim.platform.paper.util.PlayerPlatformClassifier.classify(region.owner)
            player.sendMessage(i18n.msg("plot.info_uuid", region.owner.toString()))
            val platformLabel = if (platformInfo.isBedrock) i18n.msg("plot.info_platform_bedrock") else i18n.msg("plot.info_platform_java")
            player.sendMessage(i18n.msg("plot.info_platform", platformLabel))
        }
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
    
    private fun handleMerge(player: Player, directionArg: String?) {
        val region = currentRegion(player) ?: run {
            player.sendMessage(i18n.msg("plot.not_on_plot"))
            return
        }
        if (region.owner == UNCLAIMED_UUID) {
            player.sendMessage(i18n.msg("plot.merge_unclaimed"))
            return
        }
        if (region.owner != player.uniqueId && !Permissions.canEditAnyPlot(player)) {
            player.sendMessage(i18n.msg("permission.denied"))
            return
        }

        val direction = parseMergeDirection(directionArg) ?: run {
            player.sendMessage(i18n.msg("plot.merge_usage"))
            return
        }

        val sameOwnerRegions = regionService.listRegionsByOwner(region.owner)
            .filter { it.world == region.world && it.id != region.id }
        val selected = collectMergeTargets(region, sameOwnerRegions, direction)
        if (selected.isEmpty()) {
            player.sendMessage(i18n.msg("plot.merge_no_adjacent"))
            return
        }

        val groupIds = (listOf(region) + selected).mapNotNull { it.mergeGroupId }.toSet()
        val expanded = if (groupIds.isEmpty()) {
            emptyList()
        } else {
            sameOwnerRegions.filter { it.mergeGroupId != null && it.mergeGroupId in groupIds }
        }
        val mergedIds = (listOf(region) + selected + expanded).map { it.id }.toSet()
        runCatching {
            regionService.mergeRegions(mergedIds)
        }.onSuccess { mergeId ->
            player.sendMessage(i18n.msg("plot.merge_success", mergedIds.size - 1, mergeId.value.toString().take(8)))
        }.onFailure {
            player.sendMessage("§cPlot merge failed: ${it.message ?: "unknown error"}")
        }
    }

    private fun handleUnlink(player: Player, createRoadsArg: String?) {
        val region = currentRegion(player) ?: run {
            player.sendMessage(i18n.msg("plot.not_on_plot"))
            return
        }
        if (region.owner == UNCLAIMED_UUID) {
            player.sendMessage(i18n.msg("plot.unlink_unclaimed"))
            return
        }
        val mergeGroupId = region.mergeGroupId ?: run {
            player.sendMessage(i18n.msg("plot.unlink_not_merged"))
            return
        }
        if (region.owner != player.uniqueId && !Permissions.canEditAnyPlot(player)) {
            player.sendMessage(i18n.msg("permission.denied"))
            return
        }

        val createRoads = when (createRoadsArg?.lowercase()) {
            null -> true
            "true" -> true
            "false" -> false
            else -> {
                player.sendMessage(i18n.msg("plot.unlink_usage"))
                return
            }
        }

        val groupedRegions = regionService.listAllRegions()
            .filter { it.world == region.world && it.mergeGroupId == mergeGroupId }
        if (groupedRegions.size <= 1) {
            player.sendMessage(i18n.msg("plot.unlink_not_merged"))
            return
        }

        runCatching {
            val updatedRegions = groupedRegions.map { grouped ->
                val updated = grouped.copy(mergeGroupId = null)
                regionService.updateRegion(updated)
                updated
            }
            plotMutationService.handleRegionsUnlinked(updatedRegions, createRoads)
            updatedRegions
        }.onSuccess { updatedRegions ->
            player.sendMessage(i18n.msg("plot.unlink_success", updatedRegions.size, createRoads.toString()))
        }.onFailure {
            player.sendMessage("§cPlot unlink failed: ${it.message ?: "unknown error"}")
        }
    }

    private fun handleReset(player: Player) {
        val region = currentRegion(player) ?: run {
            player.sendMessage(i18n.msg("plot.not_on_plot"))
            return
        }
        if (region.owner == UNCLAIMED_UUID) {
            player.sendMessage(i18n.msg("plot.reset_unclaimed"))
            return
        }
        if (region.owner != player.uniqueId && !Permissions.canEditAnyPlot(player)) {
            player.sendMessage(i18n.msg("permission.denied"))
            return
        }

        val queued = runCatching {
            plotResetService.queueReset(region, PlotResetReason.MANUAL)
        }.getOrElse {
            player.sendMessage("§cPlot reset failed: ${it.message ?: "unknown error"}")
            return
        }
        if (queued) {
            player.sendMessage(i18n.msg("plot.reset_queued"))
        } else {
            player.sendMessage(i18n.msg("plot.reset_unavailable"))
        }
    }

    private fun handleJobs(player: Player, args: Array<out String>) {
        val action = args.getOrNull(1)?.lowercase()
        when (action) {
            "cancel", "stop" -> {
                val worldName = args.getOrNull(2)
                val cancelled = plotMutationService.cancelPendingJobs(worldName) +
                    plotResetService.cancelPendingJobs(worldName)
                val scope = worldName ?: "all worlds"
                player.sendMessage("\u00a76\u00a7m\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u00a7r")
                player.sendMessage("\u00a7aPlot Jobs Manager")
                player.sendMessage("\u00a77Cancelled: \u00a7e$cancelled job(s) in \u00a7f$scope")
                player.sendMessage("\u00a76\u00a7m\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u00a7r")
            }
            else -> {
                val backendLine = mutationBackend?.let { b ->
                    val caps = b.capabilities()
                    val flags = buildList {
                        if (caps.supportsAsyncApply) add("async")
                        if (caps.supportsChunkBatching) add("chunk-batching")
                        if (caps.supportsUndo) add("undo")
                    }.joinToString(", ").ifEmpty { "none" }
                    "\u00a77Backend: \u00a7e${b.backendId}\u00a77  caps: \u00a7f$flags"
                } ?: "\u00a77Backend: \u00a7cunknown"
                player.sendMessage("\u00a76\u00a7m\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u00a7r")
                player.sendMessage("\u00a7aPlot Jobs Manager")
                player.sendMessage(backendLine)
                player.sendMessage("\u00a79Usage:")
                player.sendMessage("\u00a7f  /plot jobs cancel [world]\u00a77 - Cancel queued jobs")
                player.sendMessage("\u00a79API:")
                player.sendMessage("\u00a7f  GET /api/v1/metrics\u00a77 - Full server metrics")
                player.sendMessage("\u00a7f  GET /api/v1/metrics/plots\u00a77 - Plot-specific metrics")
                player.sendMessage("\u00a7f  GET /api/v1/metrics/worlds/{name}\u00a77 - Per-world metrics")
                player.sendMessage("\u00a76\u00a7m\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u00a7r")
            }
        }
    }

    private fun handleAlias(player: Player, args: Array<out String>) {
        val region = currentRegion(player) ?: run {
            player.sendMessage(i18n.msg("plot.not_on_plot"))
            return
        }
        if (!canEditMetadata(player, region)) {
            player.sendMessage(i18n.msg("permission.denied"))
            return
        }

        val raw = args.drop(1).joinToString(" ").trim()
        if (raw.isEmpty()) {
            player.sendMessage(i18n.msg("plot.alias_usage"))
            return
        }

        val updatedMetadata = if (raw.equals("clear", ignoreCase = true) || raw == "-") {
            region.metadata - "alias"
        } else {
            val validation = InputValidator.validateName(raw, maxLength = 32)
            if (!validation.valid) {
                player.sendMessage(i18n.msg("plot.alias_invalid"))
                return
            }
            region.metadata + ("alias" to validation.sanitized!!)
        }

        regionService.updateRegion(region.copy(metadata = updatedMetadata))
        if (updatedMetadata.containsKey("alias")) {
            player.sendMessage(i18n.msg("plot.alias_set", updatedMetadata["alias"] ?: ""))
        } else {
            player.sendMessage(i18n.msg("plot.alias_cleared"))
        }
    }

    private fun handleDescription(player: Player, args: Array<out String>) {
        val region = currentRegion(player) ?: run {
            player.sendMessage(i18n.msg("plot.not_on_plot"))
            return
        }
        if (!canEditMetadata(player, region)) {
            player.sendMessage(i18n.msg("permission.denied"))
            return
        }

        val raw = args.drop(1).joinToString(" ").trim()
        if (raw.isEmpty()) {
            player.sendMessage(i18n.msg("plot.description_usage"))
            return
        }

        val updatedMetadata = if (raw.equals("clear", ignoreCase = true) || raw == "-") {
            region.metadata - "description"
        } else {
            if (raw.length > 120 || InputValidator.containsInjection(raw)) {
                player.sendMessage(i18n.msg("plot.description_invalid"))
                return
            }
            region.metadata + ("description" to InputValidator.sanitizeForDisplay(raw).take(120))
        }

        regionService.updateRegion(region.copy(metadata = updatedMetadata))
        if (updatedMetadata.containsKey("description")) {
            player.sendMessage(i18n.msg("plot.description_set", updatedMetadata["description"] ?: ""))
        } else {
            player.sendMessage(i18n.msg("plot.description_cleared"))
        }
    }

    private fun canEditMetadata(player: Player, region: Region): Boolean {
        if (Permissions.canEditAnyPlot(player)) return true
        val role = region.roles.resolve(player.uniqueId, region.owner)
        return role == RegionRole.OWNER || role == RegionRole.TRUSTED
    }

    private fun currentRegion(player: Player): Region? {
        val loc = player.location
        val worldId: systems.diath.homeclaim.core.model.WorldId = loc.world.name
        val regionId = regionService.getRegionAt2D(worldId, loc.blockX, loc.blockZ) ?: return null
        return regionService.getRegionById(regionId)
    }

    private fun collectMergeTargets(current: Region, candidates: List<Region>, direction: MergeDirection): List<Region> {
        return when (direction) {
            MergeDirection.ALL -> {
                val remaining = candidates.toMutableList()
                val queue = ArrayDeque<Region>()
                val result = linkedSetOf<Region>()
                queue.add(current)

                while (queue.isNotEmpty()) {
                    val base = queue.removeFirst()
                    val found = remaining.filter { adjacencyDirection(base.bounds, it.bounds) != null }
                    remaining.removeAll(found)
                    found.forEach {
                        if (result.add(it)) {
                            queue.add(it)
                        }
                    }
                }
                result.toList()
            }
            else -> candidates
                .mapNotNull { candidate ->
                    val match = adjacencyDirection(current.bounds, candidate.bounds)
                    if (match == direction) candidate to gapForDirection(current.bounds, candidate.bounds, direction) else null
                }
                .sortedBy { it.second }
                .map { it.first }
                .take(1)
        }
    }

    private fun adjacencyDirection(source: Bounds, other: Bounds, maxGap: Int = DEFAULT_MERGE_GAP): MergeDirection? {
        val eastGap = other.minX - source.maxX
        if (eastGap in 1..maxGap && overlaps(source.minZ, source.maxZ, other.minZ, other.maxZ)) return MergeDirection.EAST

        val westGap = source.minX - other.maxX
        if (westGap in 1..maxGap && overlaps(source.minZ, source.maxZ, other.minZ, other.maxZ)) return MergeDirection.WEST

        val southGap = other.minZ - source.maxZ
        if (southGap in 1..maxGap && overlaps(source.minX, source.maxX, other.minX, other.maxX)) return MergeDirection.SOUTH

        val northGap = source.minZ - other.maxZ
        if (northGap in 1..maxGap && overlaps(source.minX, source.maxX, other.minX, other.maxX)) return MergeDirection.NORTH

        return null
    }

    private fun gapForDirection(source: Bounds, other: Bounds, direction: MergeDirection): Int = when (direction) {
        MergeDirection.NORTH -> source.minZ - other.maxZ
        MergeDirection.SOUTH -> other.minZ - source.maxZ
        MergeDirection.WEST -> source.minX - other.maxX
        MergeDirection.EAST -> other.minX - source.maxX
        MergeDirection.ALL -> 0
    }

    private fun overlaps(firstMin: Int, firstMax: Int, secondMin: Int, secondMax: Int): Boolean {
        return firstMin <= secondMax && secondMin <= firstMax
    }

    private fun parseMergeDirection(input: String?): MergeDirection? {
        return when (input?.lowercase()) {
            null, "all", "auto" -> MergeDirection.ALL
            "north", "n" -> MergeDirection.NORTH
            "east", "e" -> MergeDirection.EAST
            "south", "s" -> MergeDirection.SOUTH
            "west", "w" -> MergeDirection.WEST
            else -> null
        }
    }

    private fun showHelp(player: Player) {
        player.sendMessage(i18n.msg("plot.help_header"))
        player.sendMessage(i18n.msg("plot.help_claim"))
        player.sendMessage(i18n.msg("plot.help_auto"))
        player.sendMessage(i18n.msg("plot.help_home"))
        player.sendMessage(i18n.msg("plot.help_info"))
        player.sendMessage(i18n.msg("plot.help_list"))
        player.sendMessage(i18n.msg("plot.help_visit"))
        player.sendMessage(i18n.msg("plot.help_merge"))
        player.sendMessage(i18n.msg("plot.help_unlink"))
        player.sendMessage(i18n.msg("plot.help_reset"))
        player.sendMessage(i18n.msg("plot.help_alias"))
        player.sendMessage(i18n.msg("plot.help_description"))
        player.sendMessage("§7/plot jobs cancel [world] - Cancel queued plot jobs")
    }
    
    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (args.size == 1) {
            return listOf("claim", "auto", "home", "info", "list", "visit", "merge", "unlink", "reset", "jobs", "alias", "desc")
                .filter { it.startsWith(args[0].lowercase()) }
        }
        if (args.size == 2 && args[0].lowercase() == "visit") {
            return org.bukkit.Bukkit.getOnlinePlayers()
                .map { it.name }
                .filter { it.lowercase().startsWith(args[1].lowercase()) }
        }
        if (args.size == 2 && args[0].lowercase() == "merge") {
            return listOf("all", "north", "east", "south", "west")
                .filter { it.startsWith(args[1].lowercase()) }
        }
        if (args.size == 2 && args[0].lowercase() == "unlink") {
            return listOf("true", "false")
                .filter { it.startsWith(args[1].lowercase()) }
        }
        if (args.size == 2 && args[0].lowercase() == "jobs") {
            return listOf("status", "cancel") +
                org.bukkit.Bukkit.getWorlds().map { it.name }
                    .filter { it.lowercase().startsWith(args[1].lowercase()) }
        }
        if (args.size == 3 && args[0].lowercase() == "jobs" && args[1].lowercase() == "cancel") {
            return org.bukkit.Bukkit.getWorlds().map { it.name }
                .filter { it.lowercase().startsWith(args[2].lowercase()) }
        }
        if (args.size == 2 && (args[0].lowercase() == "alias" || args[0].lowercase() == "desc" || args[0].lowercase() == "description")) {
            return listOf("clear")
                .filter { it.startsWith(args[1].lowercase()) }
        }
        return emptyList()
    }

    private enum class MergeDirection {
        NORTH, EAST, SOUTH, WEST, ALL
    }

    companion object {
        // UUID for unclaimed plots (all zeros)
        private val UNCLAIMED_UUID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000000")
        private const val DEFAULT_MERGE_GAP = 32
    }
}
