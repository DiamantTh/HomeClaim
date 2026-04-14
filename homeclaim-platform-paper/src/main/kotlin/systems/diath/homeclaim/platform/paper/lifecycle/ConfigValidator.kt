package systems.diath.homeclaim.platform.paper.lifecycle

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.java.JavaPlugin
import systems.diath.homeclaim.platform.paper.I18n

/**
 * Configuration validation with clear error messages.
 * Validates all config values at startup to catch issues early.
 */
object ConfigValidator {
    
    @PublishedApi
    internal var plugin: JavaPlugin? = null
    private val i18n = I18n()
    
    /**
     * Initialize the validator.
     */
    fun init(plugin: JavaPlugin) {
        this.plugin = plugin
    }
    
    /**
     * Validate the complete plugin configuration.
     */
    fun validate(config: FileConfiguration): ConfigValidationResult {
        val errors = mutableListOf<ConfigError>()
        val warnings = mutableListOf<ConfigWarning>()
        val log = plugin?.logger
        
        // Storage configuration
        validateStorage(config, errors, warnings)
        
        // REST API configuration
        validateRestApi(config, errors, warnings)
        
        // Plot world configuration
        validatePlotWorld(config, errors, warnings)
        
        // Economy configuration
        validateEconomy(config, errors, warnings)
        
        // Security configuration
        validateSecurity(config, errors, warnings)
        
        // Log results
        if (errors.isNotEmpty()) {
            log?.severe(i18n.msg("config.errors.header.line1"))
            log?.severe(i18n.msg("config.errors.header.line2"))
            log?.severe(i18n.msg("config.errors.header.line3"))
            errors.forEach { e ->
                log?.severe(i18n.msg("config.errors.entry", e.path, e.message))
                e.suggestion?.let { log?.severe(i18n.msg("config.errors.suggestion", it)) }
            }
        }
        
        if (warnings.isNotEmpty()) {
            log?.warning(i18n.msg("config.warnings.header"))
            warnings.forEach { w ->
                log?.warning(i18n.msg("config.warnings.entry", w.path, w.message))
            }
        }
        
        return ConfigValidationResult(
            valid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }
    
    private fun validateStorage(config: FileConfiguration, errors: MutableList<ConfigError>, warnings: MutableList<ConfigWarning>) {
        val storageType = config.getString("storage.type", "in-memory")?.lowercase()
        
        if (storageType !in listOf("in-memory", "jdbc", "memory")) {
            errors.add(ConfigError(
                path = "storage.type",
                message = "Invalid storage type: '$storageType'",
                suggestion = "Use 'in-memory' or 'jdbc'"
            ))
        }
        
        if (storageType == "jdbc") {
            // JDBC URL required
            val jdbcUrl = config.getString("storage.jdbc.url")
            if (jdbcUrl.isNullOrBlank()) {
                errors.add(ConfigError(
                    path = "storage.jdbc.url",
                    message = "JDBC URL is required for JDBC storage",
                    suggestion = "Example: jdbc:postgresql://localhost:5432/homeclaim"
                ))
            } else {
                // Validate URL format
                if (!jdbcUrl.startsWith("jdbc:")) {
                    errors.add(ConfigError(
                        path = "storage.jdbc.url",
                        message = "Invalid JDBC URL format",
                        suggestion = "URL must start with 'jdbc:'"
                    ))
                }
                
                // Driver detection
                val driver = config.getString("storage.jdbc.driver")
                if (driver.isNullOrBlank()) {
                    // Auto-detect warnings
                    val detectedDriver = when {
                        jdbcUrl.contains("postgresql") -> "org.postgresql.Driver"
                        jdbcUrl.contains("mysql") || jdbcUrl.contains("mariadb") -> "com.mysql.cj.jdbc.Driver"
                        jdbcUrl.contains("sqlite") -> "org.sqlite.JDBC"
                        jdbcUrl.contains("h2") -> "org.h2.Driver"
                        else -> null
                    }
                    if (detectedDriver == null) {
                        warnings.add(ConfigWarning(
                            path = "storage.jdbc.driver",
                            message = "Could not auto-detect JDBC driver from URL"
                        ))
                    }
                }
                
                // Credentials for non-SQLite
                if (!jdbcUrl.contains("sqlite")) {
                    if (config.getString("storage.jdbc.username").isNullOrBlank()) {
                        warnings.add(ConfigWarning(
                            path = "storage.jdbc.username",
                            message = "No username configured for database"
                        ))
                    }
                    if (config.getString("storage.jdbc.password").isNullOrBlank()) {
                        warnings.add(ConfigWarning(
                            path = "storage.jdbc.password",
                            message = "No password configured for database"
                        ))
                    }
                }
            }
            
            // Pool size
            val poolSize = config.getInt("storage.jdbc.pool-size", 10)
            if (poolSize < 2) {
                warnings.add(ConfigWarning(
                    path = "storage.jdbc.pool-size",
                    message = "Pool size $poolSize is very low, recommended minimum: 5"
                ))
            }
            if (poolSize > 50) {
                warnings.add(ConfigWarning(
                    path = "storage.jdbc.pool-size",
                    message = "Pool size $poolSize is very high, may cause issues"
                ))
            }
        }
    }
    
    private fun validateRestApi(config: FileConfiguration, errors: MutableList<ConfigError>, warnings: MutableList<ConfigWarning>) {
        val enabled = config.getBoolean("rest.enabled", false)
        if (!enabled) return
        
        val port = config.getInt("rest.port", 8080)
        if (port < 1024 || port > 65535) {
            errors.add(ConfigError(
                path = "rest.port",
                message = "Invalid port: $port",
                suggestion = "Use a port between 1024 and 65535"
            ))
        }
        
        // Reserved ports warning
        if (port in listOf(25565, 25575, 8123, 8192)) {
            warnings.add(ConfigWarning(
                path = "rest.port",
                message = "Port $port may conflict with Minecraft/Dynmap/BlueMap"
            ))
        }
        
        // Auth token
        if (config.getBoolean("rest.auth.enabled", true)) {
            val token = config.getString("rest.auth.token")
            if (token.isNullOrBlank()) {
                warnings.add(ConfigWarning(
                    path = "rest.auth.token",
                    message = "No API token configured, using auto-generated token"
                ))
            } else if (token.length < 32) {
                warnings.add(ConfigWarning(
                    path = "rest.auth.token",
                    message = "API token is short, recommended: 32+ characters"
                ))
            }
        } else {
            warnings.add(ConfigWarning(
                path = "rest.auth.enabled",
                message = "REST API authentication is disabled - not recommended for production"
            ))
        }
    }
    
    private fun validatePlotWorld(config: FileConfiguration, errors: MutableList<ConfigError>, warnings: MutableList<ConfigWarning>) {
        val plotWorlds = config.getConfigurationSection("plot-worlds") ?: return
        
        for (worldKey in plotWorlds.getKeys(false)) {
            val world = plotWorlds.getConfigurationSection(worldKey) ?: continue
            
            // Plot size
            val plotSize = world.getInt("plot-size", 42)
            if (plotSize < 16) {
                errors.add(ConfigError(
                    path = "plot-worlds.$worldKey.plot-size",
                    message = "Plot size $plotSize is too small (minimum: 16)",
                    suggestion = "Typical values: 42, 64, 100"
                ))
            }
            if (plotSize > 512) {
                warnings.add(ConfigWarning(
                    path = "plot-worlds.$worldKey.plot-size",
                    message = "Plot size $plotSize is very large, may cause performance issues"
                ))
            }
            
            // Road width
            val roadWidth = world.getInt("road-width", 7)
            if (roadWidth < 1) {
                errors.add(ConfigError(
                    path = "plot-worlds.$worldKey.road-width",
                    message = "Road width must be at least 1"
                ))
            }
            if (roadWidth > plotSize) {
                warnings.add(ConfigWarning(
                    path = "plot-worlds.$worldKey.road-width",
                    message = "Road width $roadWidth is larger than plot size $plotSize"
                ))
            }

            // Plot height
            val plotHeight = world.getInt("plot-height", 64)
            if (plotHeight < 1 || plotHeight > 319) {
                errors.add(ConfigError(
                    path = "plot-worlds.$worldKey.plot-height",
                    message = "Plot height $plotHeight is out of range (1-319)"
                ))
            }

            // Plot count per side
            val plotsPerSide = when {
                world.contains("plots-per-side") -> world.getInt("plots-per-side", 128)
                else -> world.getInt("plotsPerSide", 128)
            }
            if (plotsPerSide < 0) {
                errors.add(ConfigError(
                    path = "plot-worlds.$worldKey.plots-per-side",
                    message = "plots-per-side cannot be negative"
                ))
            }
            if (plotsPerSide == 1) {
                warnings.add(ConfigWarning(
                    path = "plot-worlds.$worldKey.plots-per-side",
                    message = "plots-per-side = 1 is not recommended; use 0 or at least 2"
                ))
            }
            if (plotsPerSide > 512) {
                warnings.add(ConfigWarning(
                    path = "plot-worlds.$worldKey.plots-per-side",
                    message = "plots-per-side $plotsPerSide is very large and may create an impractical world"
                ))
            }
        }
    }
    
    private fun validateEconomy(config: FileConfiguration, errors: MutableList<ConfigError>, warnings: MutableList<ConfigWarning>) {
        val claimCost = config.getDouble("economy.claim-cost", 0.0)
        if (claimCost < 0) {
            errors.add(ConfigError(
                path = "economy.claim-cost",
                message = "Claim cost cannot be negative"
            ))
        }
        
        val maxPlots = config.getInt("economy.max-plots-per-player", -1)
        if (maxPlots == 0) {
            warnings.add(ConfigWarning(
                path = "economy.max-plots-per-player",
                message = "max-plots-per-player is 0, players cannot claim plots"
            ))
        }
    }
    
    private fun validateSecurity(config: FileConfiguration, errors: MutableList<ConfigError>, warnings: MutableList<ConfigWarning>) {
        // Rate limiting
        val rateLimit = config.getInt("security.rate-limit.commands-per-second", 2)
        if (rateLimit < 1) {
            warnings.add(ConfigWarning(
                path = "security.rate-limit.commands-per-second",
                message = "Rate limit is disabled (< 1), may allow spam"
            ))
        }
        
        // Max region name length
        val maxNameLength = config.getInt("security.max-region-name-length", 32)
        if (maxNameLength < 3) {
            errors.add(ConfigError(
                path = "security.max-region-name-length",
                message = "Max name length $maxNameLength is too short"
            ))
        }
        if (maxNameLength > 128) {
            warnings.add(ConfigWarning(
                path = "security.max-region-name-length",
                message = "Max name length $maxNameLength is very long"
            ))
        }
    }
    
    /**
     * Get a config value with validation and default.
     */
    inline fun <reified T> getValidated(
        config: FileConfiguration,
        path: String,
        default: T,
        validator: (T) -> Boolean = { true },
        errorMessage: String = "Invalid value"
    ): T {
        val value = when (T::class) {
            String::class -> config.getString(path, default as? String) as T
            Int::class -> config.getInt(path, default as? Int ?: 0) as T
            Double::class -> config.getDouble(path, default as? Double ?: 0.0) as T
            Boolean::class -> config.getBoolean(path, default as? Boolean ?: false) as T
            Long::class -> config.getLong(path, default as? Long ?: 0L) as T
            else -> default
        }
        
        return if (validator(value)) value else {
            plugin?.logger?.warning("Config [$path]: $errorMessage. Using default: $default")
            default
        }
    }
    
    // ============================================
    // Data Classes
    // ============================================
    
    data class ConfigError(
        val path: String,
        val message: String,
        val suggestion: String? = null
    )
    
    data class ConfigWarning(
        val path: String,
        val message: String
    )
    
    data class ConfigValidationResult(
        val valid: Boolean,
        val errors: List<ConfigError>,
        val warnings: List<ConfigWarning>
    )
}
