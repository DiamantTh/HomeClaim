package systems.diath.homeclaim.platform.paper.config

import com.electronwill.nightconfig.core.UnmodifiableConfig
import com.electronwill.nightconfig.core.file.FileConfig
import org.bukkit.plugin.Plugin
import java.io.File

/**
 * Loads HomeClaim configuration from ./config/HomeClaim.toml.
 */
class HomeClaimTomlConfig(
    private val file: File,
    private val plugin: Plugin
) {
    private var toml: FileConfig? = null

    fun ensureExists(): Boolean {
        return try {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                plugin.getResource("HomeClaim.toml")?.bufferedReader()?.use { reader ->
                    file.bufferedWriter().use { writer -> reader.copyTo(writer) }
                } ?: run {
                    file.writeText(DEFAULT_CONFIG_TOML)
                }
            }
            reload()
        } catch (e: Exception) {
            plugin.logger.severe("Failed to initialize ${file.path}: ${e.message}")
            false
        }
    }

    fun reload(): Boolean {
        return try {
            toml?.close()
            toml = FileConfig.of(file).apply { load() }
            true
        } catch (e: Exception) {
            plugin.logger.severe("Failed to load ${file.path}: ${e.message}")
            false
        }
    }

    fun getString(path: String, default: String = ""): String {
        return toml?.get<String>(path) ?: default
    }

    fun getInt(path: String, default: Int = 0): Int {
        val raw = toml?.get<Any>(path)
        return when (raw) {
            is Int -> raw
            is Long -> raw.toInt()
            is Number -> raw.toInt()
            else -> default
        }
    }

    fun getBoolean(path: String, default: Boolean = false): Boolean {
        return toml?.get<Boolean>(path) ?: default
    }

    fun getStringList(path: String, default: List<String> = emptyList()): List<String> {
        val raw = toml?.get<List<*>>(path) ?: return default
        return raw.mapNotNull { it?.toString() }.ifEmpty { default }
    }

    fun getConfig(path: String): UnmodifiableConfig? {
        return toml?.get<Any>(path) as? UnmodifiableConfig
    }

    fun getConfigList(path: String): List<UnmodifiableConfig> {
        val raw = toml?.get<List<*>>(path) ?: return emptyList()
        return raw.filterIsInstance<UnmodifiableConfig>()
    }

    companion object {
        private const val DEFAULT_CONFIG_TOML = """
[homeclaim]
locale = "en"

[homeclaim.storage]
type = "JDBC"
driver = "SQLITE"
sqliteFile = "plugins/HomeClaim/homeclaim.db"
encoding = "UTF-8"
maximumPoolSize = 10

[homeclaim.rest]
enabled = false
port = 8080
token = ""
tokenEnv = ""
tokenFile = ""
rateLimitPerMinute = 0
allowLocalhost = false
allowedHosts = []

[homeclaim.migrations]
enabled = true
allowNonPostgres = false
baselineOnMigrate = true
locations = ["classpath:db/migration"]
"""
    }
}
