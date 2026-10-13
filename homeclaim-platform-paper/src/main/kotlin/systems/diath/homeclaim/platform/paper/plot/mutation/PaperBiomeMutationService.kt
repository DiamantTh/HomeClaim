package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.block.Biome
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.service.WorldAreaService
import java.util.concurrent.CompletableFuture

/** Bounded Paper biome writer. The existing block mutation backend cannot represent biome cells. */
class PaperBiomeMutationService(
    private val plugin: JavaPlugin,
    private val worldAreaService: WorldAreaService,
    private val columnsPerTick: Int = 256
) {
    init { require(columnsPerTick > 0) }

    fun apply(regions: Collection<Region>, biome: Biome): CompletableFuture<Unit> {
        if (regions.isEmpty()) return CompletableFuture.completedFuture(Unit)
        val first = regions.first()
        val world = resolveWorld(first)
            ?: return CompletableFuture.failedFuture(IllegalStateException("World binding unavailable for biome mutation"))
        if (regions.any { resolveWorld(it)?.uid != world.uid }) {
            return CompletableFuture.failedFuture(IllegalArgumentException("Biome mutation regions must share one Paper world"))
        }
        val iterator = regions.asSequence().flatMap { region ->
            (region.bounds.minX..region.bounds.maxX).asSequence().flatMap { x ->
                (region.bounds.minZ..region.bounds.maxZ).asSequence().map { z -> x to z }
            }
        }.iterator()
        val result = CompletableFuture<Unit>()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            var task: BukkitTask? = null
            task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
                try {
                    repeat(columnsPerTick) {
                        if (!iterator.hasNext()) {
                            task?.cancel()
                            result.complete(Unit)
                            return@Runnable
                        }
                        val (x, z) = iterator.next()
                        world.setBiome(x, z, biome)
                    }
                } catch (failure: Throwable) {
                    task?.cancel()
                    result.completeExceptionally(failure)
                }
            }, 1L, 1L)
        })
        return result
    }

    private fun resolveWorld(region: Region): World? {
        val area = worldAreaService.getArea(region.areaId) ?: return null
        val homeWorld = worldAreaService.getWorld(area.worldId) ?: return null
        return homeWorld.platformWorldId?.let(Bukkit::getWorld) ?: Bukkit.getWorld(homeWorld.name)
    }
}
