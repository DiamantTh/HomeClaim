package systems.diath.homeclaim.webux

import com.aayushatharva.brotli4j.decoder.BrotliInputStream
import com.aayushatharva.brotli4j.Brotli4jLoader
import com.github.luben.zstd.ZstdInputStream
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StaticCompressionTest {
    @Test
    fun `static asset selection preserves logical mime type and raw bytes`() = testApplication {
        Brotli4jLoader.ensureAvailability()
        val webUx = HomeClaimWebUxServer()
        application { webUx.run { module() } }
        val original = requireNotNull(javaClass.classLoader.getResourceAsStream("static/css/app.css")).use { it.readBytes() }

        val br = client.get("/static/css/app.css") { header(HttpHeaders.AcceptEncoding, "br, zstd") }
        assertEquals("br", br.headers[HttpHeaders.ContentEncoding])
        assertTrue(br.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/css"))
        assertTrue(br.headers[HttpHeaders.Vary].orEmpty().contains(HttpHeaders.AcceptEncoding, ignoreCase = true))
        assertContentEquals(original, BrotliInputStream(ByteArrayInputStream(br.body<ByteArray>())).use { it.readBytes() })

        val zstd = client.get("/static/css/app.css") { header(HttpHeaders.AcceptEncoding, "br;q=0, zstd;q=1") }
        assertEquals("zstd", zstd.headers[HttpHeaders.ContentEncoding])
        assertContentEquals(original, ZstdInputStream(ByteArrayInputStream(zstd.body<ByteArray>())).use { it.readBytes() })

        val raw = client.get("/static/css/app.css") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals(null, raw.headers[HttpHeaders.ContentEncoding])
        assertContentEquals(original, raw.body<ByteArray>())

        val etag = requireNotNull(br.headers[HttpHeaders.ETag])
        val notModified = client.get("/static/css/app.css") {
            header(HttpHeaders.AcceptEncoding, "br")
            header(HttpHeaders.IfNoneMatch, etag)
        }
        assertEquals(304, notModified.status.value)
        assertEquals(etag, notModified.headers[HttpHeaders.ETag])

        val small = client.get("/static/spa/_app/version.json") { header(HttpHeaders.AcceptEncoding, "br, zstd") }
        assertEquals(null, small.headers[HttpHeaders.ContentEncoding])
    }

    @Test
    fun `missing files remain 404 and traversal is rejected`() = testApplication {
        val webUx = HomeClaimWebUxServer()
        application { webUx.run { module() } }
        assertEquals(404, client.get("/static/no-such-file.js").status.value)
        val traversal = client.get("/static/%2e%2e/HomeClaim.toml")
        assertTrue(traversal.status.value == 400 || traversal.status.value == 404)
    }

    @Test
    fun `websocket upgrade remains available without compression negotiation`() = testApplication {
        val webUx = HomeClaimWebUxServer()
        application { webUx.run { module() } }
        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket("/ws") {
            send(Frame.Text("{\"type\":\"ping\"}"))
        }
        wsClient.close()
    }
}
