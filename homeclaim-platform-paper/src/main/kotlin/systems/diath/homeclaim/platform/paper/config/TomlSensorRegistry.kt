package systems.diath.homeclaim.platform.paper.config

import org.bukkit.plugin.Plugin
import systems.diath.homeclaim.core.config.SensorConfig
import systems.diath.homeclaim.core.config.SensorRegistry
import systems.diath.homeclaim.platform.paper.I18n
import java.io.File

/**
 * TOML-based sensor configuration loader for Bukkit.
 * Extends SensorRegistry with TOML file parsing.
 */
class TomlSensorRegistry(
    private val configFile: File,
    private val plugin: Plugin
) : SensorRegistry {
    
    private val sensors = mutableMapOf<String, SensorConfig>()
    private val i18n = I18n()
    
    init {
        loadFromToml()
    }
    
    private fun loadFromToml() {
        try {
            if (!configFile.exists()) {
                createDefaultConfig()
            }
            
            val lines = configFile.readLines()
            @Suppress("VARIABLE_WITH_REDUNDANT_INITIALIZER")
        var currentSection = ""
            val properties = mutableMapOf<String, String>()
            
            var sensorName = ""
            var sensorType = ""
            var enabled = true
            
            for (line in lines) {
                val trimmed = line.trim()
                
                // Skip comments and empty lines
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                
                // Section header: [sensor_name]
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    // Save previous sensor if exists
                    if (sensorName.isNotEmpty()) {
                        registerSensor(SensorConfig(
                            name = sensorName,
                            sensorType = sensorType,
                            enabled = enabled,
                            properties = properties.toMap()
                        ))
                    }
                    
                    currentSection = trimmed.substring(1, trimmed.length - 1)
                    sensorName = currentSection
                    sensorType = "redstone"
                    enabled = true
                    properties.clear()
                    continue
                }
                
                // Key = Value
                if (trimmed.contains("=")) {
                    val (key, value) = trimmed.split("=", limit = 2).map { it.trim() }
                    
                    when (key) {
                        "type" -> sensorType = value.removeSurrounding("\"")
                        "enabled" -> enabled = value.lowercase() == "true"
                        else -> properties[key] = value.removeSurrounding("\"")
                    }
                }
            }
            
            // Save last sensor
            if (sensorName.isNotEmpty()) {
                registerSensor(SensorConfig(
                    name = sensorName,
                    sensorType = sensorType,
                    enabled = enabled,
                    properties = properties.toMap()
                ))
            }
            
            plugin.logger.info(i18n.msg("sensors.loaded", sensors.size.toString()))
        } catch (e: Exception) {
            plugin.logger.warning(i18n.msg("sensors.failed", e.message ?: "unknown"))
            e.printStackTrace()
        }
    }
    
    private fun createDefaultConfig() {
        val content = """
            # HomeClaim Sensor Configuration
            
            [redstone_power]
            type = "redstone"
            enabled = true
            
            [pressure_plate]
            type = "pressure_plate"
            enabled = false
        """.trimIndent()
        
        configFile.parentFile?.mkdirs()
        configFile.writeText(content)
    }
    
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
