package systems.diath.homeclaim.core.model

import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for custom action types.
 * Allows third-party plugins to register new action types for policy evaluation.
 * 
 * Built-in actions (BUILD, BREAK, INTERACT_BLOCK, PVP, etc.) are always available.
 * Custom actions can be registered at runtime via registerAction().
 * 
 * Example use cases:
 * - CUSTOM_PORTAL_USE
 * - CUSTOM_MINIGAME_JOIN
 * - CUSTOM_TRADE
 */
object ActionTypeRegistry {
    
    private val customActions = ConcurrentHashMap<String, ActionDescriptor>()
    
    // Built-in action types from ActionType enum
    private val builtInActions = setOf(
        "BUILD", "BREAK", "INTERACT_BLOCK", "INTERACT_ENTITY",
        "PVP", "FIRE_SPREAD", "EXPLOSION_DAMAGE", "MOB_GRIEF",
        "VEHICLE_USE", "ENTITY_DAMAGE", "REDSTONE"
    )
    
    /**
     * Register a custom action type.
     * 
     * @param name Unique action name (e.g., "CUSTOM_PORTAL_USE", "MINIGAME_JOIN")
     * @param descriptor Action descriptor with metadata
     * @throws IllegalArgumentException if name is already registered or built-in
     */
    fun registerAction(name: String, descriptor: ActionDescriptor) {
        require(name.isNotBlank()) { "Action type name cannot be blank" }
        val upper = name.uppercase()
        require(!builtInActions.contains(upper)) { "Cannot override built-in action type '$name'" }
        
        val existing = customActions.putIfAbsent(upper, descriptor)
        require(existing == null) { "Action type '$name' is already registered" }
    }
    
    /**
     * Unregister a custom action type.
     * Built-in actions cannot be unregistered.
     * 
     * @param name Action name to unregister
     * @return true if removed, false if not found
     */
    fun unregisterAction(name: String): Boolean {
        val upper = name.uppercase()
        require(!builtInActions.contains(upper)) { "Cannot unregister built-in action type '$name'" }
        
        return customActions.remove(upper) != null
    }
    
    /**
     * Check if an action name is registered (built-in or custom).
     */
    fun isRegistered(name: String): Boolean {
        val upper = name.uppercase()
        return builtInActions.contains(upper) || customActions.containsKey(upper)
    }
    
    /**
     * Get descriptor for a custom action.
     * Returns null for built-in actions or unregistered actions.
     */
    fun getDescriptor(name: String): ActionDescriptor? {
        return customActions[name.uppercase()]
    }
    
    /**
     * Get all registered custom action names (excludes built-in).
     */
    fun getCustomActions(): Set<String> {
        return customActions.keys.toSet()
    }
    
    /**
     * Get all action names (built-in + custom).
     */
    fun getAllActions(): Set<String> {
        return builtInActions + customActions.keys
    }
    
    /**
     * Clear all custom actions (for testing/reload).
     * Built-in actions are unaffected.
     */
    fun clearCustomActions() {
        customActions.clear()
    }
}

/**
 * Descriptor for a custom action type.
 * 
 * @property displayName Human-readable display name (e.g., "Portal Use")
 * @property description Short description of the action
 * @property category Optional category for grouping (e.g., "MOVEMENT", "COMBAT")
 * @property requiresTarget Whether this action requires a target entity/block
 * @property metadata Arbitrary metadata for third-party plugins
 */
data class ActionDescriptor(
    val displayName: String,
    val description: String,
    val category: String? = null,
    val requiresTarget: Boolean = false,
    val metadata: Map<String, Any> = emptyMap()
)
