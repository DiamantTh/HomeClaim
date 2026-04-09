package systems.diath.homeclaim.platform.paper.plot

import com.electronwill.nightconfig.core.UnmodifiableConfig
import com.electronwill.nightconfig.core.file.FileConfig
import org.bukkit.Material
import org.bukkit.plugin.Plugin
import systems.diath.homeclaim.platform.paper.I18n
import java.io.File

class PlotSchemaLoader(private val plugin: Plugin) {
    
    private val schemasDir = File(plugin.dataFolder, "plot-schemas")
    private val logger = plugin.logger
    private val i18n = I18n()
    
    fun loadAllSchemas(): Map<String, PlotWorldConfig> {
        if (!schemasDir.exists()) {
            schemasDir.mkdirs()
            copyDefaultSchemas()
        }
        
        val schemas = mutableMapOf<String, PlotWorldConfig>()
        val tomlFiles = schemasDir.listFiles { file ->
            file.isFile && file.name.endsWith(".toml")
        } ?: return schemas
        
        for (file in tomlFiles) {
            try {
                val schema = loadSchemaFromFile(file)
                if (schema != null) {
                    schemas[schema.schema] = schema
                }
            } catch (e: Exception) {
                logger.warning(i18n.msg("schema.load.failed", file.name, e.message ?: "unknown"))
            }
        }
        
        return schemas
    }
    
    private fun loadSchemaFromFile(file: File): PlotWorldConfig? {
        val toml = FileConfig.of(file).apply { load() }
        val schemaTable = toml.get<Any>("schema") as? UnmodifiableConfig ?: return null

        val name = (schemaTable.get<Any>("name") as? String) ?: file.nameWithoutExtension
        val plotBlock = Material.valueOf((schemaTable.get<Any>("plot_block") as? String) ?: return null)
        val roadBlock = Material.valueOf((schemaTable.get<Any>("road_block") as? String) ?: return null)
        val wallBlock = Material.valueOf((schemaTable.get<Any>("wall_block") as? String) ?: return null)
        val accentBlock = Material.SMOOTH_QUARTZ_STAIRS

        val result = PlotWorldConfig(
            worldName = "",
            plotSize = 50,
            roadWidth = 10,
            plotHeight = 64,
            plotsPerSide = 10,
            plotBlock = plotBlock,
            roadBlock = roadBlock,
            wallBlock = wallBlock,
            accentBlock = accentBlock,
            schema = name
        )
        toml.close()
        return result
    }
    
    private fun copyDefaultSchemas() {
        val file = File(schemasDir, "default.toml")
        if (!file.exists()) {
            file.writeText("[schema]\nname = \"default\"\nplot_block = \"GRASS_BLOCK\"\nroad_block = \"DARK_PRISMARINE\"\nwall_block = \"DIAMOND_BLOCK\"\n", Charsets.UTF_8)
        }
    }
}
