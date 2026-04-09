package systems.diath.homeclaim.core.config

/**
 * Platform-agnostic configuration abstraction.
 */
interface Config {
    
    /**
     * Get string value.
     */
    fun getString(key: String, default: String = ""): String
    
    /**
     * Get integer value.
     */
    fun getInt(key: String, default: Int = 0): Int
    
    /**
     * Get long value.
     */
    fun getLong(key: String, default: Long = 0L): Long
    
    /**
     * Get double value.
     */
    fun getDouble(key: String, default: Double = 0.0): Double
    
    /**
     * Get boolean value.
     */
    fun getBoolean(key: String, default: Boolean = false): Boolean
    
    /**
     * Get list value.
     */
    fun getList(key: String, default: List<String> = emptyList()): List<String>
    
    /**
     * Get section (nested config).
     */
    fun getSection(key: String): Config?
    
    /**
     * Get all sections.
     */
    fun getSections(): List<Config>
    
    /**
     * Set value.
     */
    fun set(key: String, value: Any?)
    
    /**
     * Save config.
     */
    fun save(): Boolean
    
    /**
     * Reload config.
     */
    fun reload(): Boolean
}

/**
 * Simple in-memory configuration.
 */
class SimpleConfig(
    val data: MutableMap<String, Any?> = mutableMapOf()
) : Config {
    
    override fun getString(key: String, default: String): String {
        return (data[key] as? String) ?: default
    }
    
    override fun getInt(key: String, default: Int): Int {
        return (data[key] as? Number)?.toInt() ?: default
    }
    
    override fun getLong(key: String, default: Long): Long {
        return (data[key] as? Number)?.toLong() ?: default
    }
    
    override fun getDouble(key: String, default: Double): Double {
        return (data[key] as? Number)?.toDouble() ?: default
    }
    
    override fun getBoolean(key: String, default: Boolean): Boolean {
        return (data[key] as? Boolean) ?: default
    }
    
    override fun getList(key: String, default: List<String>): List<String> {
        return (data[key] as? List<*>)?.mapNotNull { it as? String } ?: default
    }
    
    override fun getSection(key: String): Config? {
        val section = data[key] as? Map<*, *> ?: return null
        return SimpleConfig(section.mapKeys { it.key.toString() }.toMutableMap())
    }
    
    override fun getSections(): List<Config> {
        return data.values.filterIsInstance<Map<*, *>>()
            .map { section ->
                SimpleConfig(section.mapKeys { it.key.toString() }.toMutableMap())
            }
    }
    
    override fun set(key: String, value: Any?) {
        data[key] = value
    }
    
    override fun save(): Boolean {
        return true  // In-memory only
    }
    
    override fun reload(): Boolean {
        return true  // In-memory only
    }
}

/**
 * Configuration builder for fluent API.
 */
class ConfigBuilder {
    private val config = SimpleConfig()
    
    fun set(key: String, value: Any?): ConfigBuilder {
        config.set(key, value)
        return this
    }
    
    fun section(key: String, builder: ConfigBuilder.() -> Unit): ConfigBuilder {
        val section = ConfigBuilder()
        section.builder()
        config.set(key, section.config.data)
        return this
    }
    
    fun build(): Config = config
}

fun buildConfig(builder: ConfigBuilder.() -> Unit): Config {
    val cfg = ConfigBuilder()
    cfg.builder()
    return cfg.build()
}
