package systems.diath.homeclaim.platform.paper.lifecycle

import com.zaxxer.hikari.HikariDataSource
import org.bukkit.plugin.java.JavaPlugin
import systems.diath.homeclaim.platform.paper.I18n
import java.sql.SQLException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import javax.sql.DataSource

/**
 * Database connection resilience with automatic reconnection and circuit breaker.
 * Handles transient failures and provides connection health monitoring.
 */
object DatabaseResilience {
    
    private var plugin: JavaPlugin? = null
    private val i18n = I18n()
    private var dataSource: HikariDataSource? = null
    
    // Circuit breaker state
    private val circuitOpen = AtomicBoolean(false)
    private val failureCount = AtomicInteger(0)
    private var lastFailureTime = 0L
    private var circuitOpenedAt = 0L
    
    // Configuration
    var failureThreshold = 5           // Failures before opening circuit
    var resetTimeoutMs = 30_000L       // Time before trying to close circuit
    var retryDelayMs = 1000L           // Delay between retries
    var maxRetries = 3                 // Max retries per operation
    var connectionTestQuery = "SELECT 1"
    
    // Background health check
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "HomeClaim-DB-Health").apply { isDaemon = true }
    }
    private var healthCheckTask: ScheduledFuture<*>? = null
    
    // Listeners
    private val stateListeners = mutableListOf<(DatabaseState) -> Unit>()
    
    /**
     * Initialize the resilience manager.
     */
    fun init(plugin: JavaPlugin, dataSource: DataSource?) {
        this.plugin = plugin
        
        if (dataSource is HikariDataSource) {
            this.dataSource = dataSource
            
            // Configure HikariCP for resilience
            configureHikari(dataSource)
            
            // Start periodic health check
            startHealthCheck()
            
            // Register for graceful shutdown
            ShutdownManager.registerHook("DatabaseResilience", priority = 50) {
                shutdown()
            }
            
            plugin.logger.info(i18n.msg("db.resilience.enabled"))
        }
    }
    
    /**
     * Configure HikariCP with resilience settings.
     */
    private fun configureHikari(ds: HikariDataSource) {
        // These settings help with transient failures
        ds.connectionTimeout = 10_000        // 10s to get connection
        ds.validationTimeout = 5_000         // 5s to validate connection
        ds.idleTimeout = 300_000             // 5min idle before removal
        ds.maxLifetime = 1_800_000           // 30min max connection lifetime
        ds.leakDetectionThreshold = 60_000   // Warn if connection held >60s
        ds.connectionTestQuery = connectionTestQuery
        
        // Pool sizing
        if (ds.maximumPoolSize < 5) {
            ds.maximumPoolSize = 10
        }
        ds.minimumIdle = 2
    }
    
    /**
     * Execute a database operation with resilience (retries + circuit breaker).
     */
    fun <T> withResilience(operationName: String, operation: (java.sql.Connection) -> T): T {
        // Check circuit breaker
        if (circuitOpen.get()) {
            val elapsed = System.currentTimeMillis() - circuitOpenedAt
            if (elapsed < resetTimeoutMs) {
                throw CircuitOpenException("Database circuit breaker is open. Retry after ${(resetTimeoutMs - elapsed) / 1000}s")
            }
            // Try to half-open the circuit
            plugin?.logger?.info("Database circuit breaker: attempting to close...")
        }
        
        var lastException: Exception? = null
        
        for (attempt in 1..maxRetries) {
            try {
                val ds = dataSource ?: throw SQLException("DataSource not initialized")
                
                return ds.connection.use { conn ->
                    val result = operation(conn)
                    
                    // Success - reset failure count
                    onSuccess()
                    result
                }
            } catch (e: SQLException) {
                lastException = e
                onFailure(e)
                
                if (attempt < maxRetries && isRetryable(e)) {
                    plugin?.logger?.warning("Database operation '$operationName' failed (attempt $attempt/$maxRetries): ${e.message}")
                    Thread.sleep(retryDelayMs * attempt) // Exponential backoff
                } else {
                    break
                }
            }
        }
        
        throw DatabaseOperationException(
            "Database operation '$operationName' failed after $maxRetries attempts",
            lastException
        )
    }
    
    /**
     * Check if an exception is retryable.
     */
    private fun isRetryable(e: SQLException): Boolean {
        // Retryable SQL states (transient errors)
        val retryableStates = setOf(
            "08001", // Unable to connect
            "08003", // Connection does not exist
            "08004", // Connection rejected
            "08006", // Connection failure
            "08007", // Transaction resolution unknown
            "40001", // Deadlock
            "40P01", // Postgres deadlock
            "HY000"  // General error (might be transient)
        )
        
        // Also check for connection-related messages
        val retryableMessages = listOf(
            "connection", "timeout", "deadlock", "too many connections",
            "server has gone away", "lost connection", "broken pipe"
        )
        
        val state = e.sqlState ?: ""
        val message = e.message?.lowercase() ?: ""
        
        return retryableStates.contains(state) ||
               retryableMessages.any { message.contains(it) }
    }
    
    /**
     * Handle successful operation.
     */
    private fun onSuccess() {
        @Suppress("UNUSED_VARIABLE")
        val wasOpen = circuitOpen.get()
        failureCount.set(0)
        if (circuitOpen.compareAndSet(true, false)) {
            plugin?.logger?.info(i18n.msg("db.circuit.closed"))
            notifyListeners(DatabaseState.CONNECTED)
        }
    }
    
    /**
     * Handle failed operation.
     */
    private fun onFailure(e: Exception) {
        lastFailureTime = System.currentTimeMillis()
        val failures = failureCount.incrementAndGet()
        
        if (failures >= failureThreshold && circuitOpen.compareAndSet(false, true)) {
            circuitOpenedAt = System.currentTimeMillis()
            plugin?.logger?.severe(i18n.msg("db.circuit.open", failures.toString()))
            plugin?.logger?.severe(i18n.msg("db.circuit.error", e.message ?: "unknown"))
            notifyListeners(DatabaseState.DISCONNECTED)
        }
    }
    
    /**
     * Start periodic health check.
     */
    private fun startHealthCheck() {
        healthCheckTask = scheduler.scheduleAtFixedRate({
            try {
                checkHealth()
            } catch (e: Exception) {
                plugin?.logger?.log(Level.WARNING, "Health check failed", e)
            }
        }, 30, 30, TimeUnit.SECONDS)
    }
    
    /**
     * Perform health check.
     */
    private fun checkHealth() {
        val ds = dataSource ?: return
        
        try {
            ds.connection.use { conn ->
                if (conn.isValid(5)) {
                    onSuccess()
                    
                    // Update health check service
                    HealthCheckService.registerComponent("database") {
                        HealthCheckService.ComponentHealth(
                            name = "Database",
                            status = HealthCheckService.HealthStatus.HEALTHY,
                            message = "Pool: ${ds.hikariPoolMXBean?.activeConnections ?: 0} active, ${ds.hikariPoolMXBean?.idleConnections ?: 0} idle"
                        )
                    }
                }
            }
        } catch (e: SQLException) {
            onFailure(e)
            
            HealthCheckService.registerComponent("database") {
                HealthCheckService.ComponentHealth(
                    name = "Database",
                    status = HealthCheckService.HealthStatus.UNHEALTHY,
                    message = "Error: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Register a state change listener.
     */
    fun onStateChange(listener: (DatabaseState) -> Unit) {
        stateListeners.add(listener)
    }
    
    private fun notifyListeners(state: DatabaseState) {
        for (listener in stateListeners) {
            try {
                listener(state)
            } catch (e: Exception) {
                plugin?.logger?.log(Level.WARNING, "State listener error", e)
            }
        }
    }
    
    /**
     * Get current state.
     */
    fun getState(): DatabaseState {
        return when {
            dataSource == null -> DatabaseState.NOT_CONFIGURED
            circuitOpen.get() -> DatabaseState.DISCONNECTED
            else -> DatabaseState.CONNECTED
        }
    }
    
    /**
     * Get statistics.
     */
    fun getStats(): DatabaseStats {
        val ds = dataSource
        val pool = ds?.hikariPoolMXBean
        
        return DatabaseStats(
            state = getState(),
            failureCount = failureCount.get(),
            circuitOpen = circuitOpen.get(),
            lastFailureTime = if (lastFailureTime > 0) lastFailureTime else null,
            activeConnections = pool?.activeConnections ?: 0,
            idleConnections = pool?.idleConnections ?: 0,
            totalConnections = pool?.totalConnections ?: 0,
            threadsAwaitingConnection = pool?.threadsAwaitingConnection ?: 0
        )
    }
    
    /**
     * Manually trigger reconnection attempt.
     */
    fun forceReconnect() {
        plugin?.logger?.info("Forcing database reconnection...")
        failureCount.set(0)
        circuitOpen.set(false)
        checkHealth()
    }
    
    /**
     * Shutdown the resilience manager.
     */
    fun shutdown() {
        healthCheckTask?.cancel(true)
        scheduler.shutdown()
        stateListeners.clear()
    }
    
    // ============================================
    // Data Classes & Exceptions
    // ============================================
    
    enum class DatabaseState {
        NOT_CONFIGURED,
        CONNECTED,
        DISCONNECTED
    }
    
    data class DatabaseStats(
        val state: DatabaseState,
        val failureCount: Int,
        val circuitOpen: Boolean,
        val lastFailureTime: Long?,
        val activeConnections: Int,
        val idleConnections: Int,
        val totalConnections: Int,
        val threadsAwaitingConnection: Int
    )
    
    class CircuitOpenException(message: String) : RuntimeException(message)
    
    class DatabaseOperationException(
        message: String,
        cause: Throwable? = null
    ) : RuntimeException(message, cause)
}
