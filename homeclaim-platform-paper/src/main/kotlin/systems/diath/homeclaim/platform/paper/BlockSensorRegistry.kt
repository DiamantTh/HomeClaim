package systems.diath.homeclaim.platform.paper

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.plugin.java.JavaPlugin
import systems.diath.homeclaim.platform.paper.I18n
import java.io.File

/**
 * Configuration for allowed sensor block types used to control pad access.
 * Stores which block types can be used as teleport pad sensors.
 */
data class SensorBlockConfig(
    val allowedBlockTypes: Set<Material> = setOf(Material.DAYLIGHT_DETECTOR),
    val requireRedstoneSignal: Boolean = true,
    val minPowerLevel: Int = 1
) {
    /**
     * Check if a block is a valid sensor block.
     */
    fun isValidSensor(block: Block): Boolean {
        return block.type in allowedBlockTypes
    }
    
    /**
     * Check if a sensor block has an active redstone signal.
     */
    fun hasActiveSignal(block: Block): Boolean {
        if (!requireRedstoneSignal) return true
        return block.blockData.let { data ->
            when (data) {
                is org.bukkit.block.data.type.DaylightDetector -> data.power >= minPowerLevel
                else -> false
            }
        }
    }
}

/**
 * Registry for managing sensor block detection.
 * Loads configuration from sensor-config.toml
 */
object BlockSensorRegistry {
    private var config = SensorBlockConfig()
    private val i18n = I18n()
    
    /**
     * Initialize from TOML config file.
     */
    fun initialize(plugin: JavaPlugin) {
        val configFile = File(plugin.dataFolder, "sensor-config.toml")
        if (!configFile.exists()) {
            // Create default config
            plugin.dataFolder.mkdirs()
            plugin.getResource("sensor-config.toml")?.bufferedReader()?.use { reader ->
                configFile.bufferedWriter().use { writer ->
                    reader.copyTo(writer)
                }
            }
        }
        
        try {
            config = parseTomlConfig(configFile)
            plugin.logger.info(i18n.msg("sensor.config.loaded", config.allowedBlockTypes.size.toString(), config.requireRedstoneSignal.toString()))
        } catch (e: Exception) {
            plugin.logger.warning(i18n.msg("sensor.config.failed", e.message ?: "unknown"))
            config = SensorBlockConfig()
        }
    }
    
    private fun parseTomlConfig(file: File): SensorBlockConfig {
        val lines = file.readLines()
        var allowedBlocks = setOf("DAYLIGHT_DETECTOR")
        var requireSignal = true
        var minPower = 1
        
        var inSensorsSection = false
        for (line in lines) {
            val trimmed = line.trim()
            
            // Skip comments and empty lines
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            
            // Check for [sensors] section
            if (trimmed == "[sensors]") {
                inSensorsSection = true
                continue
            }
            
            if (inSensorsSection) {
                when {
                    trimmed.startsWith("allowed_blocks") -> {
                        // Parse array: ["BLOCK1", "BLOCK2"]
                        val content = trimmed.substringAfter("=").trim()
                        if (content.startsWith("[") && content.endsWith("]")) {
                            allowedBlocks = content
                                .removeSurrounding("[", "]")
                                .split(",")
                                .map { it.trim().removeSurrounding("\"") }
                                .toSet()
                        }
                    }
                    trimmed.startsWith("require_redstone_signal") -> {
                        requireSignal = trimmed.substringAfter("=").trim().toBoolean()
                    }
                    trimmed.startsWith("min_power_level") -> {
                        minPower = trimmed.substringAfter("=").trim().toIntOrNull() ?: 1
                    }
                }
            }
        }
        
        val materials = allowedBlocks.mapNotNull { name ->
            try { Material.valueOf(name) } catch (e: Exception) { null }
        }.toSet()
        
        return SensorBlockConfig(
            allowedBlockTypes = materials,
            requireRedstoneSignal = requireSignal,
            minPowerLevel = minPower
        )
    }
    
    fun isValidSensor(block: Block): Boolean = config.isValidSensor(block)
    
    fun hasActiveSignal(block: Block): Boolean = config.hasActiveSignal(block)
    
    fun getSensorConfig(): SensorBlockConfig = config
}

