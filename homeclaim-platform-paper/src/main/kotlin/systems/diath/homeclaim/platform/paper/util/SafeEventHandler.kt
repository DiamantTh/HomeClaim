package systems.diath.homeclaim.platform.paper.util

import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.plugin.Plugin
import java.util.logging.Level

/**
 * Utility for safe event handling with exception catching.
 * Prevents plugin exceptions from crashing the server or other plugins.
 */
object SafeEventHandler {
    
    @PublishedApi
    internal var plugin: Plugin? = null
    
    fun init(plugin: Plugin) {
        this.plugin = plugin
    }
    
    /**
     * Safely execute event handler code.
     * If an exception occurs:
     * - Logs the error with full stack trace
     * - Cancels the event if possible (fail-safe)
     * - Does NOT propagate the exception
     * 
     * @param event The event being handled
     * @param handlerName Name of the handler for logging
     * @param failSafe If true, cancels the event on error (default: true for security)
     * @param block The handler code to execute
     */
    inline fun <T : Event> handle(
        event: T,
        handlerName: String,
        failSafe: Boolean = true,
        block: () -> Unit
    ) {
        try {
            block()
        } catch (e: Exception) {
            plugin?.logger?.log(
                Level.SEVERE,
                "Exception in event handler '$handlerName' for ${event.eventName}: ${e.message}",
                e
            )
            
            // Fail-safe: Cancel the event to prevent potential exploits
            if (failSafe && event is Cancellable) {
                (event as Cancellable).isCancelled = true
            }
        }
    }
    
    /**
     * Safely execute code that should never crash.
     * Logs errors but does not cancel anything.
     */
    inline fun runSafe(context: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            plugin?.logger?.log(
                Level.SEVERE,
                "Exception in '$context': ${e.message}",
                e
            )
        }
    }
    
    /**
     * Safely execute code and return a default value on error.
     */
    inline fun <T> runSafeOrDefault(context: String, default: T, block: () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            plugin?.logger?.log(
                Level.WARNING,
                "Exception in '$context', using default: ${e.message}",
                e
            )
            default
        }
    }
}
