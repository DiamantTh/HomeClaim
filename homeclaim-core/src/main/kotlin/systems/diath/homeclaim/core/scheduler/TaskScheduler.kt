package systems.diath.homeclaim.core.scheduler

/**
 * Platform-agnostic task scheduler.
 */
interface TaskScheduler {
    
    /**
     * Run task on main thread (sync).
     * @return Task ID for cancellation
     */
    fun runSyncTask(delay: Long = 0, task: () -> Unit): Long
    
    /**
     * Run task repeatedly on main thread.
     * @param delay Initial delay before first execution
     * @param period Time between executions (0 = one-time)
     * @return Task ID for cancellation
     */
    fun runSyncRepeating(delay: Long, period: Long, task: () -> Unit): Long
    
    /**
     * Run task asynchronously (off main thread).
     * @return Task ID for cancellation
     */
    fun runAsyncTask(delay: Long = 0, task: () -> Unit): Long
    
    /**
     * Run task repeatedly asynchronously.
     * @param delay Initial delay before first execution
     * @param period Time between executions (0 = one-time)
     * @return Task ID for cancellation
     */
    fun runAsyncRepeating(delay: Long, period: Long, task: () -> Unit): Long
    
    /**
     * Cancel task by ID.
     */
    fun cancelTask(taskId: Long)
    
    /**
     * Cancel all tasks.
     */
    fun cancelAllTasks()
}

/**
 * Simple in-memory task scheduler (for testing).
 */
class SimpleTaskScheduler : TaskScheduler {
    private val tasks = mutableMapOf<Long, Task>()
    private var nextId: Long = 1L
    
    private data class Task(
        val id: Long,
        val action: () -> Unit,
        val delay: Long,
        val period: Long,
        val isAsync: Boolean,
        var nextRun: Long = System.currentTimeMillis() + delay,
        var cancelled: Boolean = false
    )
    
    override fun runSyncTask(delay: Long, task: () -> Unit): Long {
        val id = nextId++
        tasks[id] = Task(id, task, delay, 0, false)
        return id
    }
    
    override fun runSyncRepeating(delay: Long, period: Long, task: () -> Unit): Long {
        val id = nextId++
        tasks[id] = Task(id, task, delay, period, false)
        return id
    }
    
    override fun runAsyncTask(delay: Long, task: () -> Unit): Long {
        val id = nextId++
        tasks[id] = Task(id, task, delay, 0, true)
        return id
    }
    
    override fun runAsyncRepeating(delay: Long, period: Long, task: () -> Unit): Long {
        val id = nextId++
        tasks[id] = Task(id, task, delay, period, true)
        return id
    }
    
    override fun cancelTask(taskId: Long) {
        tasks[taskId]?.cancelled = true
    }
    
    override fun cancelAllTasks() {
        tasks.values.forEach { it.cancelled = true }
    }
    
    /**
     * Process tasks (should be called periodically).
     */
    fun processTasks() {
        val now = System.currentTimeMillis()
        val toRemove = mutableListOf<Long>()
        
        tasks.forEach { (id, task) ->
            if (task.cancelled) {
                toRemove.add(id)
                return@forEach
            }
            
            if (now >= task.nextRun) {
                if (task.isAsync) {
                    Thread { task.action() }.start()
                } else {
                    task.action()
                }
                
                if (task.period > 0) {
                    task.nextRun = now + task.period
                } else {
                    toRemove.add(id)
                }
            }
        }
        
        toRemove.forEach { tasks.remove(it) }
    }
}
