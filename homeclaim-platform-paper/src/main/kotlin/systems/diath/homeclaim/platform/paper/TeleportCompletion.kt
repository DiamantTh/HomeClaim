package systems.diath.homeclaim.platform.paper

import java.util.concurrent.CompletionException
import java.util.concurrent.CompletableFuture

internal sealed interface TeleportCompletion {
    data object Succeeded : TeleportCompletion
    data class Failed(val detail: String) : TeleportCompletion

    companion object {
        fun resolve(success: Boolean?, failure: Throwable?): TeleportCompletion {
            if (failure == null && success == true) return Succeeded
            if (failure == null) return Failed("Paper rejected the teleport")
            val cause = generateSequence(failure) { (it as? CompletionException)?.cause }.last()
            return Failed(cause.message ?: cause.javaClass.simpleName)
        }
    }
}

internal fun awaitSuccessfulTeleports(futures: Collection<CompletableFuture<Boolean>>): CompletableFuture<Unit> =
    CompletableFuture.allOf(*futures.toTypedArray()).thenApply {
        if (futures.any { it.getNow(false) != true }) {
            throw IllegalStateException("One or more Paper teleports were rejected")
        }
        Unit
    }
