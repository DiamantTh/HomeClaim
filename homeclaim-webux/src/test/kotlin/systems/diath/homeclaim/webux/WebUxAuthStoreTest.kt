package systems.diath.homeclaim.webux

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WebUxAuthStoreTest {
    @Test
    fun `remote bind requires explicit safe configuration`() {
        val unsafe = HomeClaimWebUxServer(port = 0, host = "0.0.0.0")
        kotlin.test.assertFailsWith<IllegalArgumentException> { unsafe.start() }
        val missingOrigin = HomeClaimWebUxServer(
            port = 0,
            host = "0.0.0.0",
            allowRemote = true,
            secureCookies = true,
            allowedHosts = setOf("web.example")
        )
        kotlin.test.assertFailsWith<IllegalArgumentException> { missingOrigin.start() }
    }

    @Test
    fun `pairing is bound to player expires and can only be consumed once`() {
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val store = WebUxAuthStore(clock = { now })
        val player = UUID.randomUUID()
        val code = store.issuePairing(player, "PlayerOne")
        val (session, token) = assertNotNull(store.consumePairing(code, "127.0.0.1"))
        assertEquals(player, session.identity.playerId)
        assertEquals("PlayerOne", session.identity.username)
        assertNotNull(store.resolve(token))
        assertNull(store.consumePairing(code, "127.0.0.1"))
        store.revoke(token)
        assertNull(store.resolve(token))

        val expiredCode = store.issuePairing(player, "PlayerOne")
        now += Duration.ofMinutes(3)
        assertNull(store.consumePairing(expiredCode, "127.0.0.1"))
    }

    @Test
    fun `expired browser session is rejected`() {
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val store = WebUxAuthStore(clock = { now }, sessionTtl = Duration.ofMinutes(1))
        val code = store.issuePairing(UUID.randomUUID(), "ExpiringPlayer")
        val (_, token) = assertNotNull(store.consumePairing(code, "127.0.0.1"))
        assertNotNull(store.resolve(token))
        now += Duration.ofMinutes(2)
        assertNull(store.resolve(token))
    }

    @Test
    fun `parallel pairing exchanges have one winner`() {
        val store = WebUxAuthStore()
        val code = store.issuePairing(UUID.randomUUID(), "Racer")
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..16).map {
                pool.submit(Callable { store.consumePairing(code, "127.0.0.1") != null })
            }.map { it.get() }
            assertEquals(1, results.count { it })
            assertFalse(results.all { it })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `sessions are invalidated when store is cleared for shutdown`() {
        val store = WebUxAuthStore()
        val code = store.issuePairing(UUID.randomUUID(), "Restart")
        val (_, token) = assertNotNull(store.consumePairing(code, "localhost"))
        store.revokeAll()
        assertNull(store.resolve(token))
    }
}
