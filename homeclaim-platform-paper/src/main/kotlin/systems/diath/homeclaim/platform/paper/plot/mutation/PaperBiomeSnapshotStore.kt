package systems.diath.homeclaim.platform.paper.plot.mutation

import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Atomic, bounded storage for the independent BiomeSnapshotV1 contract. */
class PaperBiomeSnapshotStore(plugin: JavaPlugin) : AutoCloseable {
    private val directory = plugin.dataFolder.toPath().resolve("biome-snapshots")
    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "${plugin.name}-biome-snapshots").apply { isDaemon = true }
    }

    fun write(snapshot: BiomeSnapshotV1): CompletableFuture<BiomeSnapshotReference> = CompletableFuture.supplyAsync({
        val bytes = snapshot.encode()
        Files.createDirectories(directory)
        val destination = path(snapshot.id)
        val temporary = directory.resolve(".${snapshot.id}.tmp")
        try {
            Files.write(temporary, bytes)
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, destination) }
        } finally { Files.deleteIfExists(temporary) }
        BiomeSnapshotReference(snapshot.id, sha256Hex(bytes), bytes.size.toLong())
    }, executor)

    fun read(id: UUID, expectedSha256: String): CompletableFuture<BiomeSnapshotV1> = CompletableFuture.supplyAsync({
        require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid biome snapshot checksum reference" }
        val file = path(id)
        val size = Files.size(file)
        require(size in 1..BiomeSnapshotV1.MAX_FILE_BYTES.toLong()) { "Biome snapshot file size is invalid" }
        val bytes = Files.readAllBytes(file)
        require(sha256Hex(bytes) == expectedSha256) { "Biome snapshot file checksum reference mismatch" }
        val snapshot = BiomeSnapshotV1.decode(bytes)
        require(snapshot.id == id) { "Biome snapshot identity mismatch" }
        snapshot
    }, executor)

    private fun path(id: UUID): Path = directory.resolve("$id.hcbs")

    override fun close() { (executor as? java.util.concurrent.ExecutorService)?.shutdown() }

    private fun sha256Hex(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

data class BiomeSnapshotReference(val id: UUID, val sha256: String, val sizeBytes: Long)
