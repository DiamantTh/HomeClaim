package systems.diath.homeclaim.platform.paper.lifecycle

import com.zaxxer.hikari.HikariDataSource
import org.bukkit.plugin.java.JavaPlugin
import systems.diath.homeclaim.platform.paper.I18n
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import javax.sql.DataSource

/**
 * Manages graceful shutdown of the plugin with proper resource cleanup.
 * Ensures all async operations complete and resources are properly closed.
 */
object ShutdownManager {
    
    @PublishedApi
    internal var plugin: JavaPlugin? = null
    private val i18n = I18n()
    
    private val isShuttingDown = AtomicBoolean(false)
    private val shutdownHooks = ConcurrentLinkedQueue<ShutdownHook>()
    private val pendingOperations = ConcurrentLinkedQueue<PendingOperation>()
    
    // Configurable timeouts
    var operationTimeoutMs: Long = 5000L
    var hookTimeoutMs: Long = 3000L
    var totalTimeoutMs: Long = 15000L
    
    /**
     * Initialize the shutdown manager.
     */
    fun init(plugin: JavaPlugin) {
        this.plugin = plugin
        isShuttingDown.set(false)
        shutdownHooks.clear()
        pendingOperations.clear()
    }
    
    /**
     * Check if the plugin is currently shutting down.
     */
    fun isShuttingDown(): Boolean = isShuttingDown.get()
    
    /**
     * Register a shutdown hook with priority (lower = earlier).
     * Hooks are executed in priority order during shutdown.
     */
    fun registerHook(name: String, priority: Int = 100, action: () -> Unit) {
        shutdownHooks.add(ShutdownHook(name, priority, action))
    }
    
    /**
     * Register a DataSource for graceful shutdown.
     */
    fun registerDataSource(dataSource: DataSource?) {
        if (dataSource == null) return
        registerHook("DataSource", priority = 900) {
            when (dataSource) {
                is HikariDataSource -> {
                    plugin?.logger?.info("Closing HikariCP connection pool...")
                    dataSource.close()
                }
                is Closeable -> {
                    plugin?.logger?.info("Closing DataSource...")
                    dataSource.close()
                }
            }
        }
    }
    
    /**
     * Track a pending async operation.
     * Call complete() on the returned handle when done.
     */
    fun trackOperation(name: String): OperationHandle {
        if (isShuttingDown.get()) {
            throw IllegalStateException("Cannot start operation '$name' during shutdown")
        }
        val op = PendingOperation(name, System.currentTimeMillis())
        pendingOperations.add(op)
        return OperationHandle(op)
    }
    
    /**
     * Execute a block with operation tracking.
     * Automatically marks complete when done.
     */
    inline fun <T> withOperation(name: String, block: () -> T): T {
        val handle = trackOperation(name)
        return try {
            block()
        } finally {
            handle.complete()
        }
    }
    
    /**
     * Execute graceful shutdown.
     * Called from onDisable().
     */
    fun shutdown(): ShutdownResult {
        if (!isShuttingDown.compareAndSet(false, true)) {
            return ShutdownResult(false, "Already shutting down")
        }
        
        val startTime = System.currentTimeMillis()
        val log = plugin?.logger
        val errors = mutableListOf<String>()
        
        log?.info(i18n.msg("shutdown.header.line1"))
        log?.info(i18n.msg("shutdown.header.line2"))
        log?.info(i18n.msg("shutdown.header.line3"))
        
        // Phase 1: Wait for pending operations
        log?.info(i18n.msg("shutdown.phase1"))
        val pendingCount = pendingOperations.size
        if (pendingCount > 0) {
            log?.info("  → $pendingCount operation(s) in progress")
            val opDeadline = System.currentTimeMillis() + operationTimeoutMs
            while (pendingOperations.isNotEmpty() && System.currentTimeMillis() < opDeadline) {
                Thread.sleep(50)
            }
            val remaining = pendingOperations.size
            if (remaining > 0) {
                val names = pendingOperations.take(5).joinToString { it.name }
                log?.warning("  ⚠ $remaining operation(s) did not complete: $names")
                errors.add("$remaining pending operations timed out")
            } else {
                log?.info(i18n.msg("shutdown.ops.completed"))
            }
        } else {
            log?.info(i18n.msg("shutdown.ops.none"))
        }
        
        // Phase 2: Execute shutdown hooks in priority order
        log?.info(i18n.msg("shutdown.phase2"))
        val sortedHooks = shutdownHooks.sortedBy { it.priority }
        for (hook in sortedHooks) {
            if (System.currentTimeMillis() - startTime > totalTimeoutMs) {
                log?.warning("  ⚠ Total timeout reached, skipping remaining hooks")
                errors.add("Total shutdown timeout exceeded")
                break
            }
            
            try {
                log?.info("  → ${hook.name}...")
                val hookStart = System.currentTimeMillis()
                
                // Execute hook with timeout
                val latch = CountDownLatch(1)
                var hookError: Throwable? = null
                
                Thread {
                    try {
                        hook.action()
                    } catch (e: Throwable) {
                        hookError = e
                    } finally {
                        latch.countDown()
                    }
                }.start()
                
                if (!latch.await(hookTimeoutMs, TimeUnit.MILLISECONDS)) {
                    log?.warning("    ⚠ Hook '${hook.name}' timed out")
                    errors.add("Hook '${hook.name}' timed out")
                } else if (hookError != null) {
                    log?.log(Level.WARNING, "    ⚠ Hook '${hook.name}' failed: ${hookError!!.message}", hookError)
                    errors.add("Hook '${hook.name}' failed: ${hookError!!.message}")
                } else {
                    val elapsed = System.currentTimeMillis() - hookStart
                    log?.info(i18n.msg("shutdown.hook.success", hook.name, elapsed.toString()))
                }
            } catch (e: Exception) {
                log?.log(Level.WARNING, "  ⚠ Error in hook '${hook.name}'", e)
                errors.add("Hook '${hook.name}' error: ${e.message}")
            }
        }
        
        // Phase 3: Final cleanup
        log?.info(i18n.msg("shutdown.phase3"))
        shutdownHooks.clear()
        pendingOperations.clear()
        
        val totalTime = System.currentTimeMillis() - startTime
        if (errors.isEmpty()) {
            log?.info(i18n.msg("shutdown.complete.success.line1"))
            log?.info(i18n.msg("shutdown.complete.success.line2", totalTime.toString()))
            log?.info(i18n.msg("shutdown.complete.success.line1"))
        } else {
            log?.warning(i18n.msg("shutdown.complete.warning.line1"))
            log?.warning(i18n.msg("shutdown.complete.warning.line2", errors.size.toString()))
            log?.warning(i18n.msg("shutdown.complete.warning.line3", totalTime.toString()))
            log?.warning(i18n.msg("shutdown.complete.warning.line1"))
        }
        
        return ShutdownResult(errors.isEmpty(), errors.joinToString("; "), totalTime)
    }
    
    /**
     * Get status of pending operations.
     */
    fun getStatus(): ShutdownStatus {
        return ShutdownStatus(
            isShuttingDown = isShuttingDown.get(),
            pendingOperations = pendingOperations.map { it.name },
            registeredHooks = shutdownHooks.map { it.name }
        )
    }
    
    // ============================================
    // Data Classes
    // ============================================
    
    private data class ShutdownHook(
        val name: String,
        val priority: Int,
        val action: () -> Unit
    )
    
    @ConsistentCopyVisibility
    data class PendingOperation internal constructor(
        val name: String,
        val startTime: Long,
        @Volatile var completed: Boolean = false
    )

    data class ShutdownResult(
        val success: Boolean,
        val message: String = "",
        val durationMs: Long = 0
    )
    
    data class ShutdownStatus(
        val isShuttingDown: Boolean,
        val pendingOperations: List<String>,
        val registeredHooks: List<String>
    )
    
    /**
     * Handle for tracking operation completion.
     */
    class OperationHandle internal constructor(private val operation: PendingOperation) {
        fun complete() {
            operation.completed = true
            pendingOperations.remove(operation)
        }
    }
}
