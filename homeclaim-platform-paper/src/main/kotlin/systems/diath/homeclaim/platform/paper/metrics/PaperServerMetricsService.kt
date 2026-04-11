package systems.diath.homeclaim.platform.paper

import org.bukkit.Bukkit
import systems.diath.homeclaim.api.*
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationService
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotResetService

/**
 * Paper/Folia implementation of ServerMetricsService.
 * Collects metrics from Bukkit/Paper/Folia servers including Multiverse worlds.
 */
class PaperServerMetricsService(
    private val plotMutationService: PlotMutationService,
    private val plotResetService: PlotResetService
) : ServerMetricsService {
    private val startupTime = System.currentTimeMillis()
    
    override fun collectMetrics(): ServerMetrics {
        val worldMetrics = Bukkit.getWorlds().map { world ->
            WorldMetrics(
                name = world.name,
                type = world.worldType?.name ?: "NORMAL",
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
        
        val plotsMetrics = collectPlotsMetrics()
        
        return ServerMetrics(
            uptime = (System.currentTimeMillis() - startupTime) / 1000,
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
    
    override fun collectPlotsMetrics(): PlotsMetrics {
        val byWorld = mutableMapOf<String, WorldPlotsMetrics>()
        var totalActive = 0
        var totalQueued = 0
        var totalFailed = 0
        
        for (world in Bukkit.getWorlds()) {
            val worldMetrics = collectWorldPlotMetrics(world.name)
            byWorld[world.name] = worldMetrics
            
            totalActive += worldMetrics.activeMutations + worldMetrics.activeResets
            totalQueued += worldMetrics.queuedMutations + worldMetrics.queuedResets
            totalFailed += worldMetrics.failedMutations + worldMetrics.failedResets
        }
        
        return PlotsMetrics(
            totalActive = totalActive,
            totalQueued = totalQueued,
            totalFailed = totalFailed,
            byWorld = byWorld
        )
    }
    
    override fun collectWorldMetrics(worldName: String): WorldMetrics? {
        val world = Bukkit.getWorld(worldName) ?: return null
        return WorldMetrics(
            name = world.name,
            type = world.worldType?.name ?: "NORMAL",
            loaded = true,
            environment = world.environment.name,
            chunkCount = world.loadedChunks.size,
            entityCount = world.entities.size,
            plots = collectWorldPlotMetrics(world.name)
        )
    }
    
    private fun collectWorldPlotMetrics(worldName: String): WorldPlotsMetrics {
        val mutationDiags = plotMutationService.activeJobDiagnostics(worldName)
        val resetDiags = plotResetService.activeJobDiagnostics(worldName)
        
        // SimpleHeuristic: count by job type in diagnostics
        // In production, could be more granular with job registry access
        val activeMutations = mutationDiags.filter { it.contains("active") }.size
        val queuedMutations = mutationDiags.filter { it.contains("queued") }.size
        val activeResets = resetDiags.filter { it.contains("active") }.size
        val queuedResets = resetDiags.filter { it.contains("queued") }.size
        
        return WorldPlotsMetrics(
            worldName = worldName,
            activeMutations = activeMutations,
            queuedMutations = queuedMutations,
            activeResets = activeResets,
            queuedResets = queuedResets
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
