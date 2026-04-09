package systems.diath.homeclaim.platform.paper.lifecycle

import org.bukkit.plugin.java.JavaPlugin
import systems.diath.homeclaim.platform.paper.I18n
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/**
 * Startup validation and health check system.
 * Validates configuration and dependencies on startup.
 * Provides ongoing health monitoring.
 */
@Suppress("DEPRECATION")
object HealthCheckService {
    
    private var plugin: JavaPlugin? = null
    private var dataSource: DataSource? = null
    private val i18n = I18n()
    
    private val healthStatus = AtomicReference(HealthStatus.UNKNOWN)
    private val componentHealth = ConcurrentHashMap<String, ComponentHealth>()
    private var lastCheckTime = 0L
    
    // Health check cache duration
    private const val CACHE_DURATION_MS = 5000L
    
    /**
     * Initialize the health check service.
     */
    fun init(plugin: JavaPlugin, dataSource: DataSource? = null) {
        this.plugin = plugin
        this.dataSource = dataSource
        healthStatus.set(HealthStatus.STARTING)
        componentHealth.clear()
    }
    
    /**
     * Set the datasource for health checks.
     */
    fun setDataSource(dataSource: DataSource?) {
        this.dataSource = dataSource
    }
    
    /**
     * Run all startup validations.
     * Returns list of validation failures.
     */
    fun runStartupValidation(): StartupValidationResult {
        val failures = mutableListOf<ValidationFailure>()
        val warnings = mutableListOf<String>()
        val log = plugin?.logger
        
        log?.info(i18n.msg("health.header.line1"))
        log?.info(i18n.msg("health.header.line2"))
        log?.info(i18n.msg("health.header.line3"))
        
        // 1. Java Version Check
        val javaVersion = System.getProperty("java.version")
        val majorVersion = javaVersion.split(".")[0].toIntOrNull() ?: 0
        if (majorVersion < 17) {
            failures.add(ValidationFailure(
                component = "Java Runtime",
                message = "Java 17+ required, found: $javaVersion",
                severity = Severity.CRITICAL
            ))
        } else {
            log?.info(i18n.msg("health.java.ok", javaVersion))
        }
        
        // 2. Memory Check
        val maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        if (maxMemory < 512) {
            warnings.add("Low memory: ${maxMemory}MB allocated. Recommended: 1024MB+")
            log?.warning(i18n.msg("health.memory.low", maxMemory.toString()))
        } else {
            log?.info(i18n.msg("health.memory.ok", maxMemory.toString()))
        }
        
        // 3. Database Connection Check
        val ds = dataSource
        if (ds != null) {
            try {
                ds.connection.use { conn ->
                    if (!conn.isValid(5)) {
                        failures.add(ValidationFailure(
                            component = "Database",
                            message = "Database connection not valid",
                            severity = Severity.CRITICAL
                        ))
                    } else {
                        val meta = conn.metaData
                        log?.info(i18n.msg("health.db.ok", meta.databaseProductName, meta.databaseProductVersion))
                        
                        // Check required tables
                        val missingTables = checkRequiredTables(conn)
                        if (missingTables.isNotEmpty()) {
                            failures.add(ValidationFailure(
                                component = "Database Schema",
                                message = "Missing tables: ${missingTables.joinToString()}",
                                severity = Severity.CRITICAL
                            ))
                        }
                    }
                    Unit // Explicit return for use block
                }
            } catch (e: Exception) {
                failures.add(ValidationFailure(
                    component = "Database",
                    message = "Connection failed: ${e.message}",
                    severity = Severity.CRITICAL
                ))
            }
        } else {
            log?.info(i18n.msg("health.db.inmemory"))
        }
        
        // 4. Plugin Dependencies Check
        val server = plugin?.server
        val fawe = server?.pluginManager?.getPlugin("FastAsyncWorldEdit")
        if (fawe == null || !fawe.isEnabled) {
            failures.add(ValidationFailure(
                component = "FAWE",
                message = "FastAsyncWorldEdit not found or disabled",
                severity = Severity.CRITICAL
            ))
        } else {
            log?.info(i18n.msg("health.fawe.ok", fawe.description.version))
        }
        
        // Optional: Vault
        val vault = server?.pluginManager?.getPlugin("Vault")
        if (vault != null && vault.isEnabled) {
            log?.info(i18n.msg("health.vault.ok", vault.description.version))
        } else {
            log?.info(i18n.msg("health.vault.none"))
        }
        
        // 5. Disk Space Check
        val dataFolder = plugin?.dataFolder
        if (dataFolder != null) {
            val freeSpace = dataFolder.usableSpace / (1024 * 1024)
            if (freeSpace < 100) {
                warnings.add("Low disk space: ${freeSpace}MB free")
                log?.warning(i18n.msg("health.disk.low", freeSpace.toString()))
            } else {
                log?.info(i18n.msg("health.disk.ok", freeSpace.toString()))
            }
        }
        
        // Set overall status
        val overallStatus = when {
            failures.any { it.severity == Severity.CRITICAL } -> HealthStatus.UNHEALTHY
            failures.isNotEmpty() || warnings.isNotEmpty() -> HealthStatus.DEGRADED
            else -> HealthStatus.HEALTHY
        }
        healthStatus.set(overallStatus)
        
        // Summary
        if (failures.isEmpty()) {
            log?.info(i18n.msg("health.summary.ok.line1"))
            log?.info(i18n.msg("health.summary.ok.line2"))
            log?.info(i18n.msg("health.summary.ok.line1"))
        } else {
            log?.severe(i18n.msg("health.summary.failed.line1"))
            log?.severe(i18n.msg("health.summary.failed.line2", failures.size.toString()))
            failures.forEach { f ->
                log?.severe(i18n.msg("health.summary.failed.entry", f.component, f.message))
            }
            log?.severe(i18n.msg("health.summary.failed.line1"))
        }
        
        if (warnings.isNotEmpty()) {
            log?.warning(i18n.msg("health.summary.warnings", warnings.size.toString()))
            warnings.forEach { w ->
                log?.warning(i18n.msg("health.summary.warning.entry", w))
            }
        }
        
        return StartupValidationResult(
            success = failures.none { it.severity == Severity.CRITICAL },
            failures = failures,
            warnings = warnings
        )
    }
    
    /**
     * Check for required database tables.
     */
    private fun checkRequiredTables(conn: Connection): List<String> {
        val required = listOf("regions", "components", "zones", "region_roles", "region_flags", "region_limits")
        val missing = mutableListOf<String>()
        
        try {
            val meta = conn.metaData
            for (table in required) {
                val rs = meta.getTables(null, null, table, arrayOf("TABLE"))
                if (!rs.next()) {
                    // Also check uppercase for some DBs
                    val rsUpper = meta.getTables(null, null, table.uppercase(), arrayOf("TABLE"))
                    if (!rsUpper.next()) {
                        missing.add(table)
                    }
                }
            }
        } catch (e: Exception) {
            plugin?.logger?.warning("Could not verify tables: ${e.message}")
        }
        
        return missing
    }
    
    /**
     * Perform a health check (cached).
     */
    fun check(forceRefresh: Boolean = false): HealthReport {
        val now = System.currentTimeMillis()
        if (!forceRefresh && now - lastCheckTime < CACHE_DURATION_MS) {
            return HealthReport(
                status = healthStatus.get(),
                components = componentHealth.toMap(),
                timestamp = lastCheckTime,
                cached = true
            )
        }
        
        // Database check
        val ds = dataSource
        if (ds != null) {
            try {
                ds.connection.use { conn ->
                    val valid = conn.isValid(3)
                    componentHealth["database"] = ComponentHealth(
                        name = "Database",
                        status = if (valid) HealthStatus.HEALTHY else HealthStatus.UNHEALTHY,
                        message = if (valid) "Connected" else "Connection invalid",
                        responseTimeMs = measureTimeMillis { conn.createStatement().execute("SELECT 1") }
                    )
                }
            } catch (e: Exception) {
                componentHealth["database"] = ComponentHealth(
                    name = "Database",
                    status = HealthStatus.UNHEALTHY,
                    message = "Error: ${e.message}"
                )
            }
        }
        
        // Memory check
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMb = runtime.maxMemory() / (1024 * 1024)
        val usagePercent = if (maxMb > 0) (usedMb * 100) / maxMb else 0
        componentHealth["memory"] = ComponentHealth(
            name = "Memory",
            status = when {
                usagePercent > 90 -> HealthStatus.UNHEALTHY
                usagePercent > 75 -> HealthStatus.DEGRADED
                else -> HealthStatus.HEALTHY
            },
            message = "${usedMb}MB / ${maxMb}MB (${usagePercent}%)"
        )
        
        // Update overall status
        val componentStatuses = componentHealth.values.map { it.status }
        healthStatus.set(when {
            componentStatuses.any { it == HealthStatus.UNHEALTHY } -> HealthStatus.UNHEALTHY
            componentStatuses.any { it == HealthStatus.DEGRADED } -> HealthStatus.DEGRADED
            else -> HealthStatus.HEALTHY
        })
        
        lastCheckTime = now
        
        return HealthReport(
            status = healthStatus.get(),
            components = componentHealth.toMap(),
            timestamp = now,
            cached = false
        )
    }
    
    /**
     * Register a custom health check component.
     */
    fun registerComponent(name: String, checker: () -> ComponentHealth) {
        try {
            componentHealth[name.lowercase()] = checker()
        } catch (e: Exception) {
            componentHealth[name.lowercase()] = ComponentHealth(
                name = name,
                status = HealthStatus.UNHEALTHY,
                message = "Check failed: ${e.message}"
            )
        }
    }
    
    /**
     * Get current health status.
     */
    fun getStatus(): HealthStatus = healthStatus.get()
    
    /**
     * Is the system healthy?
     */
    fun isHealthy(): Boolean = healthStatus.get() == HealthStatus.HEALTHY
    
    // Helper for timing
    private inline fun measureTimeMillis(block: () -> Unit): Long {
        val start = System.currentTimeMillis()
        block()
        return System.currentTimeMillis() - start
    }
    
    // ============================================
    // Data Classes
    // ============================================
    
    enum class HealthStatus {
        UNKNOWN,
        STARTING,
        HEALTHY,
        DEGRADED,
        UNHEALTHY
    }
    
    enum class Severity {
        WARNING,
        ERROR,
        CRITICAL
    }
    
    data class ValidationFailure(
        val component: String,
        val message: String,
        val severity: Severity
    )
    
    data class StartupValidationResult(
        val success: Boolean,
        val failures: List<ValidationFailure>,
        val warnings: List<String>
    )
    
    data class ComponentHealth(
        val name: String,
        val status: HealthStatus,
        val message: String = "",
        val responseTimeMs: Long = -1
    )
    
    data class HealthReport(
        val status: HealthStatus,
        val components: Map<String, ComponentHealth>,
        val timestamp: Long,
        val cached: Boolean = false
    )
}
