package systems.diath.homeclaim.platform.paper.plot

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.WorldCreator
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import com.electronwill.nightconfig.core.file.FileConfig
import java.util.UUID

/**
 * Setup modes for plot world creation.
 */
enum class SetupMode {
    /** Create a new world with custom generator (default, like PlotSquared) */
    NEW_WORLD,
    /** Convert an existing world's region to plots (requires FAWE or uses slow fallback) */
    CONVERT_EXISTING
}

class PlotWorldSetupWizard(
    private val configStore: PlotWorldConfigStore,
    private val i18nProvider: () -> systems.diath.homeclaim.platform.paper.I18n,
    private val plugin: Plugin,
    private val regionServiceProvider: () -> systems.diath.homeclaim.core.service.RegionService? = { null }
) {
    private val sessions = mutableMapOf<UUID, Session>()
    private val maxPlotsPerSide = 10000
    private val bulkBlockSetter = BulkBlockSetter(plugin)

    fun handle(sender: CommandSender, args: List<String>): Boolean {
        val i18n = i18nProvider()
        val player = sender as? Player ?: run {
            sender.sendMessage(i18n.msg("setup.only_players"))
            return true
        }

        val sub = args.getOrNull(0)?.lowercase()
        when (sub) {
            null, "start" -> {
                if (sessions.containsKey(player.uniqueId)) {
                    player.sendMessage(i18n.msg("setup.already_running"))
                    return true
                }
                val foliaMode = isFoliaServer()
                val session = Session().applyRecommendedDefaults(foliaMode)
                sessions[player.uniqueId] = session
                player.sendMessage(i18n.msg("setup.started"))
                player.sendMessage(i18n.msg("setup.header"))
                if (bulkBlockSetter.isFaweAvailable()) {
                    player.sendMessage(i18n.msg("setup.fawe_detected", bulkBlockSetter.getFaweVersion()))
                } else {
                    player.sendMessage("§8FAWE nicht aktiv – die Konvertierung bestehender Welten ist nur auf Paper mit FAWE verfügbar.")
                }
                val recommended = PlotSchemas.recommended(foliaMode)
                if (foliaMode) {
                    player.sendMessage("§8Folia-Profil aktiv: ${recommended.plotSize}er Plots, ${recommended.roadWidth} breite Straßen, ${recommended.plotsPerSide} Plots/Seite")
                } else {
                    player.sendMessage("§8Empfohlene Defaults aktiv: ${recommended.plotSize}er Plots, ${recommended.roadWidth} breite Straßen, ${recommended.plotsPerSide} Plots/Seite")
                }
                player.sendMessage("")
                player.sendMessage(i18n.msg("setup.mode_select"))
                player.sendMessage(i18n.msg("setup.mode_new_world"))
                
                // Conversion is only offered on Paper with FAWE.
                if (supportsConvertMode()) {
                    player.sendMessage(i18n.msg("setup.mode_convert"))
                } else if (isFoliaServer()) {
                    player.sendMessage("§7§m2) Bestehende Welt konvertieren§r §c(auf Folia deaktiviert)")
                    player.sendMessage("§8Nutze auf Folia bitte eine neue Plot-Welt über den Setup-Wizard.")
                } else {
                    player.sendMessage(i18n.msg("setup.mode_convert_disabled"))
                }
                
                player.sendMessage("")
                player.sendMessage(i18n.msg("setup.mode_answer"))
                player.sendMessage(i18n.msg("setup.cancel_hint"))
                player.sendMessage(i18n.msg("setup.default_hint"))
                return true
            }
            "cancel" -> {
                sessions.remove(player.uniqueId)
                player.sendMessage(i18n.msg("setup.cancelled"))
                return true
            }
            "status" -> {
                val s = sessions[player.uniqueId]
                if (s == null) {
                    player.sendMessage(i18n.msg("setup.no_setup"))
                } else {
                    player.sendMessage(i18n.msg("setup.status", s.step.label))
                }
                return true
            }
            else -> {
                val s = sessions[player.uniqueId]
                if (s == null) {
                    player.sendMessage(i18n.msg("setup.no_setup"))
                    player.sendMessage(i18n.msg("setup.usage"))
                    return true
                }
                val input = args.joinToString(" ").trim()
                
                // Allow cancel at any point
                if (input.lowercase() == "cancel" || input.lowercase() == "abbrechen") {
                    sessions.remove(player.uniqueId)
                    player.sendMessage(i18n.msg("setup.cancelled"))
                    return true
                }
                // Leere Eingabe = Default-Wert verwenden
                val result = s.applyInput(
                    input,
                    i18n,
                    maxPlotsPerSide,
                    bulkBlockSetter,
                    conversionAvailable = supportsConvertMode(),
                    foliaMode = isFoliaServer(),
                )
                if (!result.ok) {
                    player.sendMessage(result.message)
                    return true
                }
                if (s.step == Step.DONE) {
                    val cfg = s.toConfig()
                    
                    if (s.mode == SetupMode.CONVERT_EXISTING) {
                        // Convert existing world
                        handleConversion(player, cfg, s, i18n)
                    } else {
                        // Create new world (default) – callback-based for Folia async support
                        sessions.remove(player.uniqueId)
                        player.sendMessage(i18n.msg("setup.creating_world", cfg.worldName))
                        configStore.persistAndCreateWorld(cfg) { result ->
                            when (result) {
                                WorldCreateResult.CREATED -> {
                                    player.sendMessage(i18n.msg("setup.done.created", cfg.worldName))
                                    // Initialize plots in database
                                    initializePlots(player, cfg)
                                }
                                WorldCreateResult.FOLIA_RESTART_REQUIRED -> {
                                    player.sendMessage(i18n.msg("setup.done.saved", cfg.worldName))
                                    player.sendMessage(i18n.msg("setup.folia.restart_required", cfg.worldName))
                                    player.sendMessage(i18n.msg("setup.folia.plots_on_restart"))
                                }
                                WorldCreateResult.ALREADY_EXISTS ->
                                    player.sendMessage(i18n.msg("setup.world_exists", cfg.worldName))
                                WorldCreateResult.CREATE_FAILED ->
                                    player.sendMessage(i18n.msg("setup.create_failed", cfg.worldName))
                            }
                        }
                    }
                    return true
                }
                // Show next prompt
                showPrompt(player, s, i18n)
                return true
            }
        }
    }
    
    private fun showPrompt(player: Player, session: Session, i18n: systems.diath.homeclaim.platform.paper.I18n) {
        when (session.step) {
            Step.MODE_SELECT -> {
                // Already shown in start
            }
            Step.WORLD_NAME -> {
                if (session.mode == SetupMode.CONVERT_EXISTING) {
                    player.sendMessage(i18n.msg("setup.convert_world_name"))
                    val worlds = Bukkit.getWorlds().joinToString(", ") { it.name }
                    player.sendMessage(i18n.msg("setup.convert_available_worlds", worlds))
                } else {
                    player.sendMessage(i18n.msg("setup.prompt.world_name"))
                }
            }
            Step.PLOT_SIZE -> {
                player.sendMessage(i18n.msg("setup.prompt.plot_size_default", session.plotSize))
            }
            Step.ROAD_WIDTH -> {
                player.sendMessage(i18n.msg("setup.prompt.road_width_default", session.roadWidth))
            }
            Step.PLOT_HEIGHT -> {
                player.sendMessage(i18n.msg("setup.prompt.plot_height_default", session.plotHeight))
            }
            Step.PLOT_BLOCK -> {
                player.sendMessage(i18n.msg("setup.prompt.plot_block_default", session.plotBlock.name))
            }
            Step.ROAD_BLOCK -> {
                player.sendMessage(i18n.msg("setup.prompt.road_block_default", session.roadBlock.name))
            }
            Step.WALL_BLOCK -> {
                player.sendMessage(i18n.msg("setup.prompt.wall_block_default", session.wallBlock.name))
            }
            Step.PLOTS_PER_SIDE -> {
                player.sendMessage(i18n.msg("setup.prompt.plots_per_side_default", session.plotsPerSide, maxPlotsPerSide))
            }
            else -> {
                if (session.step.promptKey.isNotEmpty()) {
                    player.sendMessage(i18n.msg(session.step.promptKey))
                }
            }
        }
    }
    
    private fun handleConversion(
        player: Player, 
        cfg: PlotWorldConfig, 
        session: Session,
        i18n: systems.diath.homeclaim.platform.paper.I18n
    ) {
        val world = Bukkit.getWorld(cfg.worldName)
        if (world == null) {
            player.sendMessage(i18n.msg("setup.world_not_found", cfg.worldName))
            return
        }
        
        // Teleport all players out of the conversion zone
        val safeLocation = world.spawnLocation.clone().add(0.0, 100.0, 0.0)
        world.players.forEach { p ->
            p.teleportAsync(safeLocation)
            p.sendMessage(i18n.msg("setup.convert_teleport_safe"))
        }
        
        player.sendMessage(i18n.msg("setup.convert_starting"))
        player.sendMessage(i18n.msg("setup.convert_fawe_version", bulkBlockSetter.getFaweVersion()))
        player.sendMessage(i18n.msg("setup.convert_area", cfg.plotsPerSide))
        
        val startTime = System.currentTimeMillis()
        
        bulkBlockSetter.convertRegionToPlots(
            world = world,
            config = cfg,
            centerX = 0,
            centerZ = 0,
            radiusInPlots = cfg.plotsPerSide / 2,
            progressCallback = { progress ->
                val percent = (progress * 100).toInt()
                if (percent % 10 == 0) {
                    player.sendMessage(i18n.msg("setup.convert_progress", percent))
                }
            }
        ).thenAccept { blockCount ->
            val duration = (System.currentTimeMillis() - startTime) / 1000.0
            player.sendMessage(i18n.msg("setup.convert_complete"))
            player.sendMessage(i18n.msg("setup.convert_blocks_set", blockCount))
            player.sendMessage(i18n.msg("setup.convert_duration", String.format("%.1f", duration)))
            
            // Save config
            configStore.persistConfig(cfg)
            
            // Set world border
            if (cfg.plotsPerSide > 0) {
                val size = (cfg.gridSize() * cfg.plotsPerSide).toDouble()
                world.worldBorder.center = org.bukkit.Location(world, 0.0, 0.0, 0.0)
                world.worldBorder.size = size
            }
            
            sessions.remove(player.uniqueId)
        }.exceptionally { ex ->
            player.sendMessage(i18n.msg("setup.convert_failed", ex.message ?: "Unknown error"))
            sessions.remove(player.uniqueId)
            null
        }
    }
    
    /**
     * Initialize plot regions in database for a newly created plot world.
     */
    private fun initializePlots(player: Player, cfg: PlotWorldConfig) {
        val regionService = regionServiceProvider() ?: run {
            player.sendMessage("§cWarning: Could not initialize plots - RegionService not available")
            player.sendMessage("§eRun /homeclaim plot init ${cfg.worldName} to initialize plots later")
            return
        }
        
        val world = Bukkit.getWorld(cfg.worldName) ?: run {
            player.sendMessage("§cWarning: World not found after creation")
            return
        }
        
        player.sendMessage("§eInitializing plots in database...")
        val initializer = PlotWorldInitializer(regionService, plugin)
        initializer.initializePlots(world, cfg) { progress ->
            val percent = (progress * 100).toInt()
            if (percent % 20 == 0 && percent > 0) {
                player.sendMessage("§7Progress: ${percent}%")
            }
        }.thenAccept { count ->
            player.sendMessage("§a✓ Initialized $count plots!")
            player.sendMessage("§7You can now use §e/plot claim §7to claim plots")
        }.exceptionally { ex ->
            player.sendMessage("§cFailed to initialize plots: ${ex.message}")
            player.sendMessage("§eRun /homeclaim plot init ${cfg.worldName} to retry")
            null
        }
    }

    private fun isFoliaServer(): Boolean {
        return try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    private fun supportsConvertMode(): Boolean = bulkBlockSetter.isFaweAvailable() && !isFoliaServer()

    private data class Result(val ok: Boolean, val message: String)

    private enum class Step(val label: String, val promptKey: String) {
        MODE_SELECT("mode_select", ""),
        WORLD_NAME("world_name", "setup.prompt.world_name"),
        PLOT_SIZE("plot_size", "setup.prompt.plot_size"),
        ROAD_WIDTH("road_width", "setup.prompt.road_width"),
        PLOT_HEIGHT("plot_height", "setup.prompt.plot_height"),
        PLOT_BLOCK("plot_block", "setup.prompt.plot_block"),
        ROAD_BLOCK("road_block", "setup.prompt.road_block"),
        WALL_BLOCK("wall_block", "setup.prompt.wall_block"),
        PLOTS_PER_SIDE("plots_per_side", "setup.prompt.plots_per_side"),
        DONE("done", "")
    }

    private class Session {
        var step: Step = Step.MODE_SELECT
        var mode: SetupMode = SetupMode.NEW_WORLD
        var worldName: String? = null
        var plotSize: Int = 48
        var roadWidth: Int = 10
        var plotHeight: Int = 64
        var plotBlock: Material = Material.GRASS_BLOCK
        var roadBlock: Material = Material.DARK_PRISMARINE
        var wallBlock: Material = Material.DIAMOND_BLOCK
        var accentBlock: Material? = Material.SMOOTH_QUARTZ_STAIRS
        var plotsPerSide: Int = 128
        var schema: String = "default"

        fun applyRecommendedDefaults(foliaMode: Boolean): Session {
            val recommended = PlotSchemas.recommended(foliaMode)
            plotSize = recommended.plotSize
            roadWidth = recommended.roadWidth
            plotHeight = recommended.plotHeight
            plotBlock = recommended.plotBlock
            roadBlock = recommended.roadBlock
            wallBlock = recommended.wallBlock
            accentBlock = recommended.accentBlock
            plotsPerSide = recommended.plotsPerSide
            schema = recommended.schema
            return this
        }

        fun applyInput(
            rawInput: String, 
            i18n: systems.diath.homeclaim.platform.paper.I18n, 
            maxPlotsPerSide: Int,
            bulkBlockSetter: BulkBlockSetter,
            conversionAvailable: Boolean,
            foliaMode: Boolean,
        ): Result {
            // "default" = use the default value (same as empty input)
            val input = if (rawInput.lowercase() == "default" || rawInput.lowercase() == "d") "" else rawInput
            return when (step) {
                Step.MODE_SELECT -> {
                    when (input) {
                        "1", "new", "neu" -> {
                            mode = SetupMode.NEW_WORLD
                            step = Step.WORLD_NAME
                            Result(true, "ok")
                        }
                        "2", "convert", "konvertieren" -> {
                            if (foliaMode) {
                                return Result(false, "§cDie Konvertierung bestehender Welten ist auf Folia deaktiviert. Bitte erstelle dort eine neue Plot-Welt.")
                            }
                            if (!conversionAvailable || !bulkBlockSetter.isFaweAvailable()) {
                                return Result(false, i18n.msg("setup.convert_requires_fawe"))
                            }
                            mode = SetupMode.CONVERT_EXISTING
                            step = Step.WORLD_NAME
                            Result(true, "ok")
                        }
                        else -> Result(false, i18n.msg("setup.mode_invalid"))
                    }
                }
                Step.WORLD_NAME -> {
                    val name = input.lowercase()
                    if (mode == SetupMode.NEW_WORLD) {
                        val valid = name.matches(Regex("[a-z0-9_.-]+"))
                        if (!valid) return Result(false, i18n.msg("setup.invalid_world_name"))
                        if (Bukkit.getWorld(name) != null) return Result(false, i18n.msg("setup.world_exists", name))
                    } else {
                        // Convert mode - world must exist
                        if (Bukkit.getWorld(name) == null) {
                            return Result(false, i18n.msg("setup.convert_world_not_found", name))
                        }
                    }
                    worldName = name
                    step = Step.PLOT_SIZE
                    Result(true, "ok")
                }
                Step.PLOT_SIZE -> {
                    if (input.isNotEmpty()) {
                        val v = input.toIntOrNull() ?: return Result(false, i18n.msg("setup.invalid_number"))
                        plotSize = v
                    }
                    step = Step.ROAD_WIDTH
                    Result(true, "ok")
                }
                Step.ROAD_WIDTH -> {
                    if (input.isNotEmpty()) {
                        val v = input.toIntOrNull() ?: return Result(false, i18n.msg("setup.invalid_number"))
                        roadWidth = v
                    }
                    step = Step.PLOT_HEIGHT
                    Result(true, "ok")
                }
                Step.PLOT_HEIGHT -> {
                    if (input.isNotEmpty()) {
                        val v = input.toIntOrNull() ?: return Result(false, i18n.msg("setup.invalid_number"))
                        plotHeight = v
                    }
                    step = Step.PLOT_BLOCK
                    Result(true, "ok")
                }
                Step.PLOT_BLOCK -> {
                    if (input.isNotEmpty()) {
                        val mat = Material.matchMaterial(input.uppercase()) ?: return Result(false, i18n.msg("setup.material_unknown"))
                        if (!mat.isBlock) return Result(false, i18n.msg("setup.material_not_block"))
                        plotBlock = mat
                    }
                    step = Step.ROAD_BLOCK
                    Result(true, "ok")
                }
                Step.ROAD_BLOCK -> {
                    if (input.isNotEmpty()) {
                        val mat = Material.matchMaterial(input.uppercase()) ?: return Result(false, i18n.msg("setup.material_unknown"))
                        if (!mat.isBlock) return Result(false, i18n.msg("setup.material_not_block"))
                        roadBlock = mat
                    }
                    step = Step.WALL_BLOCK
                    Result(true, "ok")
                }
                Step.WALL_BLOCK -> {
                    if (input.isNotEmpty()) {
                        val mat = Material.matchMaterial(input.uppercase()) ?: return Result(false, i18n.msg("setup.material_unknown"))
                        if (!mat.isBlock) return Result(false, i18n.msg("setup.material_not_block"))
                        wallBlock = mat
                    }
                    step = Step.PLOTS_PER_SIDE
                    Result(true, "ok")
                }
                Step.PLOTS_PER_SIDE -> {
                    if (input.isNotEmpty()) {
                        val v = input.toIntOrNull() ?: return Result(false, i18n.msg("setup.invalid_number"))
                        if (v < 0 || v > maxPlotsPerSide) {
                            return Result(false, i18n.msg("setup.invalid_plots_per_side", maxPlotsPerSide.toString()))
                        }
                        if (foliaMode && v > 256) {
                            return Result(false, "§cFür Folia sind mehr als 256 Plots pro Seite im Ingame-Setup nicht empfohlen. Nutze für größere Layouts bitte später eine manuelle Server-Konfiguration.")
                        }
                        plotsPerSide = v
                    }
                    step = Step.DONE
                    Result(true, "ok")
                }
                Step.DONE -> Result(true, "ok")
            }
        }

        fun toConfig(): PlotWorldConfig {
            return PlotWorldConfig(
                worldName = requireNotNull(worldName),
                plotSize = plotSize,
                roadWidth = roadWidth,
                plotHeight = plotHeight,
                plotBlock = plotBlock,
                roadBlock = roadBlock,
                wallBlock = wallBlock,
                accentBlock = accentBlock,                  // Added
                plotsPerSide = plotsPerSide,
                schema = schema
            )
        }
    }
}

class PlotWorldConfigStore(
    private val plugin: org.bukkit.plugin.java.JavaPlugin
) {
    private val plotWorldDir = java.io.File(plugin.dataFolder, "plot-worlds")

    private fun worldFile(worldName: String): java.io.File {
        return java.io.File(plotWorldDir, "$worldName.toml")
    }

    /**
     * Load a saved plot world config by world name.
     * Returns null if the world is not configured.
     */
    fun loadConfig(worldName: String): PlotWorldConfig? {
        val file = worldFile(worldName)
        if (!file.exists()) return null

        val toml = runCatching { FileConfig.of(file).apply { load() } }.getOrNull() ?: return null

        val baseWall = Material.matchMaterial((toml.get<Any>("wallBlock") as? String) ?: "DIAMOND_BLOCK") ?: Material.DIAMOND_BLOCK
        val accent = (toml.get<Any>("accentBlock") as? String)?.let { Material.matchMaterial(it) }
        val result = PlotWorldConfig(
            worldName = worldName,
            plotSize = (toml.get<Any>("plotSize") as? Number)?.toInt() ?: 48,
            roadWidth = (toml.get<Any>("roadWidth") as? Number)?.toInt() ?: 10,
            plotHeight = (toml.get<Any>("plotHeight") as? Number)?.toInt() ?: 64,
            plotBlock = Material.matchMaterial((toml.get<Any>("plotBlock") as? String) ?: "GRASS_BLOCK") ?: Material.GRASS_BLOCK,
            roadBlock = Material.matchMaterial((toml.get<Any>("roadBlock") as? String) ?: "DARK_PRISMARINE") ?: Material.DARK_PRISMARINE,
            wallBlock = baseWall,
            accentBlock = accent,
            unclaimedBorderBlock = Material.matchMaterial((toml.get<Any>("unclaimedBorderBlock") as? String) ?: baseWall.name) ?: baseWall,
            claimedBorderBlock = Material.matchMaterial((toml.get<Any>("claimedBorderBlock") as? String) ?: (accent?.name ?: baseWall.name)) ?: (accent ?: baseWall),
            mergedBorderBlock = Material.matchMaterial((toml.get<Any>("mergedBorderBlock") as? String) ?: (accent?.name ?: baseWall.name)) ?: (accent ?: baseWall),
            saleBorderBlock = Material.matchMaterial((toml.get<Any>("saleBorderBlock") as? String) ?: "GOLD_BLOCK") ?: Material.GOLD_BLOCK,
            adminBorderBlock = Material.matchMaterial((toml.get<Any>("adminBorderBlock") as? String) ?: "EMERALD_BLOCK") ?: Material.EMERALD_BLOCK,
            reservedBorderBlock = Material.matchMaterial((toml.get<Any>("reservedBorderBlock") as? String) ?: "REDSTONE_BLOCK") ?: Material.REDSTONE_BLOCK,
            resetOnDelete = (toml.get<Any>("resetOnDelete") as? Boolean) ?: false,
            resetOnUnclaim = (toml.get<Any>("resetOnUnclaim") as? Boolean) ?: false,
            resetBatchColumnsPerTick = (toml.get<Any>("resetBatchColumnsPerTick") as? Number)?.toInt() ?: 128,
            schema = (toml.get<Any>("schema") as? String) ?: "default",
            plotsPerSide = (toml.get<Any>("plotsPerSide") as? Number)?.toInt() ?: 500
        )
        toml.close()
        return result
    }
    
    /**
     * Save config only (for converted worlds).
     */
    fun persistConfig(cfg: PlotWorldConfig) {
        plotWorldDir.mkdirs()
        val file = worldFile(cfg.worldName)
        val accent = cfg.accentBlock?.name ?: "SMOOTH_QUARTZ_STAIRS"
        val content = buildString {
            appendLine("worldName = \"${cfg.worldName}\"")
            appendLine("plotSize = ${cfg.plotSize}")
            appendLine("roadWidth = ${cfg.roadWidth}")
            appendLine("plotHeight = ${cfg.plotHeight}")
            appendLine("plotBlock = \"${cfg.plotBlock.name}\"")
            appendLine("roadBlock = \"${cfg.roadBlock.name}\"")
            appendLine("wallBlock = \"${cfg.wallBlock.name}\"")
            appendLine("accentBlock = \"$accent\"")
            appendLine("unclaimedBorderBlock = \"${cfg.unclaimedBorderBlock.name}\"")
            appendLine("claimedBorderBlock = \"${cfg.claimedBorderBlock.name}\"")
            appendLine("mergedBorderBlock = \"${cfg.mergedBorderBlock.name}\"")
            appendLine("saleBorderBlock = \"${cfg.saleBorderBlock.name}\"")
            appendLine("adminBorderBlock = \"${cfg.adminBorderBlock.name}\"")
            appendLine("reservedBorderBlock = \"${cfg.reservedBorderBlock.name}\"")
            appendLine("resetOnDelete = ${cfg.resetOnDelete}")
            appendLine("resetOnUnclaim = ${cfg.resetOnUnclaim}")
            appendLine("resetBatchColumnsPerTick = ${cfg.resetBatchColumnsPerTick}")
            appendLine("schema = \"${cfg.schema}\"")
            appendLine("plotsPerSide = ${cfg.plotsPerSide}")
            appendLine("converted = true")
        }
        file.writeText(content)
    }
    
    /**
     * Save config and create new world with custom generator.
     * 
     * On Folia: Bukkit.createWorld(WorldCreator) throws UnsupportedOperationException.
     * Instead we use WorldCreator.createWorld() on the GlobalRegionScheduler.
     * See: https://stackoverflow.com/questions/78856245
     * 
     * @param onComplete callback with result (needed because Folia runs async on GlobalRegionScheduler)
     */
    fun persistAndCreateWorld(cfg: PlotWorldConfig, onComplete: (WorldCreateResult) -> Unit) {
        persistConfig(cfg)

        if (Bukkit.getWorld(cfg.worldName) != null) {
            onComplete(WorldCreateResult.ALREADY_EXISTS)
            return
        }
        
        // Register generator in bukkit.yml (backup for next restart)
        registerGeneratorInBukkitYml(cfg)
        
        val creator = WorldCreator(cfg.worldName)
        creator.generator(PlotWorldChunkGenerator(cfg))
        
        if (isFolia()) {
            // Folia: Use GlobalRegionScheduler + WorldCreator.createWorld()
            // GlobalRegionScheduler is for world-level operations not tied to a specific region
            Bukkit.getGlobalRegionScheduler().run(plugin) { _ ->
                try {
                    val world = creator.createWorld()
                    if (world != null) {
                        applyWorldBorder(world, cfg)
                        onComplete(WorldCreateResult.CREATED)
                    } else {
                        onComplete(WorldCreateResult.CREATE_FAILED)
                    }
                } catch (e: Exception) {
                    plugin.logger.warning("Folia world creation failed: ${e.message}")
                    onComplete(WorldCreateResult.FOLIA_RESTART_REQUIRED)
                }
            }
        } else {
            // Paper/Spigot: Create world immediately on main thread
            try {
                val world = creator.createWorld() ?: run {
                    onComplete(WorldCreateResult.CREATE_FAILED)
                    return
                }
                applyWorldBorder(world, cfg)
                onComplete(WorldCreateResult.CREATED)
            } catch (e: UnsupportedOperationException) {
                onComplete(WorldCreateResult.FOLIA_RESTART_REQUIRED)
            }
        }
    }
    
    /**
     * Apply world border if plotsPerSide is configured.
     */
    private fun applyWorldBorder(world: org.bukkit.World, cfg: PlotWorldConfig) {
        if (cfg.plotsPerSide > 0) {
            val size = (cfg.gridSize() * cfg.plotsPerSide).toDouble()
            world.worldBorder.center = org.bukkit.Location(world, 0.0, 0.0, 0.0)
            world.worldBorder.size = size
        }
    }
    
    /**
     * Detect if running on Folia.
     */
    private fun isFolia(): Boolean {
        return try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
    
    /**
     * Register the custom generator in bukkit.yml so the world auto-loads on restart.
     * Entry: worlds.<name>.generator: HomeClaim
     */
    private fun registerGeneratorInBukkitYml(cfg: PlotWorldConfig) {
        try {
            val bukkitYml = java.io.File("bukkit.yml")
            if (!bukkitYml.exists()) return
            
            val yamlConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(bukkitYml)
            val worldPath = "worlds.${cfg.worldName}"
            
            if (yamlConfig.getString("$worldPath.generator") == null) {
                yamlConfig.set("$worldPath.generator", plugin.name)
                yamlConfig.save(bukkitYml)
            }
        } catch (e: Exception) {
            plugin.logger.warning("Could not register generator in bukkit.yml: ${e.message}")
        }
    }
}

/**
 * Result of world creation attempt.
 */
enum class WorldCreateResult {
    /** World was created successfully (Paper/Spigot) */
    CREATED,
    /** Config saved, server restart required (Folia) */
    FOLIA_RESTART_REQUIRED,
    /** World already exists */
    ALREADY_EXISTS,
    /** World creation failed */
    CREATE_FAILED
}
