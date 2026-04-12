package systems.diath.homeclaim.platform.paper

import org.bukkit.Bukkit
import systems.diath.homeclaim.api.*
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationService
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotResetService

/**
 * Paper/Folia implementation of ServerMetricsService.
 * Collects metrics from Bukkit/Paper/Folia servers including Multiverse worlds.
 */
class PaperServerMetricsService(
    private val homeClaimVersion: String,
    private val regionService: RegionService,
    private val plotMutationService: PlotMutationService,
    private val plotResetService: PlotResetService
) : ServerMetricsService {
    private val startupTime = System.currentTimeMillis()
    
    override fun collectMetrics(): ServerMetrics {
        val worldMetrics = Bukkit.getWorlds().map { world ->
            WorldMetrics(
                name = world.name,
                type = world.environment.name,
                loaded = true,
                environment = world.environment.name,
                chunkCount = world.loadedChunks.size,
                entityCount = world.entities.size,
                plots = collectWorldPlotMetrics(world.name)
            )
        }
        
        val memory = Runtime.getRuntime()
        val usedMB = (memory.totalMemory() - memory.freeMemory()) / (1024 * 1024)
        val maxMB = memory.maxMemory() / (1024 * 1024)
        val allocatedMB = memory.totalMemory() / (1024 * 1024)
        val percentUsed = (usedMB * 100) / maxMB
        
        val version = getVersionInfo()
        val load = getLoadInfo()
        val plotsMetrics = collectPlotsMetrics()
        
        return ServerMetrics(
            version = version,
            uptime = (System.currentTimeMillis() - startupTime) / 1000,
            load = load,
            onlinePlayers = Bukkit.getOnlinePlayers().size,
            maxPlayers = Bukkit.getMaxPlayers(),
            tps = getServerTPS(),
            mspt = getServerMSPT(),
            totalWorlds = Bukkit.getWorlds().size,
            worlds = worldMetrics,
            memory = MemoryMetrics(
                usedMB = usedMB,
                maxMB = maxMB,
                allocatedMB = allocatedMB,
                percentUsed = percentUsed.toFloat()
            ),
            plots = plotsMetrics
        )
    }
    
    private fun getVersionInfo(): VersionInfo {
        val javaVersion = System.getProperty("java.version") ?: "unknown"
        val server = Bukkit.getServer()
        val serverImpl = if (isFolia()) "Folia" else "Paper"
        return VersionInfo(
            homeclaimVersion = homeClaimVersion,
            javaVersion = javaVersion,
            serverImplementation = serverImpl,
            serverVersion = server.version
        )
    }
    
    private fun getLoadInfo(): LoadInfo {
        val osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
        return LoadInfo(
            oneMinuteAverage = osBean.systemLoadAverage.coerceAtLeast(0.0),
            fiveMinuteAverage = osBean.systemLoadAverage.coerceAtLeast(0.0),  // Java doesn't expose 5/15 min directly
            fifteenMinuteAverage = osBean.systemLoadAverage.coerceAtLeast(0.0),
            availableProcessors = osBean.availableProcessors
        )
    }
    
    private fun isFolia(): Boolean {
        return try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            true
        } catch (e: Exception) {
            false
        }
    }
    
    override fun collectPlotsMetrics(): PlotsMetrics {
        val byWorld = mutableMapOf<String, WorldPlotsMetrics>()
        var totalActive = 0
        var totalQueued = 0
        var totalFailed = 0
        var totalCancelling = 0
        val oldestPendingMillis = mutableListOf<Long>()
        val averageAges = mutableListOf<Long>()
        
        for (world in Bukkit.getWorlds()) {
            val worldMetrics = collectWorldPlotMetrics(world.name)
            byWorld[world.name] = worldMetrics
            
            totalActive += worldMetrics.activeMutations + worldMetrics.activeResets
            totalQueued += worldMetrics.queuedMutations + worldMetrics.queuedResets
            totalFailed += worldMetrics.failedMutations + worldMetrics.failedResets
            totalCancelling += worldMetrics.cancellingMutations + worldMetrics.cancellingResets
            listOf(worldMetrics.oldestMutationAgeMillis, worldMetrics.oldestResetAgeMillis)
                .filter { it > 0L }
                .forEach(oldestPendingMillis::add)
            listOf(worldMetrics.oldestMutationAgeMillis, worldMetrics.oldestResetAgeMillis)
                .filter { it > 0L }
                .forEach(averageAges::add)
        }
        
        return PlotsMetrics(
            totalActive = totalActive,
            totalQueued = totalQueued,
            totalFailed = totalFailed,
            totalCancelling = totalCancelling,
            byWorld = byWorld,
            avgProcessingMs = averageAges.average().takeIf { !it.isNaN() }?.toLong() ?: 0L,
            oldestPendingSeconds = (oldestPendingMillis.maxOrNull() ?: 0L) / 1000L
        )
    }
    
    override fun collectWorldMetrics(worldName: String): WorldMetrics? {
        val world = Bukkit.getWorld(worldName) ?: return null
        return WorldMetrics(
            name = world.name,
            type = world.environment.name,
            loaded = true,
            environment = world.environment.name,
            chunkCount = world.loadedChunks.size,
            entityCount = world.entities.size,
            plots = collectWorldPlotMetrics(world.name)
        )
    }
    
    private fun collectWorldPlotMetrics(worldName: String): WorldPlotsMetrics {
        val mutationJobs = plotMutationService.activeJobInfo(worldName)
        val resetJobs = plotResetService.activeJobInfo(worldName)
        
        return WorldPlotsMetrics(
            worldName = worldName,
            activeMutations = mutationJobs.size,
            queuedMutations = 0,
            failedMutations = 0,
            cancellingMutations = mutationJobs.count { it.cancelRequested },
            activeResets = resetJobs.size,
            queuedResets = 0,
            failedResets = 0,
            cancellingResets = resetJobs.count { it.cancelRequested },
            totalPlots = regionService.listAllRegions().count { it.world == worldName },
            oldestMutationAgeMillis = mutationJobs.maxOfOrNull { it.queuedMillis } ?: 0L,
            oldestResetAgeMillis = resetJobs.maxOfOrNull { it.queuedMillis } ?: 0L
        )
    }
    
    private fun getServerTPS(): Float {
        return try {
            // Use reflection to get TPS from Paper/Folia if available
            val server = Bukkit.getServer()
            val method = server.javaClass.getMethod("getTPS")
            val tpsArray = method.invoke(server) as DoubleArray
            tpsArray.getOrNull(0)?.toFloat() ?: 20.0f
        } catch (e: Exception) {
            20.0f // Default if not available
        }
    }
    
    private fun getServerMSPT(): Long {
        return try {
            val server = Bukkit.getServer()
            val method = server.javaClass.getMethod("getAverageTickTime")
            val mspt = method.invoke(server) as Double
            mspt.toLong()
        } catch (e: Exception) {
            50L // Default 50ms for 20 TPS
        }
    }
}
