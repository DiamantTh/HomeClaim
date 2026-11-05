package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Loads only touched chunks, with bounded work and tickets shared by operations of this plugin. */
class BiomeChunkLoader(
    private val plugin: JavaPlugin,
    private val timeoutTicks: Long = TIMEOUT_TICKS,
    private val request: (World, Coordinate) -> CompletableFuture<org.bukkit.Chunk> = { world, coordinate -> world.getChunkAtAsync(coordinate.x, coordinate.z, true) }
) {
    init { require(timeoutTicks > 0) }
    data class Coordinate(val x: Int, val z: Int)
    private data class Key(val world: UUID, val coordinate: Coordinate)
    private data class Ticket(val world: World, var users: Int, val owned: Boolean)

    fun acquire(world: World, cells: Collection<BiomeCell>): CompletableFuture<AutoCloseable> {
        val result = CompletableFuture<AutoCloseable>()
        val coordinates = try { requiredChunks(cells) } catch (failure: Throwable) { return CompletableFuture.failedFuture(failure) }
        if (coordinates.size > MAX_CHUNKS) return CompletableFuture.failedFuture(IllegalArgumentException("Biome operation exceeds $MAX_CHUNKS chunks"))
        onMain(result) {
            val held = mutableListOf<Key>()
            val released = AtomicBoolean()
            val lease = AutoCloseable {
                if (released.compareAndSet(false, true)) onMain(CompletableFuture<Unit>()) {
                    val tickets = references[plugin] ?: return@onMain
                    held.forEach { key ->
                        val ticket = tickets[key] ?: return@forEach
                        if (--ticket.users == 0) {
                            if (ticket.owned) ticket.world.removePluginChunkTicket(key.coordinate.x, key.coordinate.z, plugin)
                            tickets.remove(key)
                        }
                    }
                    if (tickets.isEmpty()) references.remove(plugin)
                }
            }
            var cursor = 0
            var pending = 0
            val timeout = plugin.server.scheduler.runTaskLater(plugin, Runnable {
                result.completeExceptionally(java.util.concurrent.TimeoutException("Biome chunk loading timed out"))
            }, timeoutTicks)
            result.whenComplete { _, failure ->
                timeout.cancel()
                if (failure != null) lease.close()
            }
            fun pump() {
                if (result.isDone) return
                if (cursor == coordinates.size && pending == 0) { result.complete(lease); return }
                while (pending < MAX_CONCURRENT_LOADS && cursor < coordinates.size && !result.isDone) {
                    val coordinate = coordinates[cursor++]
                    pending++
                    request(world, coordinate).whenComplete { _, failure ->
                        onMain(result) {
                            pending--
                            if (result.isDone) return@onMain
                            if (failure != null) { result.completeExceptionally(failure); return@onMain }
                            check(world.isChunkLoaded(coordinate.x, coordinate.z)) { "Biome chunk unloaded before ticket acquisition" }
                            val key = Key(world.uid, coordinate)
                            val tickets = references.getOrPut(plugin) { mutableMapOf() }
                            val ticket = tickets[key]
                            if (ticket == null) tickets[key] = Ticket(world, 1, world.addPluginChunkTicket(coordinate.x, coordinate.z, plugin))
                            else ticket.users++
                            held += key
                            pump()
                        }
                    }
                }
            }
            try { pump() } catch (failure: Throwable) { result.completeExceptionally(failure) }
        }
        return result
    }

    private fun onMain(result: CompletableFuture<*>, action: () -> Unit) {
        try {
            if (Bukkit.isPrimaryThread()) action()
            else plugin.server.scheduler.runTask(plugin, Runnable { runCatching(action).onFailure(result::completeExceptionally) })
        } catch (failure: Throwable) { result.completeExceptionally(failure) }
    }

    companion object {
        fun requiredChunks(cells: Collection<BiomeCell>): List<Coordinate> {
            val chunks = linkedSetOf<Coordinate>()
            cells.forEach { cell ->
                chunks += Coordinate(Math.floorDiv(cell.blockX(), 16), Math.floorDiv(cell.blockZ(), 16))
                require(chunks.size <= MAX_CHUNKS) { "Biome operation exceeds $MAX_CHUNKS chunks" }
            }
            return chunks.toList()
        }
        const val MAX_CHUNKS = 256
        const val MAX_CONCURRENT_LOADS = 2
        const val TIMEOUT_TICKS = 1200L
        // Accessed only on the Paper thread. Existing tickets owned by other callers are retained.
        private val references = WeakHashMap<JavaPlugin, MutableMap<Key, Ticket>>()
    }
}
