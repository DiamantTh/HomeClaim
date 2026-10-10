package systems.diath.homeclaim.platform.paper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletableFuture

class TeleportCompletionTest {
    @Test
    fun `successful future is the only successful outcome`() {
        assertEquals(TeleportCompletion.Succeeded, TeleportCompletion.resolve(true, null))
        assertEquals(TeleportCompletion.Failed("Paper rejected the teleport"), TeleportCompletion.resolve(false, null))
        assertEquals(TeleportCompletion.Failed("Paper rejected the teleport"), TeleportCompletion.resolve(null, null))
    }

    @Test
    fun `exceptional future reports the unwrapped cause`() {
        assertEquals(
            TeleportCompletion.Failed("world unavailable"),
            TeleportCompletion.resolve(null, CompletionException(IllegalStateException("world unavailable")))
        )
    }

    @Test
    fun `evacuation waits for every teleport and requires all to succeed`() {
        val first = CompletableFuture<Boolean>()
        val second = CompletableFuture<Boolean>()
        val evacuation = awaitSuccessfulTeleports(listOf(first, second))

        first.complete(true)
        assertEquals(false, evacuation.isDone)
        second.complete(true)
        assertEquals(Unit, evacuation.join())
    }

    @Test
    fun `evacuation fails when any teleport is rejected or exceptional`() {
        val rejected = awaitSuccessfulTeleports(listOf(CompletableFuture.completedFuture(true), CompletableFuture.completedFuture(false)))
        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CompletionException::class.java) { rejected.join() }

        val failed = awaitSuccessfulTeleports(
            listOf(CompletableFuture.completedFuture(true), CompletableFuture.failedFuture(IllegalStateException("world unavailable")))
        )
        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CompletionException::class.java) { failed.join() }
    }
}
