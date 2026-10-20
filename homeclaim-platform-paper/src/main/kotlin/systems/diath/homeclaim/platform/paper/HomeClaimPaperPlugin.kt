package systems.diath.homeclaim.platform.paper

import systems.diath.homeclaim.api.EntryForceGrantDto
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionRole
import systems.diath.homeclaim.core.policy.SimplePolicyService
import systems.diath.homeclaim.core.service.AuditEntry
import systems.diath.homeclaim.core.service.AuditService
import systems.diath.homeclaim.core.service.BoundedAsyncAuditService
import systems.diath.homeclaim.core.service.AuditTaxonomy
import systems.diath.homeclaim.core.service.PolicyService
import systems.diath.homeclaim.core.service.RegionAdminServiceImpl
import systems.diath.homeclaim.core.store.JdbcAuditService
import systems.diath.homeclaim.core.store.JdbcComponentRepository
import systems.diath.homeclaim.core.store.JdbcFlagProfileService
import systems.diath.homeclaim.core.store.JdbcRegionRepository
import systems.diath.homeclaim.core.store.JdbcZoneRepository
import systems.diath.homeclaim.platform.paper.store.JdbcRatingRepository
import systems.diath.homeclaim.core.store.SqlDsl
import systems.diath.homeclaim.api.PlotRestServer
import systems.diath.homeclaim.api.HealthInfo
import org.flywaydb.core.Flyway
import systems.diath.homeclaim.platform.paper.I18n
import systems.diath.homeclaim.platform.paper.util.SafeEventHandler
import systems.diath.homeclaim.platform.paper.listener.PlayerCleanupManager
import systems.diath.homeclaim.platform.paper.lifecycle.ShutdownManager
import systems.diath.homeclaim.platform.paper.lifecycle.HealthCheckService
import systems.diath.homeclaim.platform.paper.lifecycle.DatabaseResilience
import systems.diath.homeclaim.platform.paper.config.HomeClaimTomlConfig
import systems.diath.homeclaim.platform.paper.clientlink.PaperClientLinkChannelListener
import systems.diath.homeclaim.platform.paper.util.Permissions
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import java.util.UUID
import javax.sql.DataSource

class HomeClaimPaperPlugin : JavaPlugin() {
    private val plotSetupWizard by lazy {
        systems.diath.homeclaim.platform.paper.plot.PlotWorldSetupWizard(
            configStore = systems.diath.homeclaim.platform.paper.plot.PlotWorldConfigStore(this),
            i18nProvider = { i18n },
            plugin = this,
            regionServiceProvider = { services?.regionService },
            worldAreaServiceProvider = { services?.worldAreaService }
        )
    }
    private var bridge: PaperPlatformBridge? = null
    private var eventDispatcher: systems.diath.homeclaim.core.event.EventDispatcher? = null
    private var plotMutationEventListener: systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationEventListener? = null
    private var econService: systems.diath.homeclaim.core.economy.SimpleEconService? = null
    private var restServer: io.ktor.server.engine.ApplicationEngine? = null
    private var webUxServer: io.ktor.server.engine.ApplicationEngine? = null
    private var webUxAuthStore: systems.diath.homeclaim.webux.WebUxAuthStore? = null
    private var services: PlatformServices? = null
    private var guiManager: systems.diath.homeclaim.platform.paper.gui.GuiManager? = null
    private var plotCommand: systems.diath.homeclaim.platform.paper.command.PlotCommand? = null
    private var zoneCommand: systems.diath.homeclaim.platform.paper.command.ZoneCommand? = null
    private var plotMutationHooksRegistered: Boolean = false
    private var plotMutationService: systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationService =
        systems.diath.homeclaim.platform.paper.plot.mutation.NoOpPlotMutationService
    private var plotResetService: systems.diath.homeclaim.platform.paper.plot.mutation.PlotResetService =
        systems.diath.homeclaim.platform.paper.plot.mutation.NoOpPlotResetService
    private var plotMutationBackend: systems.diath.homeclaim.core.mutation.WorldMutationBackend? = null
    private var plotMutationJobStore: systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationJobStore? = null
    private var plotBackupService: systems.diath.homeclaim.platform.paper.plot.mutation.PaperPlotBackupService? = null
    private var plotTransferService: systems.diath.homeclaim.platform.paper.plot.mutation.PaperPlotTransferService? = null
    private var plotMutationConflicts: systems.diath.homeclaim.platform.paper.plot.mutation.RegionMutationConflictCoordinator? = null
    private var biomeMutationService: systems.diath.homeclaim.platform.paper.plot.mutation.PaperBiomeMutationService? = null
    private var storageType: StorageType = StorageType.JDBC  // SQLite by default
    private var currentStorageConfig: StorageConfig? = null
    private var migrationConfig: MigrationConfig = MigrationConfig()
    private var i18n: I18n = I18n()
    private var clientLinkListener: PaperClientLinkChannelListener? = null
    private var clientLinkChannels: Set<String> = emptySet()
    internal val homeClaimConfig = HomeClaimTomlConfig(java.io.File("config/HomeClaim.toml"), this)

    fun bindServices(services: PlatformServices) {
        // Some optional integrations resolve services through this runtime field while
        // bindServices is still constructing listeners and commands.
        this.services = services
        bridge = PaperPlatformBridge(this, services).also { it.registerListeners() }
        logger.info(i18n.msg("bridge.registered"))
        registerPlotMutationHooks(services)
        
        // Initialize Economy system with auto-detection
        initializeEconomy()
        
        // Register GUI system
        guiManager = systems.diath.homeclaim.platform.paper.gui.GuiManager(
            regionService = services.regionService,
            zoneService = services.zoneService,
            componentService = services.componentService,
            adminService = requireNotNull(services.adminService) { "Region administration service is required for the active GUI" },
            roleService = requireNotNull(services.roleService) { "Region role service is required for the active GUI" },
            auditService = services.auditService,
            componentOperationService = requireNotNull(services.componentOperationService) { "Component operation service is required for the active GUI" },
            worldAreaService = services.worldAreaService
        ).also {
            server.pluginManager.registerEvents(it, this)
        }
        
        // Register pad creator listener
        server.pluginManager.registerEvents(systems.diath.homeclaim.platform.paper.gui.PadCreatorListener(), this)
        
        val ratingRepository = services.dataSource?.let { ds ->
            systems.diath.homeclaim.platform.paper.store.JdbcRatingRepository(ds)
        }
        val ratingOperations = ratingRepository?.let { repository ->
            systems.diath.homeclaim.core.service.RegionRatingService(services.regionService, repository, services.auditService)
        }
        val biomeMutation = services.worldAreaService?.let { worldAreas ->
            systems.diath.homeclaim.platform.paper.plot.mutation.PaperBiomeMutationService(this, worldAreas, plotMutationJobStore)
        }
        biomeMutationService = biomeMutation
        plotCommand = systems.diath.homeclaim.platform.paper.command.PlotCommand(
            services.regionService,
            guiManager!!,
            econService,
            plotResetService,
            plotMutationService,
            ratingRepository,
            i18n,
            plotMutationBackend,
            services.worldAreaService,
            systems.diath.homeclaim.core.service.RegionOperationLimitService(
                services.regionService,
                maxOwnedRegions = homeClaimConfig.getInt("homeclaim.limits.maxPlotsPerPlayer", -1),
                maxMergedRegions = homeClaimConfig.getInt("homeclaim.limits.maxMergedRegions", -1)
            ),
            services.auditService,
            worldAreaService = services.worldAreaService,
            mutationJobStore = plotMutationJobStore,
            adminService = services.adminService,
            roleService = services.roleService,
            homeService = services.regionHomeService,
            unclaimService = services.regionUnclaimService,
            biomeService = services.regionBiomeService,
            ratingService = ratingOperations,
            biomeMutationService = biomeMutation,
            backupService = plotBackupService,
            mutationConflicts = plotMutationConflicts ?: systems.diath.homeclaim.platform.paper.plot.mutation.RegionMutationConflictCoordinator(plotMutationJobStore),
            transferService = plotTransferService,
            componentService = services.componentService
        )
        zoneCommand = systems.diath.homeclaim.platform.paper.command.ZoneCommand(
            services.zoneService,
            services.regionService,
            i18n,
            services.worldAreaService
        )
        
        maybeStartRest(services)
        maybeStartWebUx()
        setupClientLinkChannels()
        this.services = services
        recoverPendingPlotWorlds(services.regionService)
    }

    private fun setupClientLinkChannels() {
        teardownClientLinkChannels()

        val enabled = homeClaimConfig.getBoolean("homeclaim.clientlink.enabled", false)
        if (!enabled) {
            logger.info("ClientLink channels disabled (homeclaim.clientlink.enabled=false)")
            return
        }

        val channels = homeClaimConfig.getStringList(
            "homeclaim.clientlink.channels",
            listOf("homeclaim:admin", "homeclaim:support")
        ).toSet()

        if (channels.isEmpty()) {
            logger.warning("ClientLink enabled but no channels configured; skipping registration")
            return
        }

        val listener = PaperClientLinkChannelListener(
            plugin = this,
            regionService = services?.regionService ?: return,
            logTraffic = homeClaimConfig.getBoolean("homeclaim.clientlink.logTraffic", false),
            worldAreaService = services?.worldAreaService
        )

        channels.forEach { channel ->
            server.messenger.registerIncomingPluginChannel(this, channel, listener)
            server.messenger.registerOutgoingPluginChannel(this, channel)
        }

        clientLinkListener = listener
        clientLinkChannels = channels
        logger.info("ClientLink channels registered: ${channels.joinToString(", ")}")
    }

    private fun teardownClientLinkChannels() {
        val listener = clientLinkListener
        if (listener != null) {
            clientLinkChannels.forEach { channel ->
                runCatching { server.messenger.unregisterIncomingPluginChannel(this, channel, listener) }
                runCatching { server.messenger.unregisterOutgoingPluginChannel(this, channel) }
            }
        }
        clientLinkListener = null
        clientLinkChannels = emptySet()
    }
    
    private fun initializeEconomy() {
        // Initialize EconService
        econService = systems.diath.homeclaim.core.economy.SimpleEconService()
        
        // Priority 1: Try Vault
        val vaultHandler = systems.diath.homeclaim.platform.paper.economy.BukkitVaultEconHandler()
        if (vaultHandler.init()) {
            econService!!.setHandler(vaultHandler)
            logger.info(i18n.msg("plugin.economy.vault"))
            return
        }
        
        // Priority 2: Try EssentialsX
        val essHandler = systems.diath.homeclaim.platform.paper.economy.EssentialsXEconHandler()
        if (essHandler.init()) {
            econService!!.setHandler(essHandler)
            logger.info(i18n.msg("plugin.economy.essentials"))
            return
        }
        
        // Priority 3: Mock (Testing/No Economy)
        val mockHandler = systems.diath.homeclaim.platform.paper.economy.MockEconHandler()
        mockHandler.init()
        econService!!.setHandler(mockHandler)
        logger.warning(i18n.msg("plugin.economy.mock"))
    }
    
    /**
     * Check if FastAsyncWorldEdit is installed.
     * FAWE is used only for converting an existing world to a plot world.
     * 
     * @return true if FAWE is available
     */
    private fun checkFaweInstalled(): Boolean {
        val fawePlugin = server.pluginManager.getPlugin("FastAsyncWorldEdit")
        
        if (fawePlugin == null || !fawePlugin.isEnabled) {
            logger.warning(i18n.msg("fawe.warning.line1"))
            logger.warning("║  ${centerPad(i18n.msg("fawe.warning.line2"), 62)}  ║")
            logger.warning(i18n.msg("fawe.warning.line3"))
            logger.warning("║  ${padRight(i18n.msg("fawe.warning.line4"), 62)}  ║")
            logger.warning("║  ${padRight(i18n.msg("fawe.warning.line5"), 62)}  ║")
            logger.warning("║  ${padRight("", 62)}  ║")
            logger.warning("║  ${padRight(i18n.msg("fawe.warning.line7"), 62)}  ║")
            logger.warning("║  ${padRight(i18n.msg("fawe.warning.line8"), 62)}  ║")
            logger.warning(i18n.msg("fawe.warning.line9"))
            return false
        }
        
        @Suppress("DEPRECATION")
        logger.info("§a✓ ${i18n.msg("fawe.detected", fawePlugin.description.version)}")
        return true
    }
    
    /** Pad string to the right with spaces */
    private fun padRight(s: String, length: Int): String {
        return if (s.length >= length) s.take(length) else s + " ".repeat(length - s.length)
    }
    
    /** Center a string with padding */
    private fun centerPad(s: String, length: Int): String {
        if (s.length >= length) return s.take(length)
        val padding = (length - s.length) / 2
        return " ".repeat(padding) + s + " ".repeat(length - s.length - padding)
    }

    /**
     * Called by Bukkit when a world needs this plugin's generator.
     * This is how configured plot worlds load their generator on server start.
     * 
     * bukkit.yml entry:
     *   worlds:
     *     plots:
     *       generator: HomeClaim
     */
    override fun getDefaultWorldGenerator(worldName: String, id: String?): org.bukkit.generator.ChunkGenerator? {
        // Ensure TOML config exists before trying to load plot world config.
        // This is called BEFORE onEnable().
        if (!homeClaimConfig.ensureExists()) {
            logger.warning("Failed to load ./config/HomeClaim.toml - using default world generator")
            return null
        }
        
        val configStore = systems.diath.homeclaim.platform.paper.plot.PlotWorldConfigStore(this)
        val cfg = configStore.loadConfig(worldName)
        if (cfg != null) {
            logger.info("Providing PlotWorldChunkGenerator for world '$worldName'")
            return systems.diath.homeclaim.platform.paper.plot.PlotWorldChunkGenerator(cfg)
        }
        logger.warning("No plot config found for world '$worldName' - using default generator")
        return null
    }
    
    override fun onEnable() {
        // Ensure TOML config exists (idempotent - safe to call multiple times)
        if (!homeClaimConfig.ensureExists()) {
            logger.severe("Could not initialize ./config/HomeClaim.toml")
            server.pluginManager.disablePlugin(this)
            return
        }
        i18n = loadI18n()
        
        // ============================================
        // LIFECYCLE: Initialize managers
        // ============================================
        ShutdownManager.init(this)
        HealthCheckService.init(this)
        
        // Initialize safe event handler for exception catching
        SafeEventHandler.init(this)
        
        // Register player cleanup listener (memory leak prevention)
        server.pluginManager.registerEvents(PlayerCleanupManager, this)
        
        // ============================================
        // FAWE DEPENDENCY CHECK - OPTIONAL!
        // ============================================
        checkFaweInstalled() // Nur Warnung, Plugin läuft weiter
        
        // Initialize sensor block registry
        BlockSensorRegistry.initialize(this)
        
        if (bridge == null) {
            val storage = loadStorageConfig()
            storageType = storage.type
            currentStorageConfig = storage
            
            // Log storage configuration
            logger.info("Using ${storage.driver} database: ${storage.sqliteFile ?: "${storage.host}:${storage.port}/${storage.database}"}")
            
            // Initialize EventDispatcher BEFORE creating services so stores can use it
            eventDispatcher = systems.diath.homeclaim.core.event.EventDispatcher()
            
            val services = createJdbcServices(storage)
            bindServices(services)
            
            // Register shutdown hooks
            registerShutdownHooks()
            
            // Run health check
            HealthCheckService.setDataSource(services.dataSource)
            val healthResult = HealthCheckService.runStartupValidation()
            if (!healthResult.success) {
                logger.warning(i18n.msg("plugin.startup.warnings"))
            }
            
            logger.info(i18n.msg("bootstrap.initialized", storage.type))
        }
    }
    
    private fun registerPlotMutationHooks(services: PlatformServices) {
        if (plotMutationHooksRegistered) return

        val dispatcher = services.eventDispatcher ?: eventDispatcher ?: return
        val configStore = systems.diath.homeclaim.platform.paper.plot.PlotWorldConfigStore(this)
        val mutationLimitForWorld: (String) -> Int = { world ->
            configStore.loadConfig(world)?.maxConcurrentMutationJobsPerWorld ?: Int.MAX_VALUE
        }
        val backend = systems.diath.homeclaim.platform.paper.plot.mutation.PaperSynchronousWorldMutationBackend(
            maxConcurrentMutationsForWorld = mutationLimitForWorld
        )
        plotMutationBackend = backend
        plotMutationJobStore = services.dataSource?.let { dataSource ->
            systems.diath.homeclaim.platform.paper.plot.mutation.JdbcPlotMutationJobStore(dataSource)
        }
        plotMutationConflicts = systems.diath.homeclaim.platform.paper.plot.mutation.RegionMutationConflictCoordinator(plotMutationJobStore)
        val backupCompression = systems.diath.homeclaim.platform.paper.plot.mutation.PlotBackupCompression.parse(
            homeClaimConfig.getString("homeclaim.backup.compression", "zstd")
        )
        logger.info("Plot backup compression: ${backupCompression.name.lowercase()}")
        plotBackupService = services.dataSource?.let { dataSource -> services.worldAreaService?.let { worldAreas ->
            systems.diath.homeclaim.platform.paper.plot.mutation.PaperPlotBackupService(
                this,
                systems.diath.homeclaim.platform.paper.plot.mutation.JdbcPlotBackupIndex(dataSource),
                worldAreas,
                requireNotNull(plotMutationJobStore),
                services.auditService,
                plotMutationConflicts,
                backupCompression
            )
        } }
        plotTransferService = if (plotBackupService != null && plotMutationJobStore != null && plotMutationConflicts != null) {
            systems.diath.homeclaim.platform.paper.plot.mutation.PaperPlotTransferService(
                this,
                requireNotNull(plotBackupService),
                requireNotNull(plotMutationConflicts),
                requireNotNull(plotMutationJobStore),
                services.auditService
            )
        } else null
        plotMutationService = systems.diath.homeclaim.platform.paper.plot.mutation.PaperPlotMutationService(
            this,
            mutationBackend = backend,
            worldAreaService = services.worldAreaService
        )
        plotResetService = systems.diath.homeclaim.platform.paper.plot.mutation.PaperPlotResetService(
            this,
            mutationBackend = backend,
            jobStore = plotMutationJobStore,
            worldAreaService = services.worldAreaService,
            backupService = plotBackupService,
            conflicts = plotMutationConflicts
        )

        dispatcher.registerListener(
            systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationEventListener(
                services.regionService,
                plotMutationService,
                plotResetService,
                deferReset = { region, reason, onComplete ->
                    runCatching {
                        server.scheduler.runTask(this, Runnable {
                            val queued = plotResetService.queueReset(region, reason, onComplete)
                            if (!queued) onComplete(systems.diath.homeclaim.platform.paper.plot.mutation.PlotResetCompletion(
                                systems.diath.homeclaim.platform.paper.plot.mutation.PlotResetCompletionStatus.FAILED,
                                0,
                                false,
                                "Reset was not queued"
                            ))
                        })
                        true
                    }.getOrDefault(false)
                }
            ).also { plotMutationEventListener = it }
        )
        server.pluginManager.registerEvents(
            systems.diath.homeclaim.platform.paper.plot.mutation.PlotJobWorldListener(
                logger,
                plotMutationService,
                plotResetService
            ),
            this
        )
        plotMutationHooksRegistered = true
        logger.info("Plot mutation hooks registered (Paper native backend)")
    }

    private fun savePlotMutationJobs() {
        try {
            val jobsFile = java.io.File(dataFolder, "plot-mutation-jobs.json")
            // Try to export job snapshots if backend is available
            val mutationBackend = plotMutationBackend as? systems.diath.homeclaim.platform.paper.plot.mutation.PaperSynchronousWorldMutationBackend
            if (mutationBackend != null) {
                val registry = mutationBackend.getJobRegistry()
                val json = registry.toPersistedFormat()
                jobsFile.parentFile?.mkdirs()
                jobsFile.writeText(json)
                val snapshots = registry.snapshot()
                if (snapshots.isNotEmpty()) {
                    logger.info("Saved ${snapshots.size} active plot mutation job snapshot(s); unfinished jobs are not resumed automatically")
                }
            }
        } catch (e: Exception) {
            logger.fine("Could not save plot mutation jobs: ${e.message}")
        }
    }

    private fun registerShutdownHooks() {
        // Cancel in-flight plot jobs before the rest server/data layer shuts down.
        ShutdownManager.registerHook("PlotJobs", priority = 30) {
            val diagnostics = plotMutationService.activeJobDiagnostics() + plotResetService.activeJobDiagnostics()
            val cancelled = plotMutationService.cancelPendingJobs() + plotResetService.cancelPendingJobs()
            if (diagnostics.isNotEmpty() || cancelled > 0) {
                logger.info("Cancelling $cancelled pending plot job(s) during shutdown")
                diagnostics.take(10).forEach { detail -> logger.info("PlotJob: $detail") }
            }
        }
        
        // REST server
        ShutdownManager.registerHook("RestServer", priority = 100) {
            restServer?.stop(500, 1000)
        }
        ShutdownManager.registerHook("WebUxServer", priority = 100) {
            webUxServer?.stop(500, 1000)
            webUxAuthStore?.revokeAll()
            webUxAuthStore = null
        }

        ShutdownManager.registerHook("AuditWriter", priority = 800) {
            (services?.auditService as? AutoCloseable)?.close()
        }
        
        // Database connection pool (last). Resolve at shutdown so a supported storage reload
        // closes the currently active pool rather than only the first startup pool.
        ShutdownManager.registerHook("DataSource", priority = 900) {
            closeDataSource(services?.dataSource)
        }
    }

    override fun onDisable() {
        // Save active plot mutation jobs before shutdown
        savePlotMutationJobs()

        // These operations touch Bukkit players/inventories and must run on Paper's server thread.
        if (org.bukkit.Bukkit.isPrimaryThread()) {
            PlayerCleanupManager.cleanupAll(server.onlinePlayers)
            guiManager?.closeAll()
            plotResetService.shutdown()
            plotTransferService?.shutdown()
            plotBackupService?.shutdown()
            biomeMutationService?.shutdown()
        }
        
        // Execute graceful shutdown with all registered hooks
        val result = ShutdownManager.shutdown()
        if (!result.success) {
            logger.warning(i18n.msg("shutdown.warnings", result.message))
        }

        teardownClientLinkChannels()
        
        // Clear references
        bridge = null
        restServer = null
        webUxServer = null
        services = null
    }

    private fun closeDataSource(dataSource: DataSource?) {
        when (dataSource) {
            is com.zaxxer.hikari.HikariDataSource -> dataSource.close()
            is java.io.Closeable -> dataSource.close()
        }
    }

    private fun runOnServerThread(action: () -> Unit) {
        if (org.bukkit.Bukkit.isPrimaryThread()) action()
        else server.scheduler.runTask(this, Runnable(action))
    }

    internal fun dispatchPaperCommand(command: String, sender: CommandSender, args: List<String>): Int {
        val handled = when (command.lowercase()) {
            "homeclaim" -> handleHomeClaimCommand(sender, args.toTypedArray())
            "plot" -> plotCommand?.execute(sender, args.toTypedArray()) ?: run {
                sender.sendMessage(i18n.msg("plugin.startup.warnings"))
                true
            }
            "zone" -> zoneCommand?.execute(sender, args.toTypedArray()) ?: run {
                sender.sendMessage(i18n.msg("plugin.startup.warnings"))
                true
            }
            else -> false
        }
        return if (handled) 1 else 0
    }

    private fun handleHomeClaimCommand(sender: CommandSender, args: Array<out String>): Boolean {
        val sub = args.getOrNull(0)?.lowercase()
        when (sub) {
            "web" -> {
                if (args.getOrNull(1)?.lowercase() != "login") {
                    sender.sendMessage("§eVerwendung: /homeclaim web login")
                    return true
                }
                val player = sender as? Player ?: run {
                    sender.sendMessage("§cDieser Befehl ist nur im Spiel verfügbar.")
                    return true
                }
                if (webUxServer == null || webUxAuthStore == null) {
                    sender.sendMessage("§cDie WebUX ist nicht aktiviert.")
                    return true
                }
                val code = runCatching { webUxAuthStore!!.issuePairing(player.uniqueId, player.name) }.getOrElse {
                    sender.sendMessage("§cEs konnte kein Web-Login-Code erstellt werden. Bitte später erneut versuchen.")
                    return true
                }
                player.sendMessage("§aWeb-Login-Code (2 Minuten gültig, einmal verwendbar): §f$code")
                player.sendMessage("§7Öffne die HomeClaim-Weboberfläche und gib diesen Code ein. Teile ihn mit niemandem.")
                return true
            }
            "reload" -> {
                if (!sender.hasPermission("homeclaim.admin.reload")) {
                    sender.sendMessage(i18n.msg("cmd.no.perm"))
                    return true
                }
                if (!homeClaimConfig.reload()) {
                    sender.sendMessage("§cFailed to reload ./config/HomeClaim.toml")
                    return true
                }
                i18n = loadI18n()
                migrationConfig = loadMigrationConfig()
                val newStorage = loadStorageConfig()
                val current = currentStorageConfig
                val storageChanged = current == null || current != newStorage

                if (storageChanged) {
                    sender.sendMessage(i18n.msg("storage.changed"))
                    restServer?.stop(500, 1000)
                    restServer = null
                    webUxServer?.stop(500, 1000)
                    webUxServer = null
                    webUxAuthStore?.revokeAll()
                    webUxAuthStore = null
                    guiManager?.closeAll()
                    plotResetService.shutdown()
                    biomeMutationService?.shutdown()
                    plotMutationService.cancelPendingJobs()
                    HandlerList.unregisterAll(this)
                    plotMutationEventListener?.let { eventDispatcher?.unregisterListener(it) }
                    plotMutationEventListener = null
                    plotMutationHooksRegistered = false
                    val previousServices = services
                    if (previousServices != null) closeRuntimeServices(previousServices)
                    eventDispatcher = systems.diath.homeclaim.core.event.EventDispatcher()
                    services = null
                    bridge = null
                    storageType = newStorage.type
                    currentStorageConfig = newStorage
                    val rebuilt = createJdbcServices(newStorage)
                    bindServices(rebuilt)
                    sender.sendMessage(i18n.msg("storage.rebuilt"))
                } else {
                    restServer?.stop(500, 1000)
                    restServer = null
                    webUxServer?.stop(500, 1000)
                    webUxServer = null
                    webUxAuthStore?.revokeAll()
                    webUxAuthStore = null
                    val svc = services
                    if (svc != null) {
                        val jdbc = svc.flagProfileService as? JdbcFlagProfileService
                        loadProfilesFromConfig(null, jdbc, clearExisting = true)
                        maybeStartRest(svc)
                        maybeStartWebUx()
                        setupClientLinkChannels()
                        sender.sendMessage(i18n.msg("storage.reload.ok"))
                    } else {
                        sender.sendMessage(i18n.msg("storage.reload.none"))
                    }
                }
            }
            "migrate" -> {
                if (!sender.hasPermission("homeclaim.admin.reload")) {
                    sender.sendMessage(i18n.msg("cmd.no.perm"))
                    return true
                }
                val storage = currentStorageConfig
                if (storage == null) {
                    sender.sendMessage(i18n.msg("cmd.migrate.no.storage"))
                    return true
                }
                val ds = services?.dataSource ?: buildDataSource(storage)
                runCatching { maybeMigrate(ds, storage.driver) }
                    .onSuccess { sender.sendMessage(i18n.msg("cmd.migrate.ok")) }
                    .onFailure { sender.sendMessage(i18n.msg("cmd.migrate.fail", it.message ?: "unknown")) }
            }
            "setup" -> {
                if (!sender.hasPermission("homeclaim.admin.setup")) {
                    sender.sendMessage(i18n.msg("cmd.no.perm"))
                    return true
                }
                return plotSetupWizard.handle(sender, args.drop(1))
            }
            "plot" -> {
                if (!sender.hasPermission("homeclaim.admin.setup")) {
                    sender.sendMessage(i18n.msg("cmd.no.perm"))
                    return true
                }
                return handlePlotAdminCommand(sender, args.drop(1))
            }
            "entryforce", "forceentry", "entry-force" -> {
                if (!sender.hasPermission(Permissions.ADMIN_ENTRY_FORCE)) {
                    sender.sendMessage(i18n.msg("cmd.no.perm"))
                    return true
                }
                return handleEntryForceCommand(sender, args.drop(1))
            }
            "audit" -> {
                if (!sender.hasPermission("homeclaim.audit.view")) {
                    sender.sendMessage(i18n.msg("cmd.no.perm"))
                    return true
                }
                val coreSender = if (sender is org.bukkit.entity.Player) {
                    systems.diath.homeclaim.platform.paper.command.BukkitPlayerCommandSenderAdapter(sender)
                } else {
                    systems.diath.homeclaim.platform.paper.command.BukkitCommandSenderAdapter(sender)
                }
                val activeServices = services
                if (activeServices == null) {
                    sender.sendMessage(i18n.msg("plugin.startup.warnings"))
                    return true
                }
                val auditCmd = systems.diath.homeclaim.platform.paper.command.AuditCommand(
                    activeServices.auditReader,
                    activeServices.regionService,
                    i18n
                )
                return auditCmd.execute(coreSender, args.drop(1).toTypedArray())
            }
            else -> {
                sender.sendMessage(i18n.msg("cmd.usage"))
            }
        }
        return true
    }

    private fun closeRuntimeServices(services: PlatformServices) {
        runCatching { (services.auditService as? AutoCloseable)?.close() }
            .onFailure { logger.log(java.util.logging.Level.WARNING, "Could not close the previous audit writer", it) }
        runCatching { closeDataSource(services.dataSource) }
            .onFailure { logger.log(java.util.logging.Level.WARNING, "Could not close the previous database pool", it) }
    }

    private fun handleEntryForceCommand(sender: CommandSender, args: List<String>): Boolean {
        if (args.size < 3) {
            sender.sendMessage("§eUsage: /homeclaim entryforce <player|uuid> <here|any|regionUuid> <reason...>")
            return true
        }

        val target = resolveOnlinePlayer(args[0])
        if (target == null) {
            sender.sendMessage("§cTarget player must be online for a temporary entry force grant.")
            return true
        }

        val scope = resolveEntryForceScope(sender, args[1]) ?: return true
        val regionId = scope.regionId
        val reason = args.drop(2).joinToString(" ").trim()
        if (reason.isBlank()) {
            sender.sendMessage("§cA reason is required.")
            return true
        }

        val grantedBy = (sender as? Player)?.uniqueId
        val grant = EntryForceRegistry.grant(
            playerId = target.uniqueId,
            regionId = regionId,
            grantedBy = grantedBy,
            reason = reason
        )
        auditEntryForceGrant(grant)
        sender.sendMessage(
            "§aEntry force grant ${grant.id.toString().take(8)} active for ${target.name} " +
                "(${regionId?.value ?: "any denied plot"}, expires ${grant.expiresAt})."
        )
        target.sendMessage("§eTemporary HomeClaim entry override granted. Reason: $reason")
        return true
    }

    private fun resolveOnlinePlayer(input: String): Player? {
        server.getPlayerExact(input)?.let { return it }
        val uuid = runCatching { UUID.fromString(input) }.getOrNull() ?: return null
        return server.getPlayer(uuid)
    }

    private data class EntryForceScope(val regionId: RegionId?)

    private fun resolveEntryForceScope(sender: CommandSender, scope: String): EntryForceScope? {
        return when {
            scope.equals("any", ignoreCase = true) -> EntryForceScope(null)
            scope.equals("here", ignoreCase = true) -> {
                val player = sender as? Player
                if (player == null) {
                    sender.sendMessage("§cScope 'here' can only be used by an in-game player.")
                    return null
                }
                val location = player.location
                val regionId = services?.regionService?.getRegionAt(
                    location.world.name,
                    location.blockX,
                    location.blockY,
                    location.blockZ
                )
                if (regionId == null) {
                    sender.sendMessage("§cNo HomeClaim plot found at your current position.")
                    return null
                }
                EntryForceScope(regionId)
            }
            else -> {
                val regionId = runCatching { RegionId(UUID.fromString(scope)) }.getOrNull()
                if (regionId == null) {
                    sender.sendMessage("§cScope must be 'here', 'any', or a region UUID.")
                    return null
                }
                if (services?.regionService?.getRegionById(regionId) == null) {
                    sender.sendMessage("§cPlot not found for region UUID ${regionId.value}.")
                    return null
                }
                EntryForceScope(regionId)
            }
        }
    }

    private fun auditEntryForceGrant(grant: EntryForceGrant, auditService: AuditService? = services?.auditService) {
        auditService?.append(
            AuditEntry(
                actorId = grant.grantedBy,
                actorKind = if (grant.grantedBy == null) "SYSTEM" else "PLAYER",
                actorRef = grant.grantedBy?.toString() ?: "system",
                targetId = grant.regionId?.value,
                category = AuditTaxonomy.Category.REGION,
                action = AuditTaxonomy.Action.ENTRY_FORCE_GRANTED,
                payload = mapOf(
                    "grantId" to grant.id.toString(),
                    "playerId" to grant.playerId.toString(),
                    "regionId" to grant.regionId?.value?.toString(),
                    "reason" to grant.reason,
                    "expiresAt" to grant.expiresAt.toString()
                )
            )
        )
    }

    private fun handlePlotAdminCommand(sender: CommandSender, args: List<String>): Boolean {
        when (args.getOrNull(0)?.lowercase()) {
            "init", "initialize", "recover" -> {
                val worldName = args.getOrNull(1)?.trim()?.lowercase()
                if (worldName.isNullOrBlank()) {
                    sender.sendMessage("§eUsage: /homeclaim plot init <world>")
                    return true
                }

                val svc = services?.regionService
                if (svc == null) {
                    sender.sendMessage("§cRegionService is not available right now.")
                    return true
                }

                val world = server.getWorld(worldName)
                if (world == null) {
                    sender.sendMessage("§cWorld '$worldName' is not loaded.")
                    return true
                }

                val cfg = systems.diath.homeclaim.platform.paper.plot.PlotWorldConfigStore(this).loadConfig(worldName)
                if (cfg == null) {
                    sender.sendMessage("§cNo plot world config found for '$worldName'.")
                    return true
                }

                sender.sendMessage("§eInitializing plot regions for '$worldName'...")
                val initializer = systems.diath.homeclaim.platform.paper.plot.PlotWorldInitializer(svc, this, services?.worldAreaService)
                initializer.initializePlots(worldName, cfg).thenAccept { created ->
                    runOnServerThread {
                        if (sender !is Player || sender.isOnline) {
                            sender.sendMessage("§a✓ Plot initialization finished for '$worldName' ($created new regions).")
                        }
                    }
                }.exceptionally { ex ->
                    runOnServerThread {
                        if (sender !is Player || sender.isOnline) {
                            sender.sendMessage("§cPlot initialization failed for '$worldName': ${ex.message}")
                        }
                    }
                    null
                }
                return true
            }
            else -> {
                sender.sendMessage("§eUsage: /homeclaim plot init <world>")
                return true
            }
        }
    }

    private fun recoverPendingPlotWorlds(regionService: systems.diath.homeclaim.core.service.RegionService) {
        val configStore = systems.diath.homeclaim.platform.paper.plot.PlotWorldConfigStore(this)
        val initializer = systems.diath.homeclaim.platform.paper.plot.PlotWorldInitializer(regionService, this, services?.worldAreaService)

        server.worlds.forEach { world ->
            val cfg = configStore.loadConfig(world.name) ?: return@forEach
            if (cfg.plotsPerSide <= 0) return@forEach

            val expected = cfg.plotsPerSide * cfg.plotsPerSide
            val areaId = initializer.getOrCreatePlotArea(world, cfg)
            val existing = regionService.listAllRegions().count { it.areaId == areaId }
            if (existing >= expected) return@forEach

            logger.info("Recovering plot regions for world '${world.name}' ($existing/$expected present)")
            initializer.initializePlots(world.name, cfg).thenAccept { created ->
                runOnServerThread {
                    if (created > 0) {
                        logger.info("Recovered $created missing plot regions for world '${world.name}'")
                    }
                }
            }.exceptionally { ex ->
                runOnServerThread { logger.warning("Failed to recover plot regions for '${world.name}': ${ex.message}") }
                null
            }
        }
    }

    private fun createJdbcServices(config: StorageConfig): PlatformServices {
        if (config.driver == DriverType.MYSQL) {
            logger.warning(
                "MySQL storage is experimental and not an officially supported HomeClaim database. " +
                    "Use PostgreSQL, MariaDB, or SQLite for supported deployments."
            )
        }
        val dataSource = buildDataSource(config)
        if (config.driver != DriverType.SQLITE && (config.username.isNullOrBlank() || config.password.isNullOrBlank())) {
            logger.warning(i18n.msg("jdbc.warn.no.creds"))
        }
        
        // Initialize database resilience
        DatabaseResilience.init(this, dataSource)
        
        maybeMigrate(dataSource, config.driver)
        val worldAreaRepo = systems.diath.homeclaim.core.store.JdbcWorldAreaRepository(dataSource)
        worldAreaRepo.ensureSchema()
        worldAreaRepo.migrateLegacyWorldNames()
        server.worlds.forEach { paperWorld ->
            val registered = worldAreaRepo.getWorldByPlatformId(paperWorld.uid)
                ?: worldAreaRepo.getWorldByName(paperWorld.name)?.takeIf { it.platformWorldId == null }
            if (registered != null) {
                worldAreaRepo.bindPlatformWorld(registered.id, paperWorld.uid, paperWorld.name)
            }
        }
        worldAreaRepo.warmRuntimeCache()
        val auditReader = JdbcAuditService(dataSource)
        val auditService = BoundedAsyncAuditService(
            sink = auditReader,
            onFailure = { message, error ->
                if (error == null) logger.warning(message)
                else logger.log(java.util.logging.Level.SEVERE, message, error)
            }
        )
        val regionRepo = JdbcRegionRepository(dataSource, eventDispatcher, auditService, worldAreaRepo)
        val componentRepo = JdbcComponentRepository(dataSource)
        val zoneRepo = JdbcZoneRepository(dataSource, worldAreaRepo)
        val entryDenyRepo = systems.diath.homeclaim.core.store.JdbcEntryDenyRepository(dataSource)
        val profileService = JdbcFlagProfileService(dataSource)
        val mutationAuthorizer = PaperRegionMutationAuthorizer()
        val roleRepository = systems.diath.homeclaim.core.service.RegionRoleService(regionRepo, auditService, mutationAuthorizer)
        val policy: PolicyService = SimplePolicyService(
            regionService = regionRepo,
            zoneService = zoneRepo,
            roleResolver = { regionId, playerId ->
                val region = regionRepo.getRegionById(regionId) ?: return@SimplePolicyService RegionRole.VISITOR
                region.roles.resolve(playerId, region.owner)
            },
            flagProfileService = profileService,
            entryDenyService = entryDenyRepo,
            areaService = worldAreaRepo,
            permissionProvider = systems.diath.homeclaim.platform.paper.permission.PaperCapabilityPermissionProvider()
        )
        loadProfilesFromConfig(null, profileService)
        val admin = RegionAdminServiceImpl(
            regionService = regionRepo,
            profileService = profileService,
            profileJdbc = profileService,
            auditService = auditService,
            authorizer = mutationAuthorizer
        )
        val homes = systems.diath.homeclaim.core.service.RegionHomeService(
            regionRepo, auditService, mutationAuthorizer
        )
        val unclaims = systems.diath.homeclaim.core.service.RegionUnclaimService(
            regionRepo, auditService, mutationAuthorizer
        )
        val biomes = systems.diath.homeclaim.core.service.RegionBiomeService(
            regionRepo, auditService, mutationAuthorizer
        )
        val componentOperations = systems.diath.homeclaim.core.service.ComponentOperationService(
            components = componentRepo,
            regions = regionRepo,
            audit = auditService,
            authorizer = mutationAuthorizer
        )
        val entryDenyOperations = systems.diath.homeclaim.core.service.EntryDenyOperationService(
            entryDenies = entryDenyRepo,
            regions = regionRepo,
            audit = auditService,
            authorizer = mutationAuthorizer
        )
        return PlatformServices(
            policyService = policy,
            regionService = regionRepo,
            componentService = componentRepo,
            componentOperationService = componentOperations,
            zoneService = zoneRepo,
            entryDenyService = entryDenyRepo,
            entryDenyOperationService = entryDenyOperations,
            adminService = admin,
            flagProfileService = profileService,
            auditService = auditService,
            dataSource = dataSource,
            eventDispatcher = eventDispatcher,
            worldAreaService = worldAreaRepo,
            roleService = roleRepository,
            regionUnclaimService = unclaims,
            regionBiomeService = biomes,
            auditReader = auditReader,
            regionHomeService = homes
        )
    }

    private fun buildDataSource(config: StorageConfig): DataSource {
        val jdbcUrl = buildJdbcUrl(config)
        val driverClassName = driverClassName(config.driver)
        return SqlDsl.hikari(
            jdbcUrl = jdbcUrl,
            username = config.username,
            password = config.password,
            driverClassName = driverClassName,
            // SQLite is a single-writer database; a single pooled connection prevents HomeClaim's
            // asynchronous audit writer racing synchronous mutation journals for the write lock.
            maximumPoolSize = if (config.driver == DriverType.SQLITE) 1 else config.maximumPoolSize,
            // SQLite allows one writer at a time. Audit and mutation journals may write concurrently;
            // let SQLite serialize short writes instead of failing immediately with SQLITE_BUSY.
            connectionInitSql = if (config.driver == DriverType.SQLITE) "PRAGMA busy_timeout=5000" else null
        )
    }

    private fun buildJdbcUrl(config: StorageConfig): String {
        val hostPortDb = "${config.host}:${config.port}/${config.database}"
        val encodingParam = when (config.driver) {
            DriverType.POSTGRES -> "?charSet=${config.encoding}"
            DriverType.MARIADB, DriverType.MYSQL -> "?characterEncoding=${config.encoding}"
            DriverType.SQLITE -> ""
        }
        return when (config.driver) {
            DriverType.POSTGRES -> "jdbc:postgresql://$hostPortDb$encodingParam"
            DriverType.MARIADB -> "jdbc:mariadb://$hostPortDb$encodingParam"
            DriverType.MYSQL -> "jdbc:mysql://$hostPortDb$encodingParam"
            DriverType.SQLITE -> "jdbc:sqlite:${config.sqliteFile ?: config.database}"
        }
    }

    private fun loadStorageConfig(): StorageConfig {
        // Default to JDBC with SQLite for persistent storage
        val typeStr = homeClaimConfig.getString("homeclaim.storage.type", "JDBC")
        val type = try {
            StorageType.valueOf(typeStr.uppercase())
        } catch (e: IllegalArgumentException) {
            logger.warning("Invalid storage type '$typeStr', defaulting to JDBC")
            StorageType.JDBC
        }
        
        val driver = DriverType.from(homeClaimConfig.getString("homeclaim.storage.driver", "SQLITE"))
        val sqliteFile = homeClaimConfig.getString("homeclaim.storage.sqliteFile", "").ifBlank { null }
        val encoding = homeClaimConfig.getString("homeclaim.storage.encoding", "UTF-8")
        migrationConfig = loadMigrationConfig()
        
        // Only warn if sqliteFile is explicitly set but driver is not SQLITE
        if (driver != DriverType.SQLITE && sqliteFile != null && sqliteFile.isNotBlank()) {
            logger.warning(i18n.msg("sqlite.warn.mismatch"))
        }
        
        return StorageConfig(
            type = type,
            host = homeClaimConfig.getString("homeclaim.storage.host", "localhost"),
            port = homeClaimConfig.getInt("homeclaim.storage.port", 5432),
            database = homeClaimConfig.getString("homeclaim.storage.database", "homeclaim"),
            username = homeClaimConfig.getString("homeclaim.storage.username", "").ifBlank { null },
            password = homeClaimConfig.getString("homeclaim.storage.password", "").ifBlank { null },
            driver = driver,
            sqliteFile = if (driver == DriverType.SQLITE) (sqliteFile ?: "plugins/HomeClaim/homeclaim.db") else null,
            encoding = encoding,
            maximumPoolSize = homeClaimConfig.getInt("homeclaim.storage.maximumPoolSize", 10)
        )
    }

    private fun loadMigrationConfig(): MigrationConfig {
        return MigrationConfig(
            enabled = homeClaimConfig.getBoolean("homeclaim.migrations.enabled", storageType == StorageType.JDBC && migrationConfig.enabled),
            allowNonPostgres = homeClaimConfig.getBoolean("homeclaim.migrations.allowNonPostgres", true),
            baselineOnMigrate = homeClaimConfig.getBoolean("homeclaim.migrations.baselineOnMigrate", true),
            locations = homeClaimConfig.getStringList("homeclaim.migrations.locations", listOf("classpath:db/migration/common"))
        )
    }

    private fun maybeMigrate(dataSource: DataSource, driver: DriverType) {
        if (!migrationConfig.enabled) {
            logger.info(i18n.msg("migrations.disabled"))
            return
        }
        if (driver != DriverType.POSTGRES && !migrationConfig.allowNonPostgres) {
            logger.warning(i18n.msg("migrations.nonpostgres"))
            return
        }
        val locations = mutableListOf<String>().apply {
            // Older pre-release configs used the parent path. Flyway scans child folders
            // recursively, so use only the common schema folder before adding one dialect.
            addAll(migrationConfig.locations.map { location ->
                if (location == "classpath:db/migration") "classpath:db/migration/common" else location
            })
            when (driver) {
                DriverType.POSTGRES -> add("classpath:db/migration/postgres")
                DriverType.MARIADB -> add("classpath:db/migration/mariadb")
                DriverType.MYSQL -> { /* no extra */ }
                DriverType.SQLITE -> { /* no extra */ }
            }
        }
        try {
            // Paper isolates plugins with dedicated classloaders; pass the plugin loader so
            // Flyway discovers the bundled classpath:db/migration resources.
            Flyway.configure(javaClass.classLoader)
                .locations(*locations.toTypedArray())
                .dataSource(dataSource)
                .baselineOnMigrate(migrationConfig.baselineOnMigrate)
                .load()
                .migrate()
            logger.info(i18n.msg("migrations.ok", locations.joinToString()))
        } catch (ex: Exception) {
            logger.severe(i18n.msg("migrations.fail", ex.message ?: "unknown"))
            throw ex
        }
    }

    private fun maybeStartRest(services: PlatformServices) {
        val enabled = homeClaimConfig.getBoolean("homeclaim.rest.enabled", false)
        if (!enabled) return
        val port = homeClaimConfig.getInt("homeclaim.rest.port", 8080)
        val bindHost = homeClaimConfig.getString("homeclaim.rest.bindHost", "127.0.0.1").ifBlank { "127.0.0.1" }
        val allowRemote = homeClaimConfig.getBoolean("homeclaim.rest.allowRemote", false)
        val token = homeClaimConfig.getString("homeclaim.rest.token", "").takeIf { it.isNotBlank() }
        val rateLimit = homeClaimConfig.getInt("homeclaim.rest.rateLimitPerMinute", 0).takeIf { it > 0 }
        val tokenEnvKey = homeClaimConfig.getString("homeclaim.rest.tokenEnv", "").ifBlank { null }
        val tokenFilePath = homeClaimConfig.getString("homeclaim.rest.tokenFile", "").ifBlank { null }
        val allowLocalhost = homeClaimConfig.getBoolean("homeclaim.rest.allowLocalhost", false)
        val allowedHosts = homeClaimConfig.getStringList("homeclaim.rest.allowedHosts")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val allowedHostHeaders = homeClaimConfig.getStringList("homeclaim.rest.allowedHostHeaders")
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val corsOrigins = homeClaimConfig.getStringList("homeclaim.rest.cors.allowedOrigins")
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val resolvedToken = resolveToken(token, tokenEnvKey, tokenFilePath)
        val adminService = services.adminService
        if (adminService == null) {
            logger.warning(i18n.msg("rest.missing.admin"))
            return
        }
        if (resolvedToken.isNullOrBlank()) {
            logger.warning(i18n.msg("rest.missing.token"))
            return
        }
        val loopback = bindHost.equals("localhost", true) || bindHost == "127.0.0.1" || bindHost == "::1"
        if (!loopback && !allowRemote) {
            logger.warning("REST remote bind refused: set homeclaim.rest.allowRemote=true only after configuring authentication and network restrictions")
            return
        }
        if ((allowRemote || !loopback) && (resolvedToken.length < 32 || allowedHosts.isEmpty() || allowedHostHeaders.isEmpty())) {
            logger.warning("REST remote bind refused: use a 32+ character token plus allowedHosts and allowedHostHeaders")
            return
        }
        val healthInfo = HealthInfo(
            version = pluginMeta.version,
            storage = storageType.name
        )
        val metricsService = systems.diath.homeclaim.platform.paper.PaperServerMetricsService(
            pluginMeta.version,
            services.regionService,
            plotMutationService,
            plotResetService,
            plotMutationBackend
        )
        val ownerMetadataResolver = systems.diath.homeclaim.platform.paper.PaperOwnerMetadataResolver()
        val rest = PlotRestServer(
            regionService = services.regionService,
            zoneService = services.zoneService,
            componentService = services.componentService,
            entryDenyService = services.entryDenyService,
            adminService = adminService,
            auditService = services.auditService,
            metricsService = metricsService,
            ownerMetadataResolver = ownerMetadataResolver,
            port = port,
            authToken = resolvedToken,
            rateLimitPerMinute = rateLimit ?: 60,
            healthInfo = healthInfo,
            allowedHosts = allowedHosts,
            allowLocalhost = allowLocalhost,
            enableCors = homeClaimConfig.getBoolean("homeclaim.rest.cors.enabled", false),
            bindHost = bindHost,
            allowRemote = allowRemote,
            allowedHostHeaders = allowedHostHeaders,
            allowedCorsOrigins = corsOrigins,
            entryDenyCreatedHandler = { rule -> kickPlayersDeniedByEntryRule(services, rule) },
            policyService = services.policyService,
            entryForceHandler = { regionId, playerId, reason, ttlSeconds ->
                val grant = EntryForceRegistry.grant(
                    playerId = playerId,
                    regionId = regionId,
                    grantedBy = null,
                    reason = reason,
                    ttlSeconds = ttlSeconds
                )
                auditEntryForceGrant(grant, services.auditService)
                EntryForceGrantDto(
                    id = grant.id.toString(),
                    playerId = grant.playerId.toString(),
                    regionId = grant.regionId?.value?.toString(),
                    grantedBy = grant.grantedBy?.toString(),
                    reason = grant.reason,
                    expiresAt = grant.expiresAt.toString()
                )
            },
            roleService = services.roleService,
            entryDenyOperationService = services.entryDenyOperationService
        )
        try {
            restServer = rest.start()
            logger.info(
                i18n.msg(
                    "rest.started",
                    port.toString(),
                    " (token)",
                    if (rateLimit != null) " (rate: $rateLimit/min)" else ""
                )
            )
        } catch (failure: Throwable) {
            restServer = null
            logger.log(java.util.logging.Level.SEVERE, i18n.msg("rest.start.failed", port), failure)
        }
    }

    private fun maybeStartWebUx() {
        if (!homeClaimConfig.getBoolean("homeclaim.webux.enabled", false)) return
        val port = homeClaimConfig.getInt("homeclaim.webux.port", 8081)
        val host = homeClaimConfig.getString("homeclaim.webux.host", "127.0.0.1").ifBlank { "127.0.0.1" }
        val allowRemote = homeClaimConfig.getBoolean("homeclaim.webux.allowRemote", false)
        val secureCookies = homeClaimConfig.getBoolean("homeclaim.webux.secureCookies", false)
        val allowedHosts = homeClaimConfig.getStringList("homeclaim.webux.allowedHosts").map { it.trim() }.filter(String::isNotEmpty).toSet()
        val allowedOrigins = homeClaimConfig.getStringList("homeclaim.webux.allowedOrigins").map { it.trim() }.filter(String::isNotEmpty).toSet()
        val authStore = systems.diath.homeclaim.webux.WebUxAuthStore()
        val loopback = host.equals("localhost", true) || host == "127.0.0.1" || host == "::1"
        if ((!loopback && !allowRemote) || ((allowRemote || !loopback) && (!secureCookies || allowedHosts.isEmpty() || allowedOrigins.isEmpty()))) {
            logger.warning("WebUX remote bind refused: require allowRemote, secureCookies, allowedHosts, and allowedOrigins")
            return
        }
        try {
            webUxServer = systems.diath.homeclaim.webux.HomeClaimWebUxServer(
                port = port,
                host = host,
                allowRemote = allowRemote,
                secureCookies = secureCookies,
                allowedHosts = allowedHosts,
                allowedOrigins = allowedOrigins,
                auditService = services?.auditService,
                authStore = authStore
            ).start()
            webUxAuthStore = authStore
            logger.info("HomeClaim WebUX started on $host:$port")
        } catch (failure: Exception) {
            webUxServer = null
            webUxAuthStore = null
            logger.severe("HomeClaim WebUX failed to start: ${failure.message ?: failure.javaClass.simpleName}")
        }
    }

    private fun loadI18n(): I18n {
        val tag = homeClaimConfig.getString("homeclaim.locale", "en")
        return I18n(localeTag = tag, loader = this::class.java.classLoader)
    }

    private fun kickPlayersDeniedByEntryRule(
        services: PlatformServices,
        rule: systems.diath.homeclaim.core.model.EntryDenyRule
    ) {
        runCatching {
            runOnServerThread {
                val region = services.regionService.getRegionById(rule.regionId) ?: return@runOnServerThread
                val area = services.worldAreaService?.getArea(region.areaId)
                val homeWorld = area?.let { services.worldAreaService?.getWorld(it.worldId) }
                val platformWorldId = homeWorld?.platformWorldId
                val world = when {
                    platformWorldId != null -> server.getWorld(platformWorldId)
                    homeWorld != null -> server.getWorld(homeWorld.name) ?: server.getWorld(region.world)
                    else -> server.getWorld(region.world)
                } ?: run {
                    logger.warning("Could not eject entry-denied players for rule ${rule.id.value}: plot world is not loaded")
                    return@runOnServerThread
                }
                val spawn = world.spawnLocation
                server.onlinePlayers.forEach { player ->
                    if (player.world.uid != world.uid) return@forEach
                    val loc = player.location
                    val inside = loc.blockX in region.bounds.minX..region.bounds.maxX &&
                        loc.blockY in region.bounds.minY..region.bounds.maxY &&
                        loc.blockZ in region.bounds.minZ..region.bounds.maxZ
                    if (!inside) return@forEach
                    if (Permissions.canBypassDeniedEntry(player)) return@forEach
                    if (!rule.matches(player.uniqueId, player.name)) return@forEach

                    player.sendMessage(
                        i18n.msg(
                            "policy.denied.detail",
                            i18n.msg("policy.reason.entry_deny"),
                            "entry_deny_id=${rule.id.value};reason=${rule.reason}"
                        )
                    )
                    val teleport = runCatching { player.teleportAsync(spawn) }
                        .getOrElse { java.util.concurrent.CompletableFuture.failedFuture(it) }
                    teleport.whenComplete { success, error ->
                        val completion = TeleportCompletion.resolve(success, error)
                        if (completion is TeleportCompletion.Failed) {
                            logger.warning(
                                "Entry-deny ejection failed for player ${player.uniqueId}, rule=${rule.id.value}: ${completion.detail}"
                            )
                            runCatching {
                                runOnServerThread {
                                    if (player.isOnline) player.sendMessage(i18n.msg("entrydeny.eject.teleport_failed"))
                                }
                            }.onFailure { dispatchError ->
                                logger.log(
                                    java.util.logging.Level.WARNING,
                                    "Could not report entry-deny ejection failure for player ${player.uniqueId}, rule=${rule.id.value}",
                                    dispatchError
                                )
                            }
                        }
                    }
                }
            }
        }.onFailure { error ->
            logger.log(
                java.util.logging.Level.SEVERE,
                "Could not process entry-deny ejection for rule ${rule.id.value}",
                error
            )
        }
    }
}

private fun HomeClaimPaperPlugin.resolveToken(token: String?, envKey: String?, filePath: String?): String? {
    if (!token.isNullOrBlank()) return token
    val envValue = envKey?.takeIf { it.isNotBlank() }?.let { System.getenv(it) }?.takeIf { !it.isNullOrBlank() }
    if (!envValue.isNullOrBlank()) return envValue
    val fileToken = filePath?.takeIf { it.isNotBlank() }?.let {
        runCatching {
            java.io.File(it).takeIf { f -> f.exists() && f.isFile }?.readLines()?.firstOrNull()?.trim()
        }.getOrNull()
    }?.takeIf { !it.isNullOrBlank() }
    return fileToken
}

private data class StorageConfig(
    val type: StorageType,
    val host: String,
    val port: Int,
    val database: String,
    val sqliteFile: String? = null,
    val username: String? = null,
    val password: String? = null,
    val driver: DriverType = DriverType.POSTGRES,
    val encoding: String = "UTF-8",
    val maximumPoolSize: Int = 10
)

private data class MigrationConfig(
    val enabled: Boolean = true,
    val allowNonPostgres: Boolean = true,
    val baselineOnMigrate: Boolean = true,
    val locations: List<String> = listOf("classpath:db/migration/common")
)

// Only JDBC storage supported (SQLite, PostgreSQL, MySQL, MariaDB)
// IN_MEMORY removed - not suitable for plot systems
private enum class StorageType {
    JDBC;
    
    companion object {
        fun default(): StorageType = JDBC
    }
}

private enum class DriverType(val className: String) {
    POSTGRES("org.postgresql.Driver"),
    MARIADB("org.mariadb.jdbc.Driver"),
    MYSQL("com.mysql.cj.jdbc.Driver"),
    SQLITE("org.sqlite.JDBC");

    companion object {
        fun from(raw: String?): DriverType {
            return when (raw?.lowercase()) {
                "postgres", "postgresql" -> POSTGRES
                "mariadb" -> MARIADB
                "mysql" -> MYSQL
                "sqlite" -> SQLITE
                else -> SQLITE  // Default to SQLite for easy local setup
            }
        }
    }
}

private fun driverClassName(driver: DriverType): String = driver.className

private fun HomeClaimPaperPlugin.loadProfilesFromConfig(
    inMemory: systems.diath.homeclaim.core.store.InMemoryFlagProfileService?,
    jdbcService: systems.diath.homeclaim.core.store.JdbcFlagProfileService?,
    clearExisting: Boolean = false
) {
    val list = homeClaimConfig.getConfigList("homeclaim.flagProfiles")
    if (list.isEmpty()) return
    if (clearExisting) {
        inMemory?.replaceAll(emptyList())
    }
    list.forEach { raw ->
        val name = (raw.get<Any>("name") as? String) ?: return@forEach
        val flagsRaw = configEntries(raw.get<Any>("flags") as? com.electronwill.nightconfig.core.UnmodifiableConfig)
        val limitsRaw = configEntries(raw.get<Any>("limits") as? com.electronwill.nightconfig.core.UnmodifiableConfig)
        val flags = flagsRaw.mapNotNull { (k, v) ->
            val key = k.toString()
            systems.diath.homeclaim.core.model.FlagKey(key) to toPolicyValue(v)
        }.toMap()
        val limits = limitsRaw.mapNotNull { (k, v) ->
            val key = k.toString()
            systems.diath.homeclaim.core.model.LimitKey(key) to toPolicyValue(v)
        }.toMap()
        val profile = systems.diath.homeclaim.core.policy.FlagProfile(name = name, flags = flags, limits = limits)
        inMemory?.register(profile)
        jdbcService?.upsert(profile)
    }
}

private fun configEntries(
    config: com.electronwill.nightconfig.core.UnmodifiableConfig?
): Map<String, Any?> {
    if (config == null) return emptyMap()
    return config.entrySet().associate { entry ->
        entry.key to entry.getRawValue<Any?>()
    }
}

private fun toPolicyValue(value: Any?): systems.diath.homeclaim.core.model.PolicyValue {
    return when (value) {
        is Boolean -> systems.diath.homeclaim.core.model.PolicyValue.Bool(value)
        is Number -> systems.diath.homeclaim.core.model.PolicyValue.IntValue(value.toInt())
        else -> systems.diath.homeclaim.core.model.PolicyValue.Text(value?.toString() ?: "")
    }
}
