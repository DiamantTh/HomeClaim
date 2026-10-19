package systems.diath.homeclaim.webux

import com.aayushatharva.brotli4j.decoder.BrotliInputStream
import com.aayushatharva.brotli4j.Brotli4jLoader
import com.github.luben.zstd.ZstdInputStream
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import io.ktor.websocket.readText
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.UUID
import systems.diath.homeclaim.core.service.AuditEntry
import systems.diath.homeclaim.core.service.AuditService

class StaticCompressionTest {
    @Test
    fun `webux app requires a session and admin routes stay disabled`() = testApplication {
        val store = WebUxAuthStore()
        val code = store.issuePairing(UUID.randomUUID(), "ReadOnlyPlayer")
        val webUx = HomeClaimWebUxServer(authStore = store)
        application { webUx.run { module() } }

        assertEquals(403, client.get("/app") { header(HttpHeaders.Host, "attacker@localhost") }.status.value)
        val redirectClient = createClient { followRedirects = false }
        val app = redirectClient.get("/app") { header(HttpHeaders.Host, "localhost") }
        assertEquals(302, app.status.value)
        assertEquals("/app/login", app.headers[HttpHeaders.Location])
        assertEquals(403, client.get("/app/admin/users") { header(HttpHeaders.Host, "localhost") }.status.value)

        val login = client.post("/auth/pair") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            header(HttpHeaders.ContentType, "application/json")
            setBody("{\"code\":\"$code\"}")
        }
        val cookie = requireNotNull(login.headers[HttpHeaders.SetCookie]).substringBefore(';')
        assertEquals(403, client.get("/app/admin/users") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Cookie, cookie)
        }.status.value)
        redirectClient.close()
    }

    @Test
    fun `static asset selection preserves logical mime type and raw bytes`() = testApplication {
        Brotli4jLoader.ensureAvailability()
        val webUx = HomeClaimWebUxServer()
        application { webUx.run { module() } }
        val original = requireNotNull(javaClass.classLoader.getResourceAsStream("static/css/app.css")).use { it.readBytes() }

        val br = client.get("/static/css/app.css") { header(HttpHeaders.Host, "localhost"); header(HttpHeaders.AcceptEncoding, "br, zstd") }
        assertEquals("br", br.headers[HttpHeaders.ContentEncoding])
        assertTrue(br.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/css"))
        assertTrue(br.headers[HttpHeaders.Vary].orEmpty().contains(HttpHeaders.AcceptEncoding, ignoreCase = true))
        assertContentEquals(original, BrotliInputStream(ByteArrayInputStream(br.body<ByteArray>())).use { it.readBytes() })

        val zstd = client.get("/static/css/app.css") { header(HttpHeaders.Host, "localhost"); header(HttpHeaders.AcceptEncoding, "br;q=0, zstd;q=1") }
        assertEquals("zstd", zstd.headers[HttpHeaders.ContentEncoding])
        assertContentEquals(original, ZstdInputStream(ByteArrayInputStream(zstd.body<ByteArray>())).use { it.readBytes() })

        val raw = client.get("/static/css/app.css") { header(HttpHeaders.Host, "localhost"); header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals(null, raw.headers[HttpHeaders.ContentEncoding])
        assertContentEquals(original, raw.body<ByteArray>())

        val etag = requireNotNull(br.headers[HttpHeaders.ETag])
        val notModified = client.get("/static/css/app.css") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.AcceptEncoding, "br")
            header(HttpHeaders.IfNoneMatch, etag)
        }
        assertEquals(304, notModified.status.value)
        assertEquals(etag, notModified.headers[HttpHeaders.ETag])

        val small = client.get("/static/spa/_app/version.json") { header(HttpHeaders.Host, "localhost"); header(HttpHeaders.AcceptEncoding, "br, zstd") }
        assertEquals(null, small.headers[HttpHeaders.ContentEncoding])
    }

    @Test
    fun `missing files remain 404 and traversal is rejected`() = testApplication {
        val webUx = HomeClaimWebUxServer()
        application { webUx.run { module() } }
        assertEquals(404, client.get("/static/no-such-file.js") { header(HttpHeaders.Host, "localhost") }.status.value)
        val traversal = client.get("/static/%2e%2e/HomeClaim.toml") { header(HttpHeaders.Host, "localhost") }
        assertTrue(traversal.status.value == 400 || traversal.status.value == 404)
    }

    @Test
    fun `websocket upgrade remains available without compression negotiation`() = testApplication {
        val store = WebUxAuthStore()
        val code = store.issuePairing(UUID.randomUUID(), "SocketPlayer")
        val webUx = HomeClaimWebUxServer(authStore = store)
        application { webUx.run { module() } }
        val wsClient = createClient { install(WebSockets) }
        assertTrue(runCatching {
            wsClient.webSocket("/ws", request = { header(HttpHeaders.Host, "localhost") }) { }
        }.isFailure)
        val login = client.post("/auth/pair") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            header(HttpHeaders.ContentType, "application/json")
            setBody("{\"code\":\"$code\"}")
        }
        assertEquals(200, login.status.value)
        val cookie = requireNotNull(login.headers[HttpHeaders.SetCookie]).substringBefore(';')
        assertTrue(runCatching {
            wsClient.webSocket("/ws", request = {
                header(HttpHeaders.Host, "localhost")
                header(HttpHeaders.Origin, "https://attacker.invalid")
                header(HttpHeaders.Cookie, cookie)
            }) { }
        }.isFailure)
        wsClient.webSocket("/ws", request = {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            header(HttpHeaders.Cookie, cookie)
        }) {
            send(Frame.Text("{\"type\":\"ping\"}"))
            val response = incoming.receive() as Frame.Text
            assertTrue(response.readText().contains("pong"))
        }
        wsClient.close()
    }

    @Test
    fun `webux requires pairing and rejects foreign origin and csrf mismatch`() = testApplication {
        val store = WebUxAuthStore()
        val code = store.issuePairing(UUID.randomUUID(), "WebPlayer")
        val auditEntries = mutableListOf<AuditEntry>()
        val audit = object : AuditService {
            override fun append(entry: AuditEntry) { auditEntries += entry }
        }
        val webUx = HomeClaimWebUxServer(authStore = store, auditService = audit)
        application { webUx.run { module() } }
        val foreign = client.post("/auth/pair") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "https://attacker.invalid")
            header(HttpHeaders.ContentType, "application/json")
            setBody("{\"code\":\"$code\"}")
        }
        assertEquals(403, foreign.status.value)
        repeat(2) {
            val invalid = client.post("/auth/pair") {
                header(HttpHeaders.Host, "localhost")
                header(HttpHeaders.Origin, "http://localhost")
                header(HttpHeaders.ContentType, "application/json")
                setBody("{\"code\":\"an-invalid-pairing-code-value\"}")
            }
            assertEquals(401, invalid.status.value)
        }
        val login = client.post("/auth/pair") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            header(HttpHeaders.ContentType, "application/json")
            setBody("{\"code\":\"$code\"}")
        }
        assertEquals(200, login.status.value)
        assertTrue(requireNotNull(login.headers[HttpHeaders.SetCookie]).contains("HttpOnly"))
        assertTrue(login.headers[HttpHeaders.SetCookie].orEmpty().contains("SameSite=Lax"))
        val cookie = requireNotNull(login.headers[HttpHeaders.SetCookie]).substringBefore(';')
        val session = client.get("/auth/session") { header(HttpHeaders.Host, "localhost"); header(HttpHeaders.Cookie, cookie) }
        assertEquals(200, session.status.value)
        val badLogout = client.post("/auth/logout") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Cookie, cookie)
            header(HttpHeaders.Origin, "http://localhost")
            header("X-CSRF-Token", "wrong")
        }
        assertEquals(403, badLogout.status.value)
        val csrf = Regex("\\\"csrfToken\\\":\\\"([^\\\"]+)\\\"")
            .find(session.body<String>())!!.groupValues[1]
        val logout = client.post("/auth/logout") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Cookie, cookie)
            header(HttpHeaders.Origin, "http://localhost")
            header("X-CSRF-Token", csrf)
        }
        assertEquals(200, logout.status.value)
        assertEquals(401, client.get("/auth/session") { header(HttpHeaders.Host, "localhost"); header(HttpHeaders.Cookie, cookie) }.status.value)
        assertEquals(listOf("LOGIN_FAILED", "LOGIN_SUCCESS", "LOGOUT"), auditEntries.map { it.action })
        assertTrue(auditEntries.first().payload["sampled"] == true)
        assertTrue(auditEntries.all { entry -> entry.payload.values.none { it.toString().contains(code) } })
    }
}
