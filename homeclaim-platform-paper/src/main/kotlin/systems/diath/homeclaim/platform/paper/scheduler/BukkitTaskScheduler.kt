package systems.diath.homeclaim.platform.paper.scheduler

import org.bukkit.plugin.Plugin
import org.bukkit.Bukkit
import systems.diath.homeclaim.core.scheduler.TaskScheduler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Folia-compatible implementation of TaskScheduler.
 * 
 * Automatically detects Folia and uses appropriate schedulers:
 * - Folia: GlobalRegionScheduler for global tasks, AsyncScheduler for async
 * - Paper/Spigot: BukkitScheduler (legacy)
 * 
 * Note: This implementation uses GlobalRegionScheduler for sync tasks on Folia,
 * which means tasks are not tied to specific regions. For region-specific tasks,
 * use RegionScheduler directly or implement a location-aware scheduler.
 */
class BukkitTaskScheduler(private val plugin: Plugin) : TaskScheduler {
    
    private val isFolia: Boolean by lazy {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
    
    // Folia uses ScheduledTask objects instead of int IDs
    // We map our Long IDs to ScheduledTask objects for Folia
    private val foliaTasks = ConcurrentHashMap<Long, io.papermc.paper.threadedregions.scheduler.ScheduledTask>()
    private var nextTaskId = 1L
    
    override fun runSyncTask(delay: Long, task: () -> Unit): Long {
        val ticks = delay / 50  // Convert ms to ticks (50ms per tick)
        
        return if (isFolia) {
            val taskId = nextTaskId++
            val scheduledTask = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { _ -> task() }, ticks)
            foliaTasks[taskId] = scheduledTask
            taskId
        } else {
            Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, task, ticks).toLong()
        }
    }
    
    override fun runSyncRepeating(delay: Long, period: Long, task: () -> Unit): Long {
        val delayTicks = delay / 50
        val periodTicks = period / 50
        
        return if (isFolia) {
            val taskId = nextTaskId++
            val scheduledTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin, 
                { _ -> task() }, 
                delayTicks, 
                periodTicks
            )
            foliaTasks[taskId] = scheduledTask
            taskId
        } else {
            Bukkit.getScheduler().scheduleSyncRepeatingTask(
                plugin,
                task,
                delayTicks,
                periodTicks
            ).toLong()
        }
    }
    
    override fun runAsyncTask(delay: Long, task: () -> Unit): Long {
        return if (isFolia) {
            val taskId = nextTaskId++
            val delayMs = delay.coerceAtLeast(1)
            val scheduledTask = Bukkit.getAsyncScheduler().runDelayed(
                plugin, 
                { _ -> task() }, 
                delayMs, 
                TimeUnit.MILLISECONDS
            )
            foliaTasks[taskId] = scheduledTask
            taskId
        } else {
            val ticks = delay / 50
            @Suppress("DEPRECATION")
            Bukkit.getScheduler().scheduleAsyncDelayedTask(plugin, task, ticks).toLong()
        }
    }
    
    override fun runAsyncRepeating(delay: Long, period: Long, task: () -> Unit): Long {
        return if (isFolia) {
            val taskId = nextTaskId++
            val delayMs = delay.coerceAtLeast(1)
            val periodMs = period.coerceAtLeast(1)
            val scheduledTask = Bukkit.getAsyncScheduler().runAtFixedRate(
                plugin, 
                { _ -> task() }, 
                delayMs, 
                periodMs, 
                TimeUnit.MILLISECONDS
            )
            foliaTasks[taskId] = scheduledTask
            taskId
        } else {
            val delayTicks = delay / 50
            val periodTicks = period / 50
            @Suppress("DEPRECATION")
            Bukkit.getScheduler().scheduleAsyncRepeatingTask(
                plugin,
                task,
                delayTicks,
                periodTicks
            ).toLong()
        }
    }
    
    override fun cancelTask(taskId: Long) {
        if (isFolia) {
            foliaTasks.remove(taskId)?.cancel()
        } else {
            Bukkit.getScheduler().cancelTask(taskId.toInt())
        }
    }
    
    override fun cancelAllTasks() {
        if (isFolia) {
            foliaTasks.values.forEach { it.cancel() }
            foliaTasks.clear()
            // Also cancel via plugin reference for any tasks not in our map
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin)
            Bukkit.getAsyncScheduler().cancelTasks(plugin)
        } else {
            Bukkit.getScheduler().cancelTasks(plugin)
        }
    }
}

