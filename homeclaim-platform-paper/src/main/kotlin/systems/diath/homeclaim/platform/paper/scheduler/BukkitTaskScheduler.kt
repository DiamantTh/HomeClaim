package systems.diath.homeclaim.platform.paper.scheduler

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import systems.diath.homeclaim.core.scheduler.TaskScheduler

/** Paper scheduler adapter. World mutations still need to be scheduled by their owning world flow. */
class BukkitTaskScheduler(private val plugin: Plugin) : TaskScheduler {
    override fun runSyncTask(delay: Long, task: () -> Unit): Long =
        (if (delay <= 0) {
            Bukkit.getScheduler().runTask(plugin, Runnable(task))
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, Runnable(task), delay.toTicks())
        }).taskId.toLong()

    override fun runSyncRepeating(delay: Long, period: Long, task: () -> Unit): Long =
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable(task), delay.toTicks(), period.toTicks()).taskId.toLong()

    override fun runAsyncTask(delay: Long, task: () -> Unit): Long =
        (if (delay <= 0) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable(task))
        } else {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, Runnable(task), delay.toTicks())
        }).taskId.toLong()

    override fun runAsyncRepeating(delay: Long, period: Long, task: () -> Unit): Long =
        Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                Runnable(task),
                delay.toTicks(),
                period.toTicks()
            ).taskId.toLong()

    override fun cancelTask(taskId: Long) {
        Bukkit.getScheduler().cancelTask(taskId.toInt())
    }

    override fun cancelAllTasks() {
        Bukkit.getScheduler().cancelTasks(plugin)
    }

    private fun Long.toTicks(): Long = (this.coerceAtLeast(0) / 50).coerceAtLeast(0)
}
