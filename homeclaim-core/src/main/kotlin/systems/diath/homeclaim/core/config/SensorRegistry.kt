package systems.diath.homeclaim.core.config

/**
 * Platform-agnostic sensor configuration.
 */
data class SensorConfig(
    val name: String,
    val sensorType: String,  // "redstone", "pressure_plate", etc.
    val enabled: Boolean = true,
    val properties: Map<String, String> = emptyMap()
)

/**
 * Service for managing block sensors and their configurations.
 * Implementations may read from TOML, JSON, YAML, or other formats.
 */
interface SensorRegistry {
    
    /**
     * Get sensor configuration by name.
     */
    fun getSensor(name: String): SensorConfig?
    
    /**
     * Get all registered sensors.
     */
    fun getAllSensors(): List<SensorConfig>
    
    /**
     * Register a sensor configuration.
     */
    fun registerSensor(config: SensorConfig)
    
    /**
     * Remove sensor configuration.
     */
    fun unregisterSensor(name: String)
    
    /**
     * Check if a sensor is enabled.
     */
    fun isEnabled(name: String): Boolean {
        return getSensor(name)?.enabled ?: false
    }
}

/**
 * In-memory implementation of SensorRegistry.
 */
class InMemorySensorRegistry : SensorRegistry {
    private val sensors = mutableMapOf<String, SensorConfig>()
    
    override fun getSensor(name: String): SensorConfig? {
        return sensors[name]
    }
    
    override fun getAllSensors(): List<SensorConfig> {
        return sensors.values.toList()
    }
    
    override fun registerSensor(config: SensorConfig) {
        sensors[config.name] = config
    }
    
    override fun unregisterSensor(name: String) {
        sensors.remove(name)
    }
}
