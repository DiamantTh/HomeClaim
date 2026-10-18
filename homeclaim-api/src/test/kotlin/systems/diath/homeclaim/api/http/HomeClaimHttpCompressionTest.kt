package systems.diath.homeclaim.api.http

import com.aayushatharva.brotli4j.decoder.BrotliInputStream
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.luben.zstd.ZstdInputStream
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeClaimHttpCompressionTest {
    private val payload = "{\"plots\":[" + (1..512).joinToString(",") { "{\"id\":$it,\"name\":\"a repeated plot label\"}" } + "]}"

    @Test
    fun `large json uses preferred stream codec and preserves json content type`() = testApplication {
        application {
            installHomeClaimHttpCompression()
            routing {
                get("/large") {
                    call.response.header(HttpHeaders.Vary, "Origin")
                    call.respondText(payload, ContentType.Application.Json)
                }
                get("/small") { call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json) }
            }
        }

        val zstdResponse = client.get("/large") { header(HttpHeaders.AcceptEncoding, "br, zstd") }
        assertEquals("zstd", zstdResponse.headers[HttpHeaders.ContentEncoding])
        assertEquals("application/json", zstdResponse.headers[HttpHeaders.ContentType]?.substringBefore(';'))
        val vary = zstdResponse.headers.getAll(HttpHeaders.Vary).orEmpty().joinToString(",")
        assertTrue(vary.contains(HttpHeaders.AcceptEncoding, ignoreCase = true))
        assertTrue(vary.contains("Origin", ignoreCase = true))
        assertEquals(1, vary.split(',').count { it.trim().equals(HttpHeaders.AcceptEncoding, ignoreCase = true) })
        val zstdDecoded = ZstdInputStream(ByteArrayInputStream(zstdResponse.body<ByteArray>())).use { it.readBytes() }
        assertContentEquals(payload.toByteArray(), zstdDecoded)

        val brResponse = client.get("/large") { header(HttpHeaders.AcceptEncoding, "zstd;q=0, br;q=1") }
        assertEquals("br", brResponse.headers[HttpHeaders.ContentEncoding])
        val brDecoded = BrotliInputStream(ByteArrayInputStream(brResponse.body<ByteArray>())).use { it.readBytes() }
        assertContentEquals(payload.toByteArray(), brDecoded)

        val identity = client.get("/large")
        assertEquals(null, identity.headers[HttpHeaders.ContentEncoding])
        assertContentEquals(payload.toByteArray(), identity.body<ByteArray>())
    }

    @Test
    fun `small and gzip only responses stay raw`() = testApplication {
        application {
            installHomeClaimHttpCompression()
            routing {
                get("/small") { call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json) }
                get("/large") { call.respondText(payload, ContentType.Application.Json) }
            }
        }
        val small = client.get("/small") { header(HttpHeaders.AcceptEncoding, "br, zstd") }
        assertEquals(null, small.headers[HttpHeaders.ContentEncoding])
        val gzipOnly = client.get("/large") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals(null, gzipOnly.headers[HttpHeaders.ContentEncoding])
        assertContentEquals(payload.toByteArray(), gzipOnly.body<ByteArray>())
    }

    @Test
    fun `unacceptable identity with unsupported gzip receives 406`() = testApplication {
        application {
            installHomeClaimHttpCompression()
            routing { get("/small") { call.respondText("small", ContentType.Text.Plain) } }
        }
        val response = client.get("/small") { header(HttpHeaders.AcceptEncoding, "gzip, identity;q=0") }
        assertEquals(406, response.status.value)
    }

    @Test
    fun `content negotiated api json response can be compressed`() = testApplication {
        application {
            install(ContentNegotiation) { jackson {} }
            installHomeClaimHttpCompression()
            routing { get("/plots") { call.respond(mapOf("plots" to payload)) } }
        }
        val response = client.get("/plots") { header(HttpHeaders.AcceptEncoding, "zstd") }
        assertEquals("zstd", response.headers[HttpHeaders.ContentEncoding])
        val decoded = ZstdInputStream(ByteArrayInputStream(response.body<ByteArray>())).use { it.readBytes() }
        assertContentEquals(ObjectMapper().writeValueAsBytes(mapOf("plots" to payload)), decoded)
    }
}
