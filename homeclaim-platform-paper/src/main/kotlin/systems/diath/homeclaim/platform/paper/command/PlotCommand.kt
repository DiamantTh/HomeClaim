package systems.diath.homeclaim.platform.paper.command

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.World
import org.bukkit.entity.Player
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.core.service.RegionOperationLimitService
import systems.diath.homeclaim.core.service.RegionAdminService
import systems.diath.homeclaim.core.service.RegionMutationContext
import systems.diath.homeclaim.core.service.OperationStatus
import systems.diath.homeclaim.core.service.AreaService
import systems.diath.homeclaim.core.service.WorldAreaService
import systems.diath.homeclaim.core.service.AuditEntries
import systems.diath.homeclaim.core.service.AuditService
import systems.diath.homeclaim.core.service.AuditStatus
import systems.diath.homeclaim.core.economy.EconService
import systems.diath.homeclaim.core.economy.SimpleEconService
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionRole
import systems.diath.homeclaim.core.model.MergeGroupId
import systems.diath.homeclaim.core.store.RatingRepository
import systems.diath.homeclaim.platform.paper.gui.GuiManager
import systems.diath.homeclaim.platform.paper.gui.RegionListMenu
import systems.diath.homeclaim.platform.paper.gui.showOperationFailure
import systems.diath.homeclaim.platform.paper.PaperWorldAreaResolver
import systems.diath.homeclaim.platform.paper.util.CommandRateLimiter
import systems.diath.homeclaim.platform.paper.util.InputValidator
import systems.diath.homeclaim.platform.paper.util.Permissions
import systems.diath.homeclaim.platform.paper.I18n
import systems.diath.homeclaim.platform.paper.plot.mutation.NoOpPlotMutationService
import systems.diath.homeclaim.platform.paper.plot.mutation.NoOpPlotResetService
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationService
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotResetCompletionStatus
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotResetReason
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotResetService
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationJobRecord
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationJobStatus
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationJobStore
import systems.diath.homeclaim.core.mutation.WorldMutationBackend
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.ActionContext
import systems.diath.homeclaim.core.policy.ActionEndpoint
import systems.diath.homeclaim.core.policy.ActionRequest
import systems.diath.homeclaim.core.policy.ActionResourceRef
import systems.diath.homeclaim.core.policy.CorrelationId
import systems.diath.homeclaim.core.policy.HomeClaimOperationActions
import java.util.UUID

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
    private val mutationBackend: WorldMutationBackend? = null,
    private val areaService: AreaService? = null,
    private val operationLimits: RegionOperationLimitService = RegionOperationLimitService(regionService),
    private val auditService: AuditService? = null,
    private val worldAreaService: WorldAreaService? = null,
    private val mutationJobStore: PlotMutationJobStore? = null,
    private val adminService: RegionAdminService? = null
) {
    private val operationReservationLock = Any()
    private val operationReservations = mutableSetOf<RegionId>()

    /** Compatibility adapter for older in-process callers; Paper uses the lifecycle Brigadier tree. */
    @Deprecated("Paper commands are routed through the lifecycle command tree")
    fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean =
        execute(sender, args)

    fun execute(sender: CommandSender, args: Array<out String>): Boolean {
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
                handleHome(sender, args.getOrNull(1))
            }
            "info", "i" -> {
                if (!Permissions.checkWithMessage(sender, Permissions.PLOT_INFO)) return true
                handleInfo(sender)
            }
            "list", "l" -> {
                if (!Permissions.checkWithMessage(sender, Permissions.PLOT_LIST)) return true
                handleList(sender)
            }
            "gui", "menu" -> {
                if (!Permissions.checkWithMessage(sender, Permissions.PLOT_LIST)) return true
                guiManager.openMenu(sender, RegionListMenu(regionService, guiManager))
            }
            "visit", "v" -> {
                if (!Permissions.checkWithMessage(sender, Permissions.PLOT_VISIT)) return true
                handleVisit(sender, args.getOrNull(1), args.getOrNull(2))
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
        val regionId = regionAt(loc.world, loc.blockX, loc.blockY, loc.blockZ)
        
        if (regionId == null) {
            player.sendMessage(i18n.msg("plot.not_on_plot"))
            return
        }
        
        val region = regionService.getRegionById(regionId)
        if (region == null) {
            player.sendMessage(i18n.msg("plot.not_found"))
            return
        }
        val request = operationRequest(player, HomeClaimOperationActions.REGION_CLAIM, region, "plot-claim")
        auditOperation(request, AuditStatus.ATTEMPTED)
        
        // Check if unclaimed (owner is empty UUID)
        if (region.owner != UNCLAIMED_UUID) {
            auditOperation(request, AuditStatus.FAILED, "Region is already claimed")
            player.sendMessage(i18n.msg("plot.already_claimed"))
            return
        }
        
        // Claim the plot. Free plot claiming should still work even if no real economy backend is wired.
        val playerId: PlayerId = player.uniqueId
        val effectiveEconService = econService ?: SimpleEconService()
        val bypassLimit = player.hasPermission(Permissions.ADMIN_BYPASS_CLAIM_LIMIT)
        val result = operationLimits.claim(
            request, regionId, playerId, 0.0, effectiveEconService, bypassLimit,
            onAuthorized = { auditOperation(request, AuditStatus.ALLOWED) }
        )
        when (result.status) {
            OperationStatus.SUCCESS -> {
                auditOperation(request, AuditStatus.COMPLETED)
                player.sendMessage(i18n.msg("plot.claim_success"))
            }
            OperationStatus.LIMIT_REACHED -> {
                auditOperation(request, AuditStatus.DENIED, result.detail ?: "Claim limit reached")
                player.sendMessage("§cYou have reached the plot claim limit.")
            }
            OperationStatus.NOT_APPLICABLE -> {
                player.sendMessage(i18n.msg("plot.not_on_plot"))
            }
            else -> {
                auditOperation(request, AuditStatus.FAILED, result.detail ?: "Claim operation failed")
                player.sendMessage(
                    if (result.status == OperationStatus.CONFLICT) i18n.msg("plot.already_claimed")
                    else i18n.msg("plot.claim_failed")
                )
            }
        }
    }
    
    private fun handleAuto(player: Player) {
        player.sendMessage(i18n.msg("plot.auto_searching"))

        val current = currentRegion(player)
        if (current != null && current.owner == UNCLAIMED_UUID) {
            handleClaim(player)
            return
        }

        val location = player.location
        val area = resolveArea(player.world, location.blockX, location.blockY, location.blockZ)
        val areaId = area?.id ?: if (areaService == null && worldAreaService == null) {
            systems.diath.homeclaim.core.model.AreaId.legacyDefault(
                systems.diath.homeclaim.core.model.WorldId.fromLegacyName(player.world.name)
            )
        } else {
            null
        }
        if (areaId == null) {
            player.sendMessage(i18n.msg("plot.not_on_plot"))
            return
        }

        val nextPlot = regionService.listAllRegions()
            .asSequence()
            .filter { it.areaId == areaId && it.owner == UNCLAIMED_UUID }
            .minByOrNull { region ->
                val centerX = (region.bounds.minX + region.bounds.maxX) / 2.0
                val centerZ = (region.bounds.minZ + region.bounds.maxZ) / 2.0
                val dx = location.x - centerX
                val dz = location.z - centerZ
                (dx * dx) + (dz * dz)
            }

        if (nextPlot == null) {
            player.sendMessage("§cNo unclaimed plot was found in this HomeClaim area.")
            return
        }

        val centerX = (nextPlot.bounds.minX + nextPlot.bounds.maxX) / 2.0
        val centerZ = (nextPlot.bounds.minZ + nextPlot.bounds.maxZ) / 2.0
        val y = player.world.getHighestBlockYAt(centerX.toInt(), centerZ.toInt()) + 1.0

        val teleport = runCatching {
            player.teleportAsync(org.bukkit.Location(player.world, centerX, y, centerZ))
        }.getOrElse { error ->
            player.sendMessage("§cTeleport to the selected plot failed.")
            org.bukkit.Bukkit.getLogger().log(
                java.util.logging.Level.WARNING,
                "Auto-claim teleport failed for ${player.uniqueId}",
                error
            )
            return
        }
        teleport.whenComplete { success, error ->
            when (val completion = systems.diath.homeclaim.platform.paper.TeleportCompletion.resolve(success, error)) {
                systems.diath.homeclaim.platform.paper.TeleportCompletion.Succeeded ->
                    onPaperThread(player, "auto-claim") { handleClaim(player) }
                is systems.diath.homeclaim.platform.paper.TeleportCompletion.Failed -> {
                    org.bukkit.Bukkit.getLogger().warning(
                        "Auto-claim teleport failed for ${player.uniqueId}: ${completion.detail}"
                    )
                    onPaperThread(player, "auto-claim") {
                        player.sendMessage("§cTeleport to the selected plot failed.")
                    }
                }
            }
        }
    }
    
    private fun handleHome(player: Player, selector: String?) {
        val playerId: PlayerId = player.uniqueId
        val plots = regionService.listRegionsByOwner(playerId)
        
        if (plots.isEmpty()) {
            player.sendMessage(i18n.msg("plot.no_plots"))
            return
        }
        
        val plot = when (val selection = OwnedRegionSelector.select(plots, selector)) {
            is OwnedRegionSelection.Selected -> selection.region
            is OwnedRegionSelection.SelectionRequired -> {
                sendRegionSelection(player, "/plot home", selection.regions)
                return
            }
            OwnedRegionSelection.InvalidSelector -> {
                player.sendMessage(i18n.msg("plot.region_selector_invalid"))
                return
            }
            OwnedRegionSelection.NoRegions -> {
                player.sendMessage(i18n.msg("plot.no_plots"))
                return
            }
        }
        val world = resolvePaperWorld(plot)
        if (world == null) {
            player.sendMessage(i18n.msg("plot.world_not_found"))
            return
        }
        
        // Teleport to center of plot
        val centerX = (plot.bounds.minX + plot.bounds.maxX) / 2.0
        val centerZ = (plot.bounds.minZ + plot.bounds.maxZ) / 2.0
        val y = world.getHighestBlockYAt(centerX.toInt(), centerZ.toInt()) + 1.0
        
        teleportWithFeedback(
            player,
            org.bukkit.Location(world, centerX, y, centerZ),
            i18n.msg("plot.teleported"),
            "§cTeleport to your plot failed."
        )
    }
    
    private fun handleInfo(player: Player) {
        val loc = player.location
        val regionId = regionAt(loc.world, loc.blockX, loc.blockY, loc.blockZ)
        
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
        player.sendMessage(i18n.msg("plot.info_world", homeClaimWorldName(region) ?: region.world))
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
        val plots = OwnedRegionSelector.order(regionService.listRegionsByOwner(playerId))
        
        if (plots.isEmpty()) {
            player.sendMessage(i18n.msg("plot.list_no_plots"))
            return
        }
        
        player.sendMessage(i18n.msg("plot.list_header", plots.size))
        plots.forEachIndexed { index, region ->
            val worldName = homeClaimWorldName(region)
                ?: org.bukkit.Bukkit.getWorld(region.world)?.name
                ?: i18n.msg("plot.list_unknown_world")
            val centerX = (region.bounds.minX + region.bounds.maxX) / 2
            val centerZ = (region.bounds.minZ + region.bounds.maxZ) / 2
            player.sendMessage(i18n.msg("plot.list_entry", index + 1, region.id.value, worldName, centerX, centerZ))
        }
        player.sendMessage(i18n.msg("plot.list_footer"))
    }
    
    private fun handleVisit(player: Player, targetName: String?, selector: String?) {
        if (targetName == null) {
            player.sendMessage(i18n.msg("plot.visit_usage_selector"))
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
        
        val plot = when (val selection = OwnedRegionSelector.select(plots, selector)) {
            is OwnedRegionSelection.Selected -> selection.region
            is OwnedRegionSelection.SelectionRequired -> {
                sendRegionSelection(player, "/plot visit ${target.name ?: validation.sanitized}", selection.regions)
                return
            }
            OwnedRegionSelection.InvalidSelector -> {
                player.sendMessage(i18n.msg("plot.region_selector_invalid"))
                return
            }
            OwnedRegionSelection.NoRegions -> {
                player.sendMessage(i18n.msg("plot.visit_no_plots", target.name ?: "unknown"))
                return
            }
        }
        val world = resolvePaperWorld(plot)
        if (world == null) {
            player.sendMessage(i18n.msg("plot.visit_world_not_found"))
            return
        }
        
        val centerX = (plot.bounds.minX + plot.bounds.maxX) / 2.0
        val centerZ = (plot.bounds.minZ + plot.bounds.maxZ) / 2.0
        val y = world.getHighestBlockYAt(centerX.toInt(), centerZ.toInt()) + 1.0
        
        teleportWithFeedback(
            player,
            org.bukkit.Location(world, centerX, y, centerZ),
            i18n.msg("plot.visit_success", target.name ?: "unknown"),
            "§cTeleport to ${target.name ?: "unknown"}'s plot failed."
        )
    }

    private fun sendRegionSelection(player: Player, command: String, regions: List<Region>) {
        player.sendMessage(i18n.msg("plot.region_selection_required", command))
        regions.forEachIndexed { index, region ->
            player.sendMessage("§7${index + 1}: ${region.id.value}")
        }
        player.sendMessage(i18n.msg("plot.region_selection_hint"))
    }
    
    private fun handleMerge(player: Player, directionArg: String?) {
        val region = runCatching { currentRegion(player) }.getOrElse { error ->
            java.util.logging.Logger.getLogger(PlotCommand::class.java.name).log(
                java.util.logging.Level.SEVERE,
                "Could not resolve plot for merge command from ${player.uniqueId}",
                error
            )
            player.sendMessage("§cPlot merge could not read plot data. Please try again later.")
            return
        } ?: run {
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

        val request = operationRequest(player, HomeClaimOperationActions.REGION_MERGE, region, "plot-merge")
        auditOperation(request, AuditStatus.ATTEMPTED)
        val sameOwnerRegions = runCatching {
            regionService.listRegionsByOwner(region.owner)
                .filter { it.areaId == region.areaId && it.id != region.id }
        }.getOrElse { error ->
            val detail = "Could not load merge candidates (${error.javaClass.simpleName})"
            org.bukkit.Bukkit.getLogger().log(java.util.logging.Level.SEVERE, "Merge ${request.correlationId} could not load candidates", error)
            auditOperation(request, AuditStatus.FAILED, detail)
            player.sendMessage("§cPlot merge could not read plot data. Please try again later.")
            return
        }
        val selected = collectMergeTargets(region, sameOwnerRegions, direction)
        if (selected.isEmpty()) {
            auditOperation(request, AuditStatus.DENIED, "NO_ADJACENT_MERGE_TARGET")
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
        val bypassLimit = player.hasPermission(Permissions.ADMIN_BYPASS_MERGE_LIMIT)
        val limit = runCatching { operationLimits.evaluateMerge(mergedIds, bypassLimit) }.getOrElse { error ->
            val detail = "Could not validate merge candidates (${error.javaClass.simpleName})"
            org.bukkit.Bukkit.getLogger().log(java.util.logging.Level.SEVERE, "Merge ${request.correlationId} validation failed", error)
            auditOperation(request, AuditStatus.FAILED, detail, mergedIds.size)
            player.sendMessage("§cPlot merge could not validate plot data. Please try again later.")
            return
        }
        if (!limit.allowed) {
            auditOperation(request, AuditStatus.DENIED, limit.reason ?: "Merge limit or scope rejected", mergedIds.size)
            player.sendMessage("§cThis merge exceeds the configured limit or crosses a resource boundary.")
            return
        }

        val reserved = reserveRegions(mergedIds)
        if (!reserved) {
            auditOperation(request, AuditStatus.DENIED, "MERGE_ALREADY_IN_PROGRESS", mergedIds.size)
            player.sendMessage("§cA merge involving one of these plots is already running.")
            return
        }

        auditOperation(request, AuditStatus.ALLOWED, targetCount = mergedIds.size)
        val originalRegions = (listOf(region) + selected + expanded).distinctBy { it.id }
        val prospectiveGroup = MergeGroupId(UUID.randomUUID())
        val prospectiveRegions = originalRegions.map { it.copy(mergeGroupId = prospectiveGroup) }
        val jobId = UUID.randomUUID()
        try {
            mutationJobStore?.create(
                PlotMutationJobRecord(
                    id = jobId,
                    correlationId = request.correlationId,
                    operation = "MERGE",
                    worldId = worldAreaService?.getArea(region.areaId)?.worldId,
                    areaId = region.areaId,
                    worldName = region.world,
                    regionIds = originalRegions.map { it.id },
                    backend = mutationBackend?.backendId ?: "paper-plot-mutation",
                    status = PlotMutationJobStatus.QUEUED,
                    totalUnits = originalRegions.size
                )
            )
            mutationJobStore?.markRunning(jobId)
        } catch (journalError: Throwable) {
            auditOperation(request, AuditStatus.FAILED, "Could not persist merge operation before mutation: ${journalError.message}", mergedIds.size)
            player.sendMessage("§cPlot merge was not started because its operation state could not be persisted.")
            releaseMergeReservation(mergedIds)
            return
        }
        val worldMutation = runCatching { plotMutationService.handleRegionsMerged(prospectiveRegions) }
            .getOrElse { java.util.concurrent.CompletableFuture.failedFuture(it) }

        worldMutation.whenComplete { _, worldError ->
            if (worldError != null) {
                val cause = worldError.cause ?: worldError
                val detail = "Merge world mutation failed; partial world changes cannot be excluded: ${cause.message ?: cause.javaClass.simpleName}"
                runCatching {
                    mutationJobStore?.finish(jobId, PlotMutationJobStatus.RECOVERY_REQUIRED, 0, originalRegions.size, detail)
                }.onFailure { journalError ->
                    org.bukkit.Bukkit.getLogger().severe("Could not persist merge recovery status for job $jobId: ${journalError.message}")
                }
                auditOperation(request, AuditStatus.FAILED, detail, mergedIds.size)
                if (player.isOnline) player.sendMessage("§cPlot merge requires recovery: $detail")
                releaseMergeReservation(mergedIds)
                return@whenComplete
            }

        val persisted = runCatching { operationLimits.merge(request, mergedIds, bypassLimit) }
            .getOrElse { error ->
                systems.diath.homeclaim.core.service.OperationResult.rejected(
                    OperationStatus.FAILED,
                    "MERGE_EXECUTION_FAILED:${error.javaClass.simpleName}"
                )
            }
            if (persisted.status == OperationStatus.SUCCESS) {
                val mergeId = requireNotNull(persisted.value)
                runCatching {
                    mutationJobStore?.finish(jobId, PlotMutationJobStatus.COMPLETED, originalRegions.size, originalRegions.size)
                }.onFailure { journalError ->
                    // World and domain state agree. Leave the nonterminal journal row for conservative startup review.
                    org.bukkit.Bukkit.getLogger().severe("Merge $jobId completed but its journal could not be finalized: ${journalError.message}")
                }
                auditOperation(request, AuditStatus.COMPLETED, targetCount = mergedIds.size)
                if (player.isOnline) {
                    player.sendMessage(i18n.msg("plot.merge_success", mergedIds.size - 1, mergeId.value.toString().take(8)))
                }
                releaseMergeReservation(mergedIds)
                return@whenComplete
            }

            val reason = persisted.detail ?: persisted.status.name
            val compensation = runCatching { plotMutationService.restoreRegionVisualStates(originalRegions) }
                .getOrElse { java.util.concurrent.CompletableFuture.failedFuture(it) }
            compensation.whenComplete { _, compensationError ->
                val cause = compensationError?.cause ?: compensationError
                val auditStatus = if (cause == null) AuditStatus.ROLLED_BACK else AuditStatus.FAILED
                val detail = if (cause == null) {
                    "Merge persistence failed ($reason); world visuals restored"
                } else {
                    "Merge persistence failed ($reason); recovery required because visual compensation failed: ${cause.message ?: cause.javaClass.simpleName}"
                }
                val jobStatus = if (cause == null) PlotMutationJobStatus.ROLLED_BACK else PlotMutationJobStatus.RECOVERY_REQUIRED
                runCatching {
                    mutationJobStore?.finish(jobId, jobStatus, originalRegions.size, originalRegions.size, detail)
                }.onFailure { journalError ->
                    org.bukkit.Bukkit.getLogger().severe("Could not persist merge compensation status for job $jobId: ${journalError.message}")
                }
                auditOperation(request, auditStatus, detail, mergedIds.size)
                if (player.isOnline) player.sendMessage("§cPlot merge failed: $detail")
                releaseMergeReservation(mergedIds)
            }
        }
    }

    private fun releaseMergeReservation(regionIds: Collection<RegionId>) {
        synchronized(operationReservationLock) { operationReservations.removeAll(regionIds.toSet()) }
    }

    private fun reserveRegions(regionIds: Collection<RegionId>): Boolean = synchronized(operationReservationLock) {
        if (regionIds.any(operationReservations::contains)) false
        else {
            operationReservations.addAll(regionIds)
            true
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

        val request = operationRequest(
            player,
            HomeClaimOperationActions.REGION_UNLINK,
            region,
            "plot-unlink",
            mapOf("restoreRoads" to createRoads.toString())
        )
        auditOperation(request, AuditStatus.ATTEMPTED)
        val groupedRegions = runCatching {
            regionService.listAllRegions()
                .filter { it.areaId == region.areaId && it.mergeGroupId == mergeGroupId }
        }.getOrElse { error ->
            val detail = "Could not load merge group (${error.javaClass.simpleName})"
            org.bukkit.Bukkit.getLogger().log(java.util.logging.Level.SEVERE, "Unlink ${request.correlationId} could not load merge group", error)
            auditOperation(request, AuditStatus.FAILED, detail)
            player.sendMessage("§cPlot unlink could not read plot data. Please try again later.")
            return
        }
        if (groupedRegions.size <= 1) {
            auditOperation(request, AuditStatus.DENIED, "MERGE_GROUP_NOT_FOUND")
            player.sendMessage(i18n.msg("plot.unlink_not_merged"))
            return
        }
        if (!reserveRegions(groupedRegions.map { it.id })) {
            auditOperation(request, AuditStatus.DENIED, "PLOT_OPERATION_ALREADY_RUNNING", groupedRegions.size)
            player.sendMessage("§cA plot operation involving this merge group is already running.")
            return
        }

        auditOperation(request, AuditStatus.ALLOWED, targetCount = groupedRegions.size)
        val originalRegions = groupedRegions.toList()
        val updatedRegions = originalRegions.map { it.copy(mergeGroupId = null) }
        val jobId = UUID.randomUUID()
        try {
            mutationJobStore?.create(
                PlotMutationJobRecord(
                    id = jobId,
                    correlationId = request.correlationId,
                    operation = "UNLINK",
                    worldId = worldAreaService?.getArea(region.areaId)?.worldId,
                    areaId = region.areaId,
                    worldName = region.world,
                    regionIds = originalRegions.map { it.id },
                    backend = mutationBackend?.backendId ?: "paper-plot-mutation",
                    status = PlotMutationJobStatus.QUEUED,
                    totalUnits = originalRegions.size
                )
            )
            mutationJobStore?.markRunning(jobId)
        } catch (journalError: Throwable) {
            auditOperation(request, AuditStatus.FAILED, "Could not persist unlink operation before mutation")
            player.sendMessage("§cPlot unlink was not started because its operation state could not be persisted.")
            releaseMergeReservation(originalRegions.map { it.id })
            return
        }
        try {
            updatedRegions.forEach(regionService::updateRegion)
        } catch (error: Throwable) {
            val rollbackError = runCatching { originalRegions.forEach(regionService::updateRegion) }.exceptionOrNull()
            val detail = buildString {
                append(error.message ?: "Unlink persistence failed")
                if (rollbackError != null) append("; persistence rollback failed: ${rollbackError.message}")
            }
            val status = if (rollbackError == null) PlotMutationJobStatus.ROLLED_BACK else PlotMutationJobStatus.RECOVERY_REQUIRED
            runCatching { mutationJobStore?.finish(jobId, status, 0, originalRegions.size, detail) }
                .onFailure { journalError -> org.bukkit.Bukkit.getLogger().severe("Could not persist unlink recovery state for job $jobId: ${journalError.message}") }
            auditOperation(request, if (rollbackError == null) AuditStatus.ROLLED_BACK else AuditStatus.FAILED, detail, groupedRegions.size)
            player.sendMessage("§cPlot unlink failed: $detail")
            releaseMergeReservation(originalRegions.map { it.id })
            return
        }

        val mutation = try {
            plotMutationService.handleRegionsUnlinked(updatedRegions, createRoads)
        } catch (error: Throwable) {
            java.util.concurrent.CompletableFuture.failedFuture(error)
        }
        mutation.whenComplete { _, mutationError ->
            if (mutationError == null) {
                runCatching {
                    mutationJobStore?.finish(jobId, PlotMutationJobStatus.COMPLETED, originalRegions.size, originalRegions.size)
                }.onFailure { journalError ->
                    org.bukkit.Bukkit.getLogger().severe("Unlink $jobId completed but its journal could not be finalized: ${journalError.message}")
                }
                auditOperation(request, AuditStatus.COMPLETED, targetCount = updatedRegions.size)
                if (player.isOnline) player.sendMessage(i18n.msg("plot.unlink_success", updatedRegions.size, createRoads.toString()))
                releaseMergeReservation(originalRegions.map { it.id })
            } else {
                val cause = mutationError.cause ?: mutationError
                val rollbackError = runCatching { originalRegions.forEach(regionService::updateRegion) }.exceptionOrNull()
                val compensation = runCatching { plotMutationService.handleRegionsMerged(originalRegions) }
                    .getOrElse { java.util.concurrent.CompletableFuture.failedFuture(it) }
                compensation.whenComplete { _, compensationError ->
                    val compensationCause = compensationError?.cause ?: compensationError
                    val recoveryRequired = rollbackError != null || compensationCause != null
                    val detail = buildString {
                        append(cause.message ?: "Unlink world mutation failed")
                        if (rollbackError != null) append("; persistence rollback failed: ${rollbackError.message}")
                        else append("; persisted merge state restored")
                        if (compensationCause != null) append("; world compensation failed: ${compensationCause.message ?: compensationCause.javaClass.simpleName}")
                    }
                    val jobStatus = if (recoveryRequired) PlotMutationJobStatus.RECOVERY_REQUIRED else PlotMutationJobStatus.ROLLED_BACK
                    runCatching { mutationJobStore?.finish(jobId, jobStatus, originalRegions.size, originalRegions.size, detail) }
                        .onFailure { journalError -> org.bukkit.Bukkit.getLogger().severe("Could not persist unlink terminal state for job $jobId: ${journalError.message}") }
                    auditOperation(request, if (recoveryRequired) AuditStatus.FAILED else AuditStatus.ROLLED_BACK, detail, groupedRegions.size)
                    if (player.isOnline) player.sendMessage("§cPlot unlink failed: $detail")
                    releaseMergeReservation(originalRegions.map { it.id })
                }
            }
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

        val request = operationRequest(player, HomeClaimOperationActions.REGION_RESET, region, "plot-reset")
        auditOperation(request, AuditStatus.ATTEMPTED)
        if (!reserveRegions(listOf(region.id))) {
            auditOperation(request, AuditStatus.DENIED, "PLOT_OPERATION_ALREADY_RUNNING")
            player.sendMessage("§cA plot operation involving this region is already running.")
            return
        }
        val queued = runCatching {
            plotResetService.queueReset(region, PlotResetReason.MANUAL, request.correlationId) { completion ->
                try {
                    when (completion.status) {
                        PlotResetCompletionStatus.COMPLETED -> {
                            auditOperation(request, AuditStatus.COMPLETED)
                            if (player.isOnline) player.sendMessage("§aPlot reset completed.")
                        }
                        PlotResetCompletionStatus.FAILED, PlotResetCompletionStatus.CANCELLED -> {
                            val state = when {
                                completion.partialMutation ->
                                    "recovery required: partial world changes may remain; inspect /plot jobs status"
                                completion.completedColumns > 0 ->
                                    "${completion.completedColumns} reset columns were applied, but job finalization failed; inspect /plot jobs status and verify the plot"
                                else -> "no reset batches were applied"
                            }
                            val detail = "${completion.status}: ${completion.error ?: "unknown reset result"}; $state"
                            auditOperation(request, AuditStatus.FAILED, detail)
                            if (player.isOnline) player.sendMessage("§cPlot reset ${completion.status.name.lowercase()}: $state")
                        }
                    }
                } finally {
                    releaseMergeReservation(listOf(region.id))
                }
            }
        }.getOrElse {
            releaseMergeReservation(listOf(region.id))
            auditOperation(request, AuditStatus.FAILED, it.message ?: "Reset queue failed")
            player.sendMessage("§cPlot reset failed: ${it.message ?: "unknown error"}")
            return
        }
        if (queued) {
            // ALLOWED records acceptance only; the asynchronous reset job owns completion.
            auditOperation(request, AuditStatus.ALLOWED)
            player.sendMessage(i18n.msg("plot.reset_queued"))
        } else {
            releaseMergeReservation(listOf(region.id))
            auditOperation(request, AuditStatus.FAILED, "Reset could not be queued")
            player.sendMessage(i18n.msg("plot.reset_unavailable"))
        }
    }

    private fun handleJobs(player: Player, args: Array<out String>) {
        val action = args.getOrNull(1)?.lowercase()
        when (action) {
            "inspect" -> {
                val jobId = args.getOrNull(2)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (jobId == null) {
                    player.sendMessage("§cUsage: /plot jobs inspect <job-id>")
                    return
                }
                val record = mutationJobStore?.findById(jobId)
                if (record == null) {
                    player.sendMessage("§eNo persisted mutation job with that id was found.")
                    return
                }
                player.sendMessage("§6§m───────────§r")
                player.sendMessage("§aHomeClaim recovery job")
                player.sendMessage("§7Job: §f${record.id}")
                player.sendMessage("§7Operation: §f${record.operation}  §7Status: §e${record.status}")
                player.sendMessage("§7Region(s): §f${record.regionIds.joinToString { it.value.toString() }}")
                player.sendMessage("§7Area: §f${record.areaId.value}  §7World: §f${record.worldName}")
                player.sendMessage("§7Backend: §f${record.backend}  §7Progress: §f${record.completedUnits}/${record.totalUnits}")
                player.sendMessage("§7Correlation: §f${record.correlationId}")
                player.sendMessage("§7Updated: §f${record.updatedAt}")
                record.error?.let { player.sendMessage("§cError: §f$it") }
                if (record.status == PlotMutationJobStatus.RECOVERY_REQUIRED) {
                    player.sendMessage("§eDiagnostic only: inspect the plot and start a new explicit operation if recovery is safe. HomeClaim does not resume this job automatically.")
                } else {
                    player.sendMessage("§7This is a historical job record. Inspection does not change its state.")
                }
                player.sendMessage("§6§m───────────§r")
            }
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
            "status" -> {
                val worldName = args.getOrNull(2)
                val scope = worldName ?: "all worlds"
                val diagnostics = buildList {
                    addAll(plotMutationService.activeJobDiagnostics(worldName))
                    addAll(plotResetService.activeJobDiagnostics(worldName))
                    addAll(plotResetService.recoveryRequiredDiagnostics(worldName))
                }
                player.sendMessage("\u00a76\u00a7m\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u00a7r")
                player.sendMessage("\u00a7aPlot Jobs Status")
                player.sendMessage("\u00a77Scope: \u00a7f$scope")
                if (diagnostics.isEmpty()) {
                    player.sendMessage("\u00a77No active plot jobs.")
                } else {
                    diagnostics.forEach { line ->
                        player.sendMessage("\u00a77- \u00a7f$line")
                    }
                }
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
                player.sendMessage("\u00a7f  /plot jobs status [world]\u00a77 - Show active queued jobs")
                player.sendMessage("\u00a7f  /plot jobs cancel [world]\u00a77 - Cancel queued jobs")
                player.sendMessage("\u00a7f  /plot jobs inspect <job-id>\u00a77 - Inspect recovery-required job details")
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
        val raw = args.drop(1).joinToString(" ").trim()
        if (raw.isEmpty()) {
            player.sendMessage(i18n.msg("plot.alias_usage"))
            return
        }
        val service = adminService ?: run {
            player.showOperationFailure("REGION_ADMIN_OPERATIONS_UNAVAILABLE")
            return
        }
        val value = raw.takeUnless { it.equals("clear", ignoreCase = true) || it == "-" }
        val result = service.mutateMetadata(
            RegionMutationContext(ActionActor.Player(player.uniqueId), "paper", "plot_alias"),
            region.id,
            "alias",
            value
        )
        if (!result.succeeded) {
            player.showOperationFailure(result.detail)
        } else if (value != null) {
            player.sendMessage(i18n.msg("plot.alias_set", result.value?.metadata?.get("alias") ?: ""))
        } else {
            player.sendMessage(i18n.msg("plot.alias_cleared"))
        }
    }

    private fun handleDescription(player: Player, args: Array<out String>) {
        val region = currentRegion(player) ?: run {
            player.sendMessage(i18n.msg("plot.not_on_plot"))
            return
        }
        val raw = args.drop(1).joinToString(" ").trim()
        if (raw.isEmpty()) {
            player.sendMessage(i18n.msg("plot.description_usage"))
            return
        }
        val service = adminService ?: run {
            player.showOperationFailure("REGION_ADMIN_OPERATIONS_UNAVAILABLE")
            return
        }
        val value = raw.takeUnless { it.equals("clear", ignoreCase = true) || it == "-" }
        val result = service.mutateMetadata(
            RegionMutationContext(ActionActor.Player(player.uniqueId), "paper", "plot_description"),
            region.id,
            "description",
            value
        )
        if (!result.succeeded) {
            player.showOperationFailure(result.detail)
        } else if (value != null) {
            player.sendMessage(i18n.msg("plot.description_set", result.value?.metadata?.get("description") ?: ""))
        } else {
            player.sendMessage(i18n.msg("plot.description_cleared"))
        }
    }

    private fun currentRegion(player: Player): Region? {
        val loc = player.location
        // Paper may reject a location whose world is no longer loaded. Treat that as an
        // unresolved location and give the caller its normal not-on-plot feedback.
        val world = runCatching { loc.world }.getOrNull() ?: return null
        val regionId = regionAt(world, loc.blockX, loc.blockY, loc.blockZ) ?: return null
        return regionService.getRegionById(regionId)
    }

    private fun regionAt(world: World, x: Int, y: Int, z: Int): RegionId? {
        val area = resolveArea(world, x, y, z)
        return if (area != null) regionService.getRegionAt2D(area.id, x, z)
        else if (areaService == null && worldAreaService == null) regionService.getRegionAt2D(
            systems.diath.homeclaim.core.model.WorldId.fromLegacyName(world.name), x, z
        ) else null
    }

    private fun resolveArea(world: World, x: Int, y: Int, z: Int): systems.diath.homeclaim.core.model.Area? {
        worldAreaService?.let { service ->
            return PaperWorldAreaResolver.resolveArea(service, world.uid, world.name, x, y, z)
        }
        return areaService?.resolveArea(world.name, x, y, z)
    }

    private fun resolvePaperWorld(region: Region): World? {
        val service = worldAreaService ?: return org.bukkit.Bukkit.getWorld(region.world)
        val area = service.getArea(region.areaId) ?: return org.bukkit.Bukkit.getWorld(region.world)
        val homeWorld = service.getWorld(area.worldId) ?: return org.bukkit.Bukkit.getWorld(region.world)
        val platformId = homeWorld.platformWorldId
        if (platformId != null) return org.bukkit.Bukkit.getWorld(platformId)
        return org.bukkit.Bukkit.getWorld(homeWorld.name) ?: org.bukkit.Bukkit.getWorld(region.world)
    }

    private fun homeClaimWorldName(region: Region): String? {
        val service = worldAreaService ?: return null
        val area = service.getArea(region.areaId) ?: return null
        return service.getWorld(area.worldId)?.name
    }

    private fun teleportWithFeedback(player: Player, location: org.bukkit.Location, successMessage: String, failureMessage: String) {
        val teleport = runCatching { player.teleportAsync(location) }.getOrElse { error ->
            org.bukkit.Bukkit.getLogger().log(
                java.util.logging.Level.WARNING,
                "Plot teleport failed for ${player.uniqueId}",
                error
            )
            player.sendMessage(failureMessage)
            return
        }
        teleport.whenComplete { success, error ->
            val completion = systems.diath.homeclaim.platform.paper.TeleportCompletion.resolve(success, error)
            if (completion is systems.diath.homeclaim.platform.paper.TeleportCompletion.Failed) {
                org.bukkit.Bukkit.getLogger().warning("Plot teleport failed for ${player.uniqueId}: ${completion.detail}")
            }
            onPaperThread(player, "plot-teleport") {
                player.sendMessage(
                    if (completion == systems.diath.homeclaim.platform.paper.TeleportCompletion.Succeeded) successMessage
                    else failureMessage
                )
            }
        }
    }

    private fun onPaperThread(player: Player, operation: String, action: () -> Unit) {
        val task = Runnable { if (player.isOnline) action() }
        runCatching {
            if (org.bukkit.Bukkit.isPrimaryThread()) {
                task.run()
            } else {
                org.bukkit.Bukkit.getScheduler().runTask(
                    org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(PlotCommand::class.java),
                    task
                )
            }
        }.onFailure { error ->
            org.bukkit.Bukkit.getLogger().log(
                java.util.logging.Level.WARNING,
                "Could not deliver $operation teleport completion for ${player.uniqueId}",
                error
            )
        }
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
        player.sendMessage(i18n.msg("plot.help_home_selector"))
        player.sendMessage(i18n.msg("plot.help_info"))
        player.sendMessage(i18n.msg("plot.help_list"))
        player.sendMessage("§7/plot gui - Manage your plots in the GUI")
        player.sendMessage(i18n.msg("plot.help_visit"))
        player.sendMessage(i18n.msg("plot.help_merge"))
        player.sendMessage(i18n.msg("plot.help_unlink"))
        player.sendMessage(i18n.msg("plot.help_reset"))
        player.sendMessage(i18n.msg("plot.help_alias"))
        player.sendMessage(i18n.msg("plot.help_description"))
        player.sendMessage("§7/plot jobs status [world] - Show queued plot jobs")
        player.sendMessage("§7/plot jobs cancel [world] - Cancel queued plot jobs")
        player.sendMessage("§7/plot jobs inspect <job-id> - Inspect recovery-required job details")
    }

    private fun operationRequest(
        player: Player,
        actionKey: systems.diath.homeclaim.core.policy.ActionKey,
        region: Region,
        eventName: String,
        attributes: Map<String, String> = emptyMap()
    ): ActionRequest = ActionRequest(
        actor = ActionActor.Player(player.uniqueId),
        actionKey = actionKey,
        target = ActionEndpoint(
            resource = ActionResourceRef.Region(region.id),
            areaId = region.areaId
        ),
        regionId = region.id,
        areaId = region.areaId,
        context = ActionContext(
            platform = "paper",
            eventName = eventName,
            playerName = player.name,
            attributes = attributes
        ),
        correlationId = CorrelationId.newId()
    )

    private fun auditOperation(
        request: ActionRequest,
        status: AuditStatus,
        error: String? = null,
        targetCount: Int? = null
    ) {
        auditService?.append(AuditEntries.operationResult(request, status, error, targetCount))
    }
    
    @Deprecated("Paper suggestions are registered on the lifecycle command tree")
    fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> = suggestions(sender, args)

    fun suggestions(sender: CommandSender, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return listOf("claim", "auto", "home", "info", "list", "gui", "menu", "visit", "merge", "unlink", "reset", "jobs", "alias", "desc")
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
            return listOf("status", "cancel", "inspect") +
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
