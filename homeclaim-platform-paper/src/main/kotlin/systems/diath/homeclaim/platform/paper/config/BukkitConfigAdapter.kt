package systems.diath.homeclaim.platform.paper.config

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import systems.diath.homeclaim.core.config.Config
import systems.diath.homeclaim.platform.paper.I18n
import java.io.File

/**
 * Bukkit YAML configuration adapter.
 */
class BukkitConfigAdapter(
    private val file: File,
    private val plugin: Plugin
) : Config {
    
    private var yaml: YamlConfiguration = YamlConfiguration()
    private val i18n = I18n()
    
    init {
        reload()
    }
    
    override fun getString(key: String, default: String): String {
        return yaml.getString(key) ?: default
    }
    
    override fun getInt(key: String, default: Int): Int {
        return yaml.getInt(key, default)
    }
    
    override fun getLong(key: String, default: Long): Long {
        return yaml.getLong(key, default)
    }
    
    override fun getDouble(key: String, default: Double): Double {
        return yaml.getDouble(key, default)
    }
    
    override fun getBoolean(key: String, default: Boolean): Boolean {
        return yaml.getBoolean(key, default)
    }
    
    override fun getList(key: String, default: List<String>): List<String> {
        val list = yaml.getStringList(key)
        return if (list.isEmpty()) default else list
    }
    
    override fun getSection(key: String): Config? {
        val section = yaml.getConfigurationSection(key) ?: return null
        return BukkitConfigSectionAdapter(section)
    }
    
    override fun getSections(): List<Config> {
        return yaml.getKeys(false)
            .mapNotNull { yaml.getConfigurationSection(it) }
            .map { BukkitConfigSectionAdapter(it) }
    }
    
    override fun set(key: String, value: Any?) {
        yaml.set(key, value)
    }
    
    override fun save(): Boolean {
        return try {
            yaml.save(file)
            true
        } catch (e: Exception) {
            plugin.logger.warning(i18n.msg("config.save.failed", e.message ?: "unknown"))
            false
        }
    }
    
    override fun reload(): Boolean {
        return try {
            yaml = YamlConfiguration.loadConfiguration(file)
            true
        } catch (e: Exception) {
            plugin.logger.warning(i18n.msg("config.load.failed", e.message ?: "unknown"))
            false
        }
    }
}

/**
 * Bukkit configuration section adapter.
 */
class BukkitConfigSectionAdapter(
    private val section: org.bukkit.configuration.ConfigurationSection
) : Config {
    
    override fun getString(key: String, default: String): String {
        return section.getString(key) ?: default
    }
    
    override fun getInt(key: String, default: Int): Int {
        return section.getInt(key, default)
    }
    
    override fun getLong(key: String, default: Long): Long {
        return section.getLong(key, default)
    }
    
    override fun getDouble(key: String, default: Double): Double {
        return section.getDouble(key, default)
    }
    
    override fun getBoolean(key: String, default: Boolean): Boolean {
        return section.getBoolean(key, default)
    }
    
    override fun getList(key: String, default: List<String>): List<String> {
        val list = section.getStringList(key)
        return if (list.isEmpty()) default else list
    }
    
    override fun getSection(key: String): Config? {
        val subsection = section.getConfigurationSection(key) ?: return null
        return BukkitConfigSectionAdapter(subsection)
    }
    
    override fun getSections(): List<Config> {
        return section.getKeys(false)
            .mapNotNull { section.getConfigurationSection(it) }
            .map { BukkitConfigSectionAdapter(it) }
    }
    
    override fun set(key: String, value: Any?) {
        section.set(key, value)
    }
    
    override fun save(): Boolean {
        return true  // Saving handled by parent
    }
    
    override fun reload(): Boolean {
        return true  // Reloading handled by parent
    }
}
