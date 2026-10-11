package systems.diath.homeclaim.core.model

import java.util.concurrent.ConcurrentHashMap

/**
 * Metadata registry for component type descriptors. Registering a custom descriptor does not
 * install persistence, protection, or a runtime handler. Only [ComponentType] built-ins have
 * HomeClaim runtime dispatch in this checkout.
 */
object ComponentTypeRegistry {
    
    private val customTypes = ConcurrentHashMap<String, ComponentTypeDescriptor>()
    
    /**
     * Register a custom component type.
     * 
     * @param name Unique type name (e.g., "REDSTONE_PAD", "SIGN_PAD")
     * @param descriptor Type descriptor with metadata
     * @throws IllegalArgumentException if name is already registered
     */
    fun registerType(name: String, descriptor: ComponentTypeDescriptor) {
        require(name.isNotBlank()) { "Component type name cannot be blank" }
        require(!name.equals("ELEVATOR_PAD", ignoreCase = true)) { "Cannot override built-in type ELEVATOR_PAD" }
        require(!name.equals("TELEPORT_PAD", ignoreCase = true)) { "Cannot override built-in type TELEPORT_PAD" }
        
        val existing = customTypes.putIfAbsent(name.uppercase(), descriptor)
        require(existing == null) { "Component type '$name' is already registered" }
    }
    
    /**
     * Unregister a custom component type.
     * Built-in types cannot be unregistered.
     * 
     * @param name Type name to unregister
     * @return true if removed, false if not found
     */
    fun unregisterType(name: String): Boolean {
        require(!name.equals("ELEVATOR_PAD", ignoreCase = true)) { "Cannot unregister built-in type ELEVATOR_PAD" }
        require(!name.equals("TELEPORT_PAD", ignoreCase = true)) { "Cannot unregister built-in type TELEPORT_PAD" }
        
        return customTypes.remove(name.uppercase()) != null
    }
    
    /**
     * Check if a type name is registered (built-in or custom).
     */
    fun isRegistered(name: String): Boolean {
        val upper = name.uppercase()
        return upper == "ELEVATOR_PAD" || upper == "TELEPORT_PAD" || customTypes.containsKey(upper)
    }

    /** Distinguishes executable built-ins from metadata-only custom descriptors. */
    fun runtimeSupport(name: String): ComponentTypeRuntimeSupport {
        val upper = name.uppercase()
        return when {
            ComponentType.entries.any { it.name == upper } -> ComponentTypeRuntimeSupport.BUILTIN
            customTypes.containsKey(upper) -> ComponentTypeRuntimeSupport.DESCRIPTOR_ONLY
            else -> ComponentTypeRuntimeSupport.UNKNOWN
        }
    }
    
    /**
     * Get descriptor for a custom type.
     * Returns null for built-in types or unregistered types.
     */
    fun getDescriptor(name: String): ComponentTypeDescriptor? {
        return customTypes[name.uppercase()]
    }
    
    /**
     * Get all registered custom type names (excludes built-in types).
     */
    fun getCustomTypes(): Set<String> {
        return customTypes.keys.toSet()
    }
    
    /**
     * Get all type names (built-in + custom).
     */
    fun getAllTypes(): Set<String> {
        return setOf("ELEVATOR_PAD", "TELEPORT_PAD") + customTypes.keys
    }
    
    /**
     * Clear all custom types (for testing/reload).
     * Built-in types are unaffected.
     */
    fun clearCustomTypes() {
        customTypes.clear()
    }
}

enum class ComponentTypeRuntimeSupport {
    BUILTIN,
    DESCRIPTOR_ONLY,
    UNKNOWN
}

/**
 * Descriptor for a custom component type.
 * 
 * @property displayName Human-readable display name (e.g., "Redstone Pad")
 * @property description Short description of the component's purpose
 * @property requiresConfig Whether this type requires configuration data
 * @property metadata Arbitrary metadata for third-party plugins
 */
data class ComponentTypeDescriptor(
    val displayName: String,
    val description: String,
    val requiresConfig: Boolean = false,
    val metadata: Map<String, Any> = emptyMap()
)
