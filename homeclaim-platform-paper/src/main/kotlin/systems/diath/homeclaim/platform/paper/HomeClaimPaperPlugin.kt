package systems.diath.homeclaim.platform.paper

import systems.diath.homeclaim.core.model.RegionRole
import systems.diath.homeclaim.core.policy.SimplePolicyService
import systems.diath.homeclaim.core.service.PolicyService
import systems.diath.homeclaim.core.service.RegionAdminServiceImpl
import systems.diath.homeclaim.core.store.JdbcAuditService
import systems.diath.homeclaim.core.store.JdbcComponentRepository
import systems.diath.homeclaim.core.store.JdbcFlagLimitRepository
import systems.diath.homeclaim.core.store.JdbcFlagProfileService
import systems.diath.homeclaim.core.store.JdbcRegionRepository
import systems.diath.homeclaim.core.store.JdbcRoleRepository
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
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.event.HandlerList
import javax.sql.DataSource

class HomeClaimPaperPlugin : JavaPlugin() {
    private val plotSetupWizard by lazy {
        systems.diath.homeclaim.platform.paper.plot.PlotWorldSetupWizard(
            configStore = systems.diath.homeclaim.platform.paper.plot.PlotWorldConfigStore(this),
            i18nProvider = { i18n },
            plugin = this,
            regionServiceProvider = { services?.regionService }
        )
    }
    private var bridge: PaperPlatformBridge? = null
    private var eventDispatcher: systems.diath.homeclaim.core.event.EventDispatcher? = null
    private var econService: systems.diath.homeclaim.core.economy.SimpleEconService? = null
    private var restServer: io.ktor.server.engine.ApplicationEngine? = null
    private var services: PlatformServices? = null
    private var guiManager: systems.diath.homeclaim.platform.paper.gui.GuiManager? = null
    private var plotMutationHooksRegistered: Boolean = false
    private var plotMutationService: systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationService =
        systems.diath.homeclaim.platform.paper.plot.mutation.NoOpPlotMutationService
    private var plotResetService: systems.diath.homeclaim.platform.paper.plot.mutation.PlotResetService =
        systems.diath.homeclaim.platform.paper.plot.mutation.NoOpPlotResetService
    private var plotMutationBackend: systems.diath.homeclaim.core.mutation.WorldMutationBackend? = null
    private var storageType: StorageType = StorageType.JDBC  // SQLite by default
    private var currentStorageConfig: StorageConfig? = null
    private var migrationConfig: MigrationConfig = MigrationConfig()
    private var i18n: I18n = I18n()
    private var clientLinkListener: PaperClientLinkChannelListener? = null
    private var clientLinkChannels: Set<String> = emptySet()
    internal val homeClaimConfig = HomeClaimTomlConfig(java.io.File("config/HomeClaim.toml"), this)

    fun bindServices(services: PlatformServices) {
        bridge = PaperPlatformBridge(this, services).also { it.registerListeners() }
        logger.info(i18n.msg("bridge.registered"))
        registerPlotMutationHooks(services)
        
        // Initialize Economy system with auto-detection
        initializeEconomy()
        
        // Register GUI system
        guiManager = systems.diath.homeclaim.platform.paper.gui.GuiManager(services.regionService, services.zoneService, services.componentService).also {
            server.pluginManager.registerEvents(it, this)
        }
        
        // Register pad creator listener
        server.pluginManager.registerEvents(systems.diath.homeclaim.platform.paper.gui.PadCreatorListener(), this)
        
        // Register /plot command (P2-Style)
        getCommand("plot")?.let { cmd ->
            val ratingRepository = services.dataSource?.let { ds ->
                systems.diath.homeclaim.platform.paper.store.JdbcRatingRepository(ds)
            }
            val executor = systems.diath.homeclaim.platform.paper.command.PlotCommand(
                services.regionService,
                guiManager!!,
                econService,
                plotResetService,
                plotMutationService,
                ratingRepository,
                i18n,
                plotMutationBackend
            )
            cmd.setExecutor(executor)
            cmd.tabCompleter = executor
        }
        
        // Register /zone command
        getCommand("zone")?.let { cmd ->
            val executor = systems.diath.homeclaim.platform.paper.command.ZoneCommand(
                services.zoneService,
                services.regionService,
                i18n
            )
            cmd.setExecutor(executor)
            cmd.tabCompleter = executor
        }
        
        maybeStartRest(services)
        setupClientLinkChannels()
        this.services = services
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
            logTraffic = homeClaimConfig.getBoolean("homeclaim.clientlink.logTraffic", false)
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
     * HomeClaim uses FAWE for fast block operations during plot world setup.
     * 
     * @return true if FAWE is available, false otherwise (runs with limited features)
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
     * Called by Bukkit/Folia when a world needs this plugin's generator.
     * This is how Folia loads custom worlds on server start (via bukkit.yml).
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
            registerShutdownHooks(services)
            
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
        val backend: systems.diath.homeclaim.core.mutation.WorldMutationBackend = if (isFoliaRuntime()) {
            systems.diath.homeclaim.platform.paper.plot.mutation.FoliaRegionScheduledWorldMutationBackend(
                this, maxConcurrentMutationsForWorld = mutationLimitForWorld
            )
        } else {
            systems.diath.homeclaim.platform.paper.plot.mutation.PaperSynchronousWorldMutationBackend(
                maxConcurrentMutationsForWorld = mutationLimitForWorld
            )
        }
        plotMutationBackend = backend
        plotMutationService = if (isFoliaRuntime()) {
            systems.diath.homeclaim.platform.paper.plot.mutation.FoliaPlotMutationService(this, mutationBackend = backend)
        } else {
            systems.diath.homeclaim.platform.paper.plot.mutation.PaperPlotMutationService(this, mutationBackend = backend)
        }
        plotResetService = if (isFoliaRuntime()) {
            systems.diath.homeclaim.platform.paper.plot.mutation.FoliaPlotResetService(this, mutationBackend = backend)
        } else {
            systems.diath.homeclaim.platform.paper.plot.mutation.PaperPlotResetService(this, mutationBackend = backend)
        }

        dispatcher.registerListener(
            systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationEventListener(
                services.regionService,
                plotMutationService,
                plotResetService
            )
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
        logger.info("Plot mutation hooks registered (${if (isFoliaRuntime()) "Folia" else "Paper"} mode)")
    }

    private fun isFoliaRuntime(): Boolean {
        return try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    private fun savePlotMutationJobs() {
        try {
            val jobsFile = java.io.File(dataFolder, "plot-mutation-jobs.json")
            // Try to export job snapshots if backend is available
            val mutationBackend = plotMutationBackend as? systems.diath.homeclaim.platform.paper.plot.mutation.PaperSynchronousWorldMutationBackend
                ?: plotMutationBackend as? systems.diath.homeclaim.platform.paper.plot.mutation.FoliaRegionScheduledWorldMutationBackend
            
            if (mutationBackend != null) {
                val registry = (mutationBackend as? Any)?.let {
                    try {
                        it::class.java.getMethod("getJobRegistry").invoke(it)
                            as systems.diath.homeclaim.platform.paper.plot.mutation.PlotJobRegistry
                    } catch (e: Exception) {
                        null
                    }
                }
                
                if (registry != null) {
                    val json = registry.toPersistedFormat()
                    jobsFile.parentFile?.mkdirs()
                    jobsFile.writeText(json)
                    val snapshots = registry.snapshot()
                    if (snapshots.isNotEmpty()) {
                        logger.info("Saved ${snapshots.size} active plot mutation jobs for recovery on reload")
                    }
                }
            }
        } catch (e: Exception) {
            logger.fine("Could not save plot mutation jobs: ${e.message}")
        }
    }

    private fun registerShutdownHooks(services: PlatformServices) {
        // Player cleanup first
        ShutdownManager.registerHook("PlayerCleanup", priority = 10) {
            PlayerCleanupManager.cleanupAll(server.onlinePlayers)
        }
        
        // GUI cleanup
        ShutdownManager.registerHook("GuiManager", priority = 20) {
            guiManager?.closeAll()
        }

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
        
        // Database connection pool (last)
        ShutdownManager.registerDataSource(services.dataSource)
    }

    override fun onDisable() {
        // Save active plot mutation jobs before shutdown
        savePlotMutationJobs()
        
        // Execute graceful shutdown with all registered hooks
        val result = ShutdownManager.shutdown()
        if (!result.success) {
            logger.warning(i18n.msg("shutdown.warnings", result.message))
        }

        teardownClientLinkChannels()
        
        // Clear references
        bridge = null
        restServer = null
        services = null
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!command.name.equals("homeclaim", ignoreCase = true) && !command.name.equals("hc", ignoreCase = true)) {
            return false
        }
        val sub = args.getOrNull(0)?.lowercase()
        when (sub) {
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
                    HandlerList.unregisterAll(this)
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
                    val svc = services
                    if (svc != null) {
                        val jdbc = svc.flagProfileService as? JdbcFlagProfileService
                        loadProfilesFromConfig(null, jdbc, clearExisting = true)
                        maybeStartRest(svc)
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
                val auditCmd = systems.diath.homeclaim.platform.paper.command.AuditCommand(
                    services?.auditService,
                    services!!.regionService,
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

    private fun createJdbcServices(config: StorageConfig): PlatformServices {
        val dataSource = buildDataSource(config)
        if (config.driver != DriverType.SQLITE && (config.username.isNullOrBlank() || config.password.isNullOrBlank())) {
            logger.warning(i18n.msg("jdbc.warn.no.creds"))
        }
        
        // Initialize database resilience
        DatabaseResilience.init(this, dataSource)
        
        maybeMigrate(dataSource, config.driver)
        val auditService = JdbcAuditService(dataSource)
        val regionRepo = JdbcRegionRepository(dataSource, eventDispatcher, auditService)
        val componentRepo = JdbcComponentRepository(dataSource)
        val zoneRepo = JdbcZoneRepository(dataSource)
        val profileService = JdbcFlagProfileService(dataSource)
        val flagLimitRepo = JdbcFlagLimitRepository(dataSource)
        val policy: PolicyService = SimplePolicyService(
            regionService = regionRepo,
            zoneService = zoneRepo,
            roleResolver = { regionId, playerId ->
                val region = regionRepo.getRegionById(regionId) ?: return@SimplePolicyService RegionRole.VISITOR
                region.roles.resolve(playerId, region.owner)
            },
            flagProfileService = profileService
        )
        loadProfilesFromConfig(null, profileService)
        val admin = RegionAdminServiceImpl(
            regionService = regionRepo,
            profileService = profileService,
            flagLimitRepo = flagLimitRepo,
            profileJdbc = profileService,
            auditService = auditService
        )
        return PlatformServices(
            policyService = policy,
            regionService = regionRepo,
            plotMemberService = null, // TODO: Implement PlotMemberService
            componentService = componentRepo,
            zoneService = zoneRepo,
            adminService = admin,
            flagProfileService = profileService,
            auditService = auditService,
            dataSource = dataSource,
            eventDispatcher = eventDispatcher
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
            maximumPoolSize = config.maximumPoolSize
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
            allowNonPostgres = homeClaimConfig.getBoolean("homeclaim.migrations.allowNonPostgres", false),
            baselineOnMigrate = homeClaimConfig.getBoolean("homeclaim.migrations.baselineOnMigrate", true),
            locations = homeClaimConfig.getStringList("homeclaim.migrations.locations", listOf("classpath:db/migration"))
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
            addAll(migrationConfig.locations)
            when (driver) {
                DriverType.POSTGRES -> add("classpath:db/migration/postgres")
                DriverType.MARIADB -> add("classpath:db/migration/mariadb")
                DriverType.MYSQL -> { /* no extra */ }
                DriverType.SQLITE -> { /* no extra */ }
            }
        }
        try {
            Flyway.configure()
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
        val token = homeClaimConfig.getString("homeclaim.rest.token", "").takeIf { it.isNotBlank() }
        val rateLimit = homeClaimConfig.getInt("homeclaim.rest.rateLimitPerMinute", 0).takeIf { it > 0 }
        val tokenEnvKey = homeClaimConfig.getString("homeclaim.rest.tokenEnv", "").ifBlank { null }
        val tokenFilePath = homeClaimConfig.getString("homeclaim.rest.tokenFile", "").ifBlank { null }
        val allowLocalhost = homeClaimConfig.getBoolean("homeclaim.rest.allowLocalhost", false)
        val allowedHosts = homeClaimConfig.getStringList("homeclaim.rest.allowedHosts")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
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
        restServer = PlotRestServer(
            regionService = services.regionService,
            plotMemberService = services.plotMemberService,
            zoneService = services.zoneService,
            componentService = services.componentService,
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
            enableCors = homeClaimConfig.getBoolean("homeclaim.rest.cors.enabled", false)
        ).start()
        logger.info(
            i18n.msg(
                "rest.started",
                port.toString(),
                " (token)",
                if (rateLimit != null) " (rate: $rateLimit/min)" else ""
            )
        )
    }

    private fun loadI18n(): I18n {
        val tag = homeClaimConfig.getString("homeclaim.locale", "en")
        return I18n(localeTag = tag, loader = this::class.java.classLoader)
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
    val allowNonPostgres: Boolean = false,
    val baselineOnMigrate: Boolean = true,
    val locations: List<String> = listOf("classpath:db/migration")
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
                else -> SQLITE  // Default to SQLite for easy setup and Folia testing
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
