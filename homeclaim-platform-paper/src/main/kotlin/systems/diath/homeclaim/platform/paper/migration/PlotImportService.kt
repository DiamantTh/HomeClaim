package systems.diath.homeclaim.platform.paper.migration

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import systems.diath.homeclaim.core.model.*
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.core.service.AuditService
import systems.diath.homeclaim.core.service.AuditEntry
import java.util.*
import kotlin.math.abs

/**
 * Importiert externe Plot-Plugins (WorldGuard, PlotSquared) zu HomeClaim.
 * Async-Migration mit Progress-Tracking.
 */
class PlotImportService(
    private val regionService: RegionService,
    private val auditService: AuditService? = null
) {
    
    private val importCache = mutableMapOf<String, ImportProgress>()
    
    data class ImportProgress(
        val id: String,
        val totalPlots: Int,
        var importedPlots: Int = 0,
        var failedPlots: Int = 0,
        var status: ImportStatus = ImportStatus.PENDING,
        val startTime: Long = System.currentTimeMillis(),
        var endTime: Long? = null,
        val errors: MutableList<String> = mutableListOf()
    )
    
    enum class ImportStatus {
        PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED
    }
    
    data class ExternalPlot(
        val id: String,
        val owner: UUID,
        val world: String,
        val minX: Int,
        val minZ: Int,
        val maxX: Int,
        val maxZ: Int,
        val members: Set<UUID> = emptySet(),
        val trusted: Set<UUID> = emptySet(),
        val denied: Set<UUID> = emptySet(),
        val flags: Map<String, String> = emptyMap(),
        val metadata: Map<String, String> = emptyMap()
    )
    
    /**
     * Startet den Import-Prozess asynchron.
     * 
     * @param plots Liste von zu importierenden Plots
     * @param callback Wird aufgerufen bei Progress/Completion
     * @return Import-ID zum Tracking
     */
    fun startImport(
        plots: List<ExternalPlot>,
        callback: (progress: ImportProgress) -> Unit
    ): String {
        val importId = "import_${System.currentTimeMillis()}_${Random().nextInt(10000)}"
        val progress = ImportProgress(
            id = importId,
            totalPlots = plots.size
        )
        importCache[importId] = progress
        
        // Async Task starten
        val plugin = Bukkit.getPluginManager().getPlugin("HomeClaim")
        if (plugin != null) {
            // Use Folia-compatible async scheduler
            if (isFolia()) {
                Bukkit.getAsyncScheduler().runNow(plugin) { _ ->
                    runImportTask(plots, progress, callback)
                }
            } else {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                    runImportTask(plots, progress, callback)
                })
            }
        } else {
            progress.status = ImportStatus.FAILED
            progress.errors.add("HomeClaim plugin not found")
            callback(progress)
        }
        
        return importId
    }
    
    /**
     * Führt den Import-Task aus (wird asynchron aufgerufen).
     */
    private fun runImportTask(
        plots: List<ExternalPlot>,
        progress: ImportProgress,
        callback: (progress: ImportProgress) -> Unit
    ) {
        try {
            progress.status = ImportStatus.IN_PROGRESS
            callback(progress)
            
            for (plot in plots) {
                try {
                    importPlot(plot)
                    progress.importedPlots++
                } catch (e: Exception) {
                    progress.failedPlots++
                    progress.errors.add("${plot.id}: ${e.message}")
                }
                callback(progress)
            }
            
            progress.status = ImportStatus.COMPLETED
            progress.endTime = System.currentTimeMillis()
        } catch (e: Exception) {
            progress.status = ImportStatus.FAILED
            progress.errors.add("Migration failed: ${e.message}")
            progress.endTime = System.currentTimeMillis()
        }
        
        callback(progress)
    }
    
    /**
     * Detect if running on Folia.
     */
    private fun isFolia(): Boolean {
        return try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
    
    /**
     * Importiere einen einzelnen Plot
     */
    private fun importPlot(external: ExternalPlot) {
        // Erstelle Bounds (AABB) aus WorldEdit/PlotSquared Koordinaten
        val minY = 0
        val maxY = 319
        val bounds = Bounds(
            minX = external.minX,
            maxX = external.maxX,
            minY = minY,
            maxY = maxY,
            minZ = external.minZ,
            maxZ = external.maxZ
        )
        
        // Erstelle Region
        val regionId = RegionId(UUID.randomUUID())
        val region = Region(
            id = regionId,
            world = external.world,
            shape = RegionShape.CUBOID,
            bounds = bounds,
            owner = external.owner,
            roles = RegionRoles(
                trusted = external.trusted,
                members = external.members,
                banned = external.denied
            ),
            flags = mapFlagsFromExternal(external.flags),
            metadata = external.metadata + mapOf(
                "imported_from" to (external.metadata["plugin"] ?: "external"),
                "import_date" to System.currentTimeMillis().toString(),
                "original_id" to external.id
            )
        )
        
        // Speichern
        regionService.createRegion(region, bounds)
        
        // Audit-Eintrag
        auditService?.append(
            AuditEntry(
                actorId = external.owner,
                targetId = regionId.value,
                category = "IMPORT",
                action = "PLOT_IMPORTED",
                payload = mapOf(
                    "source" to (external.metadata["plugin"] ?: "unknown"),
                    "world" to external.world,
                    "originalId" to external.id
                )
            )
        )
    }
    
    /**
     * Mappinge externe Flags zu HomeClaim Flags
     */
    private fun mapFlagsFromExternal(externalFlags: Map<String, String>): Map<FlagKey, FlagValue> {
        val mapped = mutableMapOf<FlagKey, FlagValue>()
        
        // Mapping-Tabelle für WorldGuard/PlotSquared → HomeClaim
        val flagMapping = mapOf(
            // WorldGuard Flags
            "deny-all" to "BUILD:false",
            "deny-message" to null,
            "greet-message" to null,
            "farewell-message" to null,
            "allow-flight" to null,
            "mob-damage" to "MOB_GRIEF:false",
            "creeper-explosion" to "EXPLOSION_DAMAGE:false",
            "tnt-explosion" to "EXPLOSION_DAMAGE:false",
            "ghast-fireball" to "EXPLOSION_DAMAGE:false",
            "other-explosion" to "EXPLOSION_DAMAGE:false",
            "dragon-fireball" to "EXPLOSION_DAMAGE:false",
            "block-break" to "BREAK:false",
            "block-place" to "BUILD:false",
            "use" to "INTERACT_BLOCK:false",
            "leaf-decay" to null,
            "lava-flow" to "FIRE_SPREAD:false",
            "water-flow" to null,
            "pvp" to "PVP:false",
            "sleep" to null,
            "lightning-strike" to null,
            "time-lock" to null,
            
            // PlotSquared Flags
            "blocked-cmds" to null,
            "break" to "BREAK:false",
            "place" to "BUILD:false",
            "interact" to "INTERACT_BLOCK:false",
            "pvp" to "PVP:false",
            "pve" to "MOB_GRIEF:false",
            "use" to "INTERACT_BLOCK:false",
            "animal-attack" to "ENTITY_DAMAGE:false",
            "animal-cap" to null,
            "vehicle-place" to "VEHICLE_USE:false",
            "vehicle-use" to "VEHICLE_USE:false",
            "hostile-cap" to null,
            "mob-cap" to null,
            "flow" to "FIRE_SPREAD:false",
            "grass-grow" to null
        )
        
        for ((key, _) in externalFlags) {
            val mapping = flagMapping[key.lowercase()] ?: continue
            
            val parts = mapping.split(":")
            if (parts.size == 2) {
                val flagKey = FlagKey(parts[0])
                val boolValue = parts[1].toBoolean()
                val flagValue: FlagValue = PolicyValue.Bool(boolValue)
                mapped[flagKey] = flagValue
            }
        }
        
        return mapped
    }
    
    /**
     * Hol Import-Progress
     */
    fun getProgress(importId: String): ImportProgress? {
        return importCache[importId]
    }
    
    /**
     * Liste alle aktiven Imports
     */
    fun listActiveImports(): List<ImportProgress> {
        return importCache.values
            .filter { it.status == ImportStatus.IN_PROGRESS || it.status == ImportStatus.PENDING }
            .toList()
    }
    
    /**
     * Cancelle einen Import
     */
    fun cancelImport(importId: String): Boolean {
        val progress = importCache[importId] ?: return false
        if (progress.status == ImportStatus.IN_PROGRESS) {
            progress.status = ImportStatus.CANCELLED
            progress.endTime = System.currentTimeMillis()
            return true
        }
        return false
    }
    
    /**
     * Erstelle ImportSource für WorldGuard Regions
     */
    object WorldGuardSource {
        fun extract(): List<ExternalPlot> {
            val plots = mutableListOf<ExternalPlot>()
            
            try {
                val wgPlugin = Bukkit.getPluginManager().getPlugin("WorldGuard")
                if (wgPlugin == null || !wgPlugin.isEnabled) return emptyList()
                
                // WorldGuard API Integration
                // Iteriere durch alle Welten und ihre Regionen
                for (world in Bukkit.getWorlds()) {
                    try {
                        // Nutze Reflection, um RegionContainer zu bekommen
                        val wgClass = Class.forName("com.sk89q.worldguard.WorldGuard")
                        val platformMethod = wgClass.getMethod("getPlatform")
                        val platform = platformMethod.invoke(null)
                        
                        val regionContainerClass = Class.forName("com.sk89q.worldguard.protection.managers.RegionContainer")
                        val getManagerMethod = regionContainerClass.getMethod("get", Class.forName("com.sk89q.worldedit.world.World"))
                        
                        // WorldEdit World aus Bukkit World
                        val worldEditWorldClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter")
                        val adaptMethod = worldEditWorldClass.getMethod("adapt", org.bukkit.World::class.java)
                        val weWorld = adaptMethod.invoke(null, world)
                        
                        val regionManager = getManagerMethod.invoke(
                            platform.javaClass.getMethod("getRegionContainer").invoke(platform),
                            weWorld
                        ) ?: continue
                        
                        // Iteriere durch Regionen
                        val regionsMethod = regionManager.javaClass.getMethod("getRegions")
                        @Suppress("UNCHECKED_CAST")
                        val regions = regionsMethod.invoke(regionManager) as? Map<String, Any> ?: continue
                        
                        for ((regionName, region) in regions) {
                            try {
                                val plot = extractWorldGuardRegion(region, world.name, wgPlugin)
                                if (plot != null) plots.add(plot)
                            } catch (e: Exception) {
                                // Skip ungültige Regionen
                            }
                        }
                    } catch (e: Exception) {
                        // Skip Welten ohne RegionManager
                    }
                }
                
                return plots
            } catch (e: Exception) {
                return emptyList()
            }
        }
        
        private fun extractWorldGuardRegion(region: Any, worldName: String, wgPlugin: Plugin): ExternalPlot? {
            try {
                // Extrahiere Owner/Members/Denied Sets
                val ownersField = region.javaClass.getField("owners")
                val membersField = region.javaClass.getField("members")
                val deniedField = region.javaClass.getField("denied")
                
                val owners = ownersField.get(region)
                val members = membersField.get(region)
                val denied = deniedField.get(region)
                
                // Extrahiere UUIDs aus den Domains
                val ownerUUIDs = extractUUIDs(owners)
                val memberUUIDs = extractUUIDs(members)
                val deniedUUIDs = extractUUIDs(denied)
                
                val owner = ownerUUIDs.firstOrNull() ?: return null
                
                // Extrahiere Bounds
                val minPointField = region.javaClass.getMethod("getMinimumPoint")
                val maxPointField = region.javaClass.getMethod("getMaximumPoint")
                
                val minPoint = minPointField.invoke(region)
                val maxPoint = maxPointField.invoke(region)
                
                val minX = minPoint?.javaClass?.getMethod("getBlockX")?.invoke(minPoint) as? Int ?: 0
                val minY = minPoint?.javaClass?.getMethod("getBlockY")?.invoke(minPoint) as? Int ?: 0
                val minZ = minPoint?.javaClass?.getMethod("getBlockZ")?.invoke(minPoint) as? Int ?: 0
                
                val maxX = maxPoint?.javaClass?.getMethod("getBlockX")?.invoke(maxPoint) as? Int ?: 0
                val maxY = maxPoint?.javaClass?.getMethod("getBlockY")?.invoke(maxPoint) as? Int ?: 0
                val maxZ = maxPoint?.javaClass?.getMethod("getBlockZ")?.invoke(maxPoint) as? Int ?: 0
                
                // Extrahiere Flags
                val flagsMethod = region.javaClass.getMethod("getFlags")
                @Suppress("UNCHECKED_CAST")
                val flags = flagsMethod.invoke(region) as? Map<String, Any> ?: emptyMap()
                
                val flagMap = mutableMapOf<String, String>()
                for ((key, value) in flags) {
                    flagMap[key] = value.toString()
                }
                
                return ExternalPlot(
                    id = region.javaClass.getMethod("getId").invoke(region).toString(),
                    owner = owner,
                    world = worldName,
                    minX = minX,
                    maxX = maxX,
                    minZ = minZ,
                    maxZ = maxZ,
                    members = memberUUIDs.toSet(),
                    trusted = emptySet(),
                    denied = deniedUUIDs.toSet(),
                    flags = flagMap,
                    metadata = mapOf(
                        "plugin" to "WorldGuard",
                        "region_name" to (region.javaClass.getMethod("getId").invoke(region).toString()),
                        "world" to worldName
                    )
                )
            } catch (e: Exception) {
                return null
            }
        }
        
        private fun extractUUIDs(domain: Any): List<UUID> {
            val uuids = mutableListOf<UUID>()
            try {
                val allMethod = domain.javaClass.getMethod("all")
                @Suppress("UNCHECKED_CAST")
                val all = allMethod.invoke(domain) as? Collection<Any> ?: return emptyList()
                
                for (item in all) {
                    try {
                        val uuid = when {
                            item is UUID -> item
                            item.toString().matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) -> {
                                UUID.fromString(item.toString())
                            }
                            else -> null
                        }
                        if (uuid != null) uuids.add(uuid)
                    } catch (e: Exception) {
                        // Skip ungültige UUIDs
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
            return uuids
        }
    }
    
    /**
     * Erstelle ImportSource für PlotSquared Regions
     */
    object PlotSquaredSource {
        fun extract(): List<ExternalPlot> {
            val plots = mutableListOf<ExternalPlot>()
            
            try {
                val psPlugin = Bukkit.getPluginManager().getPlugin("PlotSquared")
                if (psPlugin == null || !psPlugin.isEnabled) return emptyList()
                
                // PlotSquared API Integration
                val plotAPIClass = Class.forName("com.plotsquared.core.PlotAPI")
                val getInstance = plotAPIClass.getMethod("getInstance")
                val plotAPI = getInstance.invoke(null)
                
                // Iteriere durch alle Plots
                val getPlotsMethod = plotAPI.javaClass.getMethod("getAllPlots")
                @Suppress("UNCHECKED_CAST")
                val allPlots = getPlotsMethod.invoke(plotAPI) as? Collection<Any> ?: return emptyList()
                
                for (plot in allPlots) {
                    try {
                        val extracted = extractPlotSquaredPlot(plot, psPlugin)
                        if (extracted != null) plots.add(extracted)
                    } catch (e: Exception) {
                        // Skip ungültige Plots
                    }
                }
                
                return plots
            } catch (e: Exception) {
                return emptyList()
            }
        }
        
        private fun extractPlotSquaredPlot(plot: Any, psPlugin: Plugin): ExternalPlot? {
            try {
                // Extrahiere Besitzer
                val ownerMethod = plot.javaClass.getMethod("getOwner")
                val ownerUUID = ownerMethod.invoke(plot) as? UUID ?: return null
                
                // Extrahiere Members/Trusted/Denied
                val membersMethod = plot.javaClass.getMethod("getMembers")
                val trustedMethod = plot.javaClass.getMethod("getTrusted")
                val deniedMethod = plot.javaClass.getMethod("getDenied")
                
                @Suppress("UNCHECKED_CAST")
                val members = (membersMethod.invoke(plot) as? Set<UUID>) ?: emptySet()
                @Suppress("UNCHECKED_CAST")
                val trusted = (trustedMethod.invoke(plot) as? Set<UUID>) ?: emptySet()
                @Suppress("UNCHECKED_CAST")
                val denied = (deniedMethod.invoke(plot) as? Set<UUID>) ?: emptySet()
                
                // Extrahiere Location/Bounds
                val areaMethod = plot.javaClass.getMethod("getArea")
                val area = areaMethod.invoke(plot)
                
                val regionMethod = plot.javaClass.getMethod("getRegion")
                val region = regionMethod.invoke(plot)
                
                val minX = region?.javaClass?.getMethod("getMinimumPoint")?.invoke(region)?.javaClass?.getMethod("getX")?.invoke(region?.javaClass?.getMethod("getMinimumPoint")?.invoke(region)) as? Int ?: 0
                val minZ = region?.javaClass?.getMethod("getMinimumPoint")?.invoke(region)?.javaClass?.getMethod("getZ")?.invoke(region?.javaClass?.getMethod("getMinimumPoint")?.invoke(region)) as? Int ?: 0
                val maxX = region?.javaClass?.getMethod("getMaximumPoint")?.invoke(region)?.javaClass?.getMethod("getX")?.invoke(region?.javaClass?.getMethod("getMaximumPoint")?.invoke(region)) as? Int ?: 0
                val maxZ = region?.javaClass?.getMethod("getMaximumPoint")?.invoke(region)?.javaClass?.getMethod("getZ")?.invoke(region?.javaClass?.getMethod("getMaximumPoint")?.invoke(region)) as? Int ?: 0
                
                // Extrahiere World
                val worldNameMethod = plot.javaClass.getMethod("getWorldName")
                val worldName = worldNameMethod.invoke(plot).toString()
                
                // Extrahiere Flags
                val flagsMethod = plot.javaClass.getMethod("getFlags")
                @Suppress("UNCHECKED_CAST")
                val flagsMap = flagsMethod.invoke(plot) as? Map<String, Any> ?: emptyMap()
                
                val flagMap = mutableMapOf<String, String>()
                for ((key, value) in flagsMap) {
                    flagMap[key] = value.toString()
                }
                
                // Extrahiere Plot ID
                val plotIdMethod = plot.javaClass.getMethod("getId")
                val plotId = plotIdMethod.invoke(plot).toString()
                
                return ExternalPlot(
                    id = plotId,
                    owner = ownerUUID,
                    world = worldName,
                    minX = minX,
                    maxX = maxX,
                    minZ = minZ,
                    maxZ = maxZ,
                    members = members.toSet(),
                    trusted = trusted.toSet(),
                    denied = denied.toSet(),
                    flags = flagMap,
                    metadata = mapOf(
                        "plugin" to "PlotSquared",
                        "plot_id" to plotId,
                        "world" to worldName
                    )
                )
            } catch (e: Exception) {
                return null
            }
        }
    }
}
