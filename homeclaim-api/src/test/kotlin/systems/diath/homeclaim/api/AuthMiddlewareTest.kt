package systems.diath.homeclaim.api

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ktor.serialization.jackson.jackson
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation

class AuthMiddlewareTest {
    @Test
    fun `admin token stays required and valid token is accepted`() = testApplication {
        val auth = AuthMiddleware("a sufficiently long test secret", RateLimiter(60), emptySet(), true, emptySet())
        application {
            install(ContentNegotiation) { jackson() }
            routing {
                get("/resource") {
                    if (auth.check(call)) call.respond("accepted")
                }
            }
        }
        assertEquals(401, client.get("/resource") { header(HttpHeaders.Host, "localhost") }.status.value)
        assertEquals(401, client.get("/resource") { header(HttpHeaders.Host, "localhost"); header("X-Admin-Token", "wrong") }.status.value)
        assertEquals(200, client.get("/resource") { header(HttpHeaders.Host, "localhost"); header("X-Admin-Token", "a sufficiently long test secret") }.status.value)
    }

    @Test
    fun `source rate limit counts failed token attempts`() = testApplication {
        val auth = AuthMiddleware("secret", RateLimiter(1), emptySet(), true, emptySet())
        application {
            install(ContentNegotiation) { jackson() }
            routing {
                get("/resource") {
                    if (auth.check(call)) call.respond("accepted")
                }
            }
        }
        assertEquals(401, client.get("/resource") { header(HttpHeaders.Host, "localhost"); header("X-Admin-Token", "wrong") }.status.value)
        assertEquals(429, client.get("/resource") { header(HttpHeaders.Host, "localhost"); header("X-Admin-Token", "secret") }.status.value)
    }

    @Test
    fun `host header allowlist rejects unlisted host`() = testApplication {
        val auth = AuthMiddleware("secret", RateLimiter(10), emptySet(), true, setOf("home.example"))
        application {
            install(ContentNegotiation) { jackson() }
            routing {
                get("/resource") {
                    if (auth.check(call)) call.respond("accepted")
                }
            }
        }
        assertEquals(403, client.get("/resource") {
            header(HttpHeaders.Host, "attacker.example")
            header("X-Admin-Token", "secret")
        }.status.value)
        assertEquals(403, client.get("/resource") {
            header(HttpHeaders.Host, "attacker@home.example")
            header("X-Admin-Token", "secret")
        }.status.value)
    }
}
