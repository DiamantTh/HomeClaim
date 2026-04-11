package systems.diath.homeclaim.platform.paper.migration

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import systems.diath.homeclaim.core.model.*
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.core.service.AuditService
import systems.diath.homeclaim.core.service.AuditEntry
import java.util.*
import kotlin.math.abs

private object ReflectionAccess {
    fun invokeNoArg(target: Any?, vararg methodNames: String): Any? {
        if (target == null) return null
        val type = target.javaClass
        for (methodName in methodNames) {
            val method = runCatching { type.getMethod(methodName) }.getOrNull() ?: continue
            val result = runCatching { method.invoke(target) }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    fun asUuidSet(value: Any?): Set<UUID> =
        (value as? Collection<*>)
            ?.mapNotNull { it as? UUID }
            ?.toSet()
            ?: emptySet()

    fun intFrom(point: Any?, vararg methodNames: String): Int? =
        (invokeNoArg(point, *methodNames) as? Number)?.toInt()

    fun asStringMap(value: Any?): Map<String, String> {
        val map = value as? Map<*, *> ?: return emptyMap()
        val result = linkedMapOf<String, String>()

        for ((key, entryValue) in map) {
            val normalizedKey = invokeNoArg(entryValue, "getName")?.toString()
                ?: when (key) {
                    is Class<*> -> key.simpleName
                    null -> null
                    else -> key.toString()
                }
                ?: continue

            val normalizedValue = invokeNoArg(entryValue, "getValue")?.toString()
                ?: entryValue?.toString()
                ?: ""

            result[normalizedKey.lowercase()] = normalizedValue
        }

        return result
    }
}

internal object PlotSquaredImportAdapter {
    fun extractFromApi(api: Any): List<PlotImportService.ExternalPlot> {
        val allPlots = ReflectionAccess.invokeNoArg(api, "getAllPlots") as? Collection<*> ?: return emptyList()
        return allPlots.mapNotNull { plot -> plot?.let(::extractPlot) }
    }

    fun extractPlot(plot: Any): PlotImportService.ExternalPlot? {
        val owner = ReflectionAccess.invokeNoArg(plot, "getOwner") as? UUID ?: return null
        val members = ReflectionAccess.asUuidSet(ReflectionAccess.invokeNoArg(plot, "getMembers"))
        val trusted = ReflectionAccess.asUuidSet(ReflectionAccess.invokeNoArg(plot, "getTrusted"))
        val denied = ReflectionAccess.asUuidSet(ReflectionAccess.invokeNoArg(plot, "getDenied"))

        val worldName = ReflectionAccess.invokeNoArg(plot, "getWorldName")?.toString()
            ?: ReflectionAccess.invokeNoArg(ReflectionAccess.invokeNoArg(plot, "getArea"), "getWorldName")?.toString()
            ?: return null

        val region = ReflectionAccess.invokeNoArg(plot, "getLargestRegion", "getRegion")
            ?: (ReflectionAccess.invokeNoArg(plot, "getRegions") as? Iterable<*>)?.firstOrNull()
            ?: return null

        val minPoint = ReflectionAccess.invokeNoArg(region, "getMinimumPoint") ?: return null
        val maxPoint = ReflectionAccess.invokeNoArg(region, "getMaximumPoint") ?: return null

        val minX = ReflectionAccess.intFrom(minPoint, "getX", "getBlockX") ?: 0
        val minZ = ReflectionAccess.intFrom(minPoint, "getZ", "getBlockZ") ?: 0
        val maxX = ReflectionAccess.intFrom(maxPoint, "getX", "getBlockX") ?: minX
        val maxZ = ReflectionAccess.intFrom(maxPoint, "getZ", "getBlockZ") ?: minZ

        val flagMap = ReflectionAccess.asStringMap(ReflectionAccess.invokeNoArg(plot, "getFlags"))
            .ifEmpty {
                ReflectionAccess.asStringMap(
                    ReflectionAccess.invokeNoArg(
                        ReflectionAccess.invokeNoArg(plot, "getFlagContainer"),
                        "getFlagMap"
                    )
                )
            }

        val plotId = ReflectionAccess.invokeNoArg(plot, "getId")?.toString() ?: "$worldName:$minX:$minZ"

        return PlotImportService.ExternalPlot(
            id = plotId,
            owner = owner,
            world = worldName,
            minX = minX,
            maxX = maxX,
            minZ = minZ,
            maxZ = maxZ,
            members = members,
            trusted = trusted,
            denied = denied,
            flags = flagMap,
            metadata = mapOf(
                "plugin" to "PlotSquared",
                "plot_id" to plotId,
                "world" to worldName
            )
        )
    }
}

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

        // Mapping-Tabelle für WorldGuard/PlotSquared → HomeClaim.
        // The boolean on the right is the fallback when the source value is not directly parseable.
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

        fun resolveBoolean(rawValue: String?, fallback: Boolean): Boolean {
            return when (rawValue?.trim()?.lowercase()) {
                "true", "allow", "allowed", "enabled", "yes", "1", "on" -> true
                "false", "deny", "denied", "disabled", "no", "0", "off" -> false
                else -> fallback
            }
        }

        for ((key, rawValue) in externalFlags) {
            val mapping = flagMapping[key.lowercase()] ?: continue
            val parts = mapping.split(":")
            if (parts.size == 2) {
                val flagKey = FlagKey(parts[0])
                val fallback = parts[1].toBooleanStrictOrNull() ?: false
                mapped[flagKey] = PolicyValue.Bool(resolveBoolean(rawValue, fallback))
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
                        
                        for ((_, region) in regions) {
                            try {
                                val plot = extractWorldGuardRegion(region, world.name)
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
        
        private fun extractWorldGuardRegion(region: Any, worldName: String): ExternalPlot? {
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
                val minZ = minPoint?.javaClass?.getMethod("getBlockZ")?.invoke(minPoint) as? Int ?: 0

                val maxX = maxPoint?.javaClass?.getMethod("getBlockX")?.invoke(maxPoint) as? Int ?: 0
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
                
                // PlotSquared API integration (supports current constructor-based API and older singleton access patterns)
                val plotAPIClass = Class.forName("com.plotsquared.core.PlotAPI")
                val plotAPI = runCatching { plotAPIClass.getDeclaredConstructor().newInstance() }
                    .getOrElse {
                        val getInstance = plotAPIClass.getMethod("getInstance")
                        getInstance.invoke(null)
                    }

                plots += PlotSquaredImportAdapter.extractFromApi(plotAPI)
                return plots
            } catch (e: Exception) {
                return emptyList()
            }
        }
        
        private fun extractPlotSquaredPlot(plot: Any, @Suppress("UNUSED_PARAMETER") psPlugin: Plugin): ExternalPlot? {
            return PlotSquaredImportAdapter.extractPlot(plot)
        }
    }
}
