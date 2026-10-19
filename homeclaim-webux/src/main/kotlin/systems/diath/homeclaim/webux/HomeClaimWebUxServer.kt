package systems.diath.homeclaim.webux

import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.ClasspathLoader
import io.ktor.server.application.*
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.jackson.*
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.request.*
import io.ktor.websocket.*
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import systems.diath.homeclaim.api.http.AcceptEncodingNegotiation
import systems.diath.homeclaim.api.http.ContentEncodingPolicy
import java.net.URL
import java.security.MessageDigest
import java.io.StringWriter
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import systems.diath.homeclaim.core.service.AuditService
import systems.diath.homeclaim.core.service.AuditEntry
import systems.diath.homeclaim.core.service.AuditStatus
import systems.diath.homeclaim.core.policy.ActionKey
import systems.diath.homeclaim.core.policy.CorrelationId

/**
 * WebUX Server Module
 * 
 * Provides a SvelteKit-first frontend with Pebble fallback for legacy pages.
 * Handles WebSocket connections for real-time updates.
 */
class HomeClaimWebUxServer(
    private val port: Int = 8081,
    private val host: String = "127.0.0.1",
    private val allowRemote: Boolean = false,
    private val secureCookies: Boolean = false,
    private val allowedHosts: Set<String> = emptySet(),
    private val allowedOrigins: Set<String> = emptySet(),
    private val auditService: AuditService? = null,
    internal val authStore: WebUxAuthStore = WebUxAuthStore()
) {
    fun start(): ApplicationEngine {
        val loopback = host.equals("localhost", true) || host == "127.0.0.1" || host == "::1"
        require(loopback || allowRemote) { "Remote WebUX bind requires homeclaim.webux.allowRemote=true" }
        require(!(allowRemote || !loopback) || (secureCookies && allowedHosts.isNotEmpty() && allowedOrigins.isNotEmpty())) {
            "Remote WebUX requires Secure cookies, allowedHosts, and allowedOrigins"
        }
        return embeddedServer(Netty, host = host, port = port) { module() }.start(wait = false)
    }

    fun issuePairing(playerId: UUID, username: String): String = authStore.issuePairing(playerId, username)

    private val pebble: PebbleEngine = PebbleEngine.Builder()
        .loader(ClasspathLoader().apply {
            prefix = "templates"
            suffix = ".peb"
        })
        .autoEscaping(true)
        .build()

    private val wsConnections = java.util.concurrent.ConcurrentHashMap.newKeySet<WebSocketSession>()
    private val userSessions = java.util.concurrent.ConcurrentHashMap<String, MutableSet<WebSocketSession>>()
    private val json = ObjectMapper()

    /**
     * Configure Ktor application
     */
    fun Application.module() {
        install(ContentNegotiation) { jackson() }
        intercept(ApplicationCallPipeline.Call) {
            if (!validHost(call)) {
                call.respond(HttpStatusCode.Forbidden)
                finish()
                return@intercept
            }
            if (call.request.path() == "/ws") {
                if (currentSession(call) == null) {
                    call.respond(HttpStatusCode.Unauthorized)
                    finish()
                    return@intercept
                }
                if (!validOrigin(call)) {
                    call.respond(HttpStatusCode.Forbidden)
                    finish()
                    return@intercept
                }
            }
            call.response.headers.append("X-Content-Type-Options", "nosniff", safeOnly = true)
            call.response.headers.append("Referrer-Policy", "strict-origin-when-cross-origin", safeOnly = true)
            call.response.headers.append("Permissions-Policy", "camera=(), microphone=(), geolocation=()", safeOnly = true)
            call.response.headers.append("Content-Security-Policy", "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; connect-src 'self' ws: wss:; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'", safeOnly = true)
            proceed()
        }
        // WebSocket support
        install(WebSockets) {
            pingPeriod = Duration.ofSeconds(15)
            timeout = Duration.ofSeconds(15)
            maxFrameSize = 16 * 1024L
            masking = false
        }

        routing {
            get("/static/{path...}") { serveStatic(call, "static", call.parameters.getAll("path")) }
            post("/auth/pair") {
                if (!validOrigin(call)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "origin_rejected")); return@post
                }
                val request = runCatching { call.receive<PairRequest>() }.getOrNull()
                val remote = call.request.local.remoteHost
                val exchanged = request?.code?.let { authStore.consumePairing(it, remote) }
                if (exchanged == null) {
                    if (authStore.shouldAuditFailedLogin()) auditFailedLogin()
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_or_expired_code")); return@post
                }
                val (session, token) = exchanged
                auditAuthEvent(session.identity, "LOGIN_SUCCESS")
                call.response.headers.append(HttpHeaders.SetCookie, sessionCookie(token, session.expiresAt), safeOnly = false)
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(mapOf("authenticated" to true, "playerId" to session.identity.playerId.toString(), "username" to session.identity.username))
            }
            get("/auth/session") {
                val session = currentSession(call)
                call.response.header(HttpHeaders.CacheControl, "no-store")
                if (session == null) call.respond(HttpStatusCode.Unauthorized, mapOf("authenticated" to false))
                else call.respond(mapOf("authenticated" to true, "playerId" to session.identity.playerId.toString(), "username" to session.identity.username, "expiresAt" to session.expiresAt.toString(), "csrfToken" to session.csrfToken))
            }
            post("/auth/logout") {
                val session = currentSession(call)
                if (session != null && (!validOrigin(call) || !validCsrf(call, session))) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "csrf_rejected")); return@post
                }
                authStore.revoke(cookieToken(call))
                session?.identity?.let {
                    auditAuthEvent(it, "LOGOUT")
                    closeUserConnections(it.playerId.toString())
                }
                call.response.headers.append(HttpHeaders.SetCookie, clearSessionCookie(), safeOnly = false)
                call.respond(mapOf("loggedOut" to true))
            }
            get("/app") {
                if (currentSession(call) == null) { call.respondRedirect("/app/login"); return@get }
                respondSvelteApp(call)
            }
            get("/app/{path...}") {
                val path = call.parameters.getAll("path")
                val assetPath = path?.joinToString("/")
                if (assetPath == "admin" || assetPath?.startsWith("admin/") == true) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }
                if (assetPath == "login" || assetPath?.startsWith("_app/") == true && assetPath.substringAfterLast('/').contains('.')) {
                    if (assetPath == "login") respondSvelteApp(call)
                    else if (!serveSpaResourceIfPresent(call, assetPath)) call.respond(HttpStatusCode.NotFound)
                } else if (assetPath != null && assetPath.substringAfterLast('/').contains('.') && serveSpaResourceIfPresent(call, assetPath)) {
                    Unit
                } else if (currentSession(call) == null) call.respondRedirect("/app/login")
                else respondSvelteApp(call)
            }

            // WebSocket endpoint
            webSocket("/ws") {
                val session = currentSession(call)
                if (session == null || !validOrigin(call)) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication required"))
                    return@webSocket
                }
                wsConnections += this
                val userId = session.identity.playerId.toString()
                registerUserSession(userId, this)
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            if (authStore.resolve(cookieToken(call))?.id != session.id) {
                                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Session expired"))
                                break
                            }
                            handleWsMessage(frame.readText(), this)
                        }
                    }
                } catch (e: ClosedReceiveChannelException) {
                    println("[WS] Connection closed: ${closeReason.await()}")
                } finally {
                    wsConnections -= this
                    unregisterUserSession(userId, this)
                }
            }

            // Web Routes
            get("/") {
                if (hasSvelteKitBuild()) {
                    call.respondRedirect("/app")
                    return@get
                }

                renderTemplate(call, "plots-list", mapOf(
                    "title" to "HomeClaim - Alle Plots",
                    "user" to getCurrentUser(call)
                ))
            }

            get("/plots") {
                if (hasSvelteKitBuild()) {
                    call.respondRedirect("/app/plots")
                    return@get
                }

                renderTemplate(call, "plots-list", mapOf(
                    "title" to "Alle Plots",
                    "user" to getCurrentUser(call)
                ))
            }

            get("/plot/{id}") {
                val plotId = call.parameters["id"]
                if (hasSvelteKitBuild() && plotId != null) {
                    call.respondRedirect("/app/plot/$plotId")
                    return@get
                }

                renderTemplate(call, "plot-detail", mapOf(
                    "title" to "Plot Details",
                    "plotId" to plotId,
                    "user" to getCurrentUser(call)
                ))
            }

            get("/my-plots") {
                if (hasSvelteKitBuild()) {
                    call.respondRedirect("/app/my-plots")
                    return@get
                }

                renderTemplate(call, "plots-list", mapOf(
                    "title" to "Meine Plots",
                    "user" to getCurrentUser(call),
                    "breadcrumb" to listOf(
                        mapOf("label" to "Home", "url" to "/"),
                        mapOf("label" to "Meine Plots")
                    )
                ))
            }

            get("/dashboard") {
                if (hasSvelteKitBuild()) {
                    call.respondRedirect("/app")
                    return@get
                }

                renderTemplate(call, "dashboard", mapOf(
                    "title" to "Dashboard",
                    "user" to getCurrentUser(call)
                ))
            }

            get("/bookmarks") {
                if (hasSvelteKitBuild()) {
                    call.respondRedirect("/app/bookmarks")
                    return@get
                }

                renderTemplate(call, "bookmarks", mapOf(
                    "title" to "Lesezeichen",
                    "user" to getCurrentUser(call)
                ))
            }

            get("/settings") {
                if (hasSvelteKitBuild()) {
                    call.respondRedirect("/app/settings")
                    return@get
                }

                renderTemplate(call, "settings", mapOf(
                    "title" to "Einstellungen",
                    "user" to getCurrentUser(call)
                ))
            }

            get("/login") {
                if (hasSvelteKitBuild()) {
                    call.respondRedirect("/app/login")
                    return@get
                }

                renderTemplate(call, "login", mapOf(
                    "title" to "Anmelden",
                    "showSidebar" to false
                ))
            }

            get("/admin/plots") {
                call.respond(HttpStatusCode.Forbidden)
            }

            get("/admin/users") {
                call.respond(HttpStatusCode.Forbidden)
            }

            get("/admin/notifications") {
                call.respond(HttpStatusCode.Forbidden)
            }
        }
    }

    private fun hasSvelteKitBuild(): Boolean =
        javaClass.classLoader.getResource("static/spa/index.html") != null

    /**
     * Serve the built SvelteKit app if available, otherwise fall back to the legacy Pebble page.
     */
    private suspend fun respondSvelteApp(call: ApplicationCall) {
        if (javaClass.classLoader.getResource("static/spa/index.html") == null) {
            renderTemplate(call, "plots-list", mapOf(
                "title" to "HomeClaim - Alle Plots",
                "user" to getCurrentUser(call),
                "flash" to mapOf(
                    "type" to "info",
                    "icon" to "info-circle-fill",
                    "message" to "The new SvelteKit frontend is not built yet. Run npm install && npm run build in homeclaim-webux/frontend."
                )
            ))
            return
        }
        serveStatic(call, "static/spa", listOf("index.html"))
    }

    private suspend fun serveSpaResourceIfPresent(call: ApplicationCall, path: String): Boolean =
        serveStatic(call, "static/spa", listOf(path), missingIsNotFound = path.substringAfterLast('/', "").contains('.'))

    private suspend fun serveStatic(
        call: ApplicationCall,
        resourceRoot: String,
        pathParts: List<String>?,
        missingIsNotFound: Boolean = true
    ): Boolean {
        val clean = pathParts?.joinToString("/") ?: ""
        if (!isSafeResourcePath(clean)) {
            call.respond(HttpStatusCode.BadRequest)
            return true
        }

        val logicalPath = "$resourceRoot/$clean"
        val loader = javaClass.classLoader
        val raw = loader.getResource(logicalPath)
        if (raw == null) {
            if (missingIsNotFound) {
                call.respond(HttpStatusCode.NotFound)
                return true
            }
            return false
        }

        val negotiation = AcceptEncodingNegotiation.parse(call.request.headers.getAll(HttpHeaders.AcceptEncoding))
        val isText = logicalPath.substringAfterLast('.', "").lowercase() in setOf("js", "mjs", "css", "html", "json", "svg", "xml", "txt", "map")
        val representations = linkedMapOf<String, URL>()
        if (isText) {
            loader.getResource("$logicalPath.br")?.let { representations["br"] = it }
            loader.getResource("$logicalPath.zst")?.let { representations["zstd"] = it }
        }
        representations["identity"] = raw
        val encoding = negotiation.select(ContentEncodingPolicy.STATIC, representations.keys)
        if (encoding == null) {
            call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptEncoding, safeOnly = true)
            call.respond(HttpStatusCode.NotAcceptable)
            return true
        }
        respondStaticRepresentation(call, logicalPath, representations.getValue(encoding), encoding)
        return true
    }

    private suspend fun respondStaticRepresentation(call: ApplicationCall, logicalPath: String, resource: URL, encoding: String) {
        val connection = resource.openConnection().apply { useCaches = true }
        val lastModified = connection.lastModified
        val length = connection.contentLengthLong
        val weakTag = staticWeakTag(logicalPath, encoding, length, lastModified)
        call.ensureVaryAcceptEncoding()
        call.response.header(HttpHeaders.ETag, weakTag)
        call.response.header(HttpHeaders.AcceptRanges, "none")
        if (lastModified > 0) call.response.header(HttpHeaders.LastModified, DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(lastModified).atZone(java.time.ZoneOffset.UTC)))
        if (encoding != "identity") call.response.header(HttpHeaders.ContentEncoding, encoding)

        val matchingTags = call.request.headers.getAll(HttpHeaders.IfNoneMatch).orEmpty()
            .flatMap { it.split(',') }.map(String::trim)
        val notModified = matchingTags.any { it == "*" || it == weakTag || it.removePrefix("W/") == weakTag.removePrefix("W/") } ||
            (matchingTags.isEmpty() && lastModified > 0 && call.request.headers[HttpHeaders.IfModifiedSince]?.let { header ->
                runCatching { Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(header)).toEpochMilli() >= lastModified / 1000 * 1000 }.getOrDefault(false)
            } == true)
        if (notModified) {
            call.respond(HttpStatusCode.NotModified)
            return
        }

        val type = ContentType.defaultForFilePath(logicalPath)
        call.respond(object : OutgoingContent.ReadChannelContent() {
            override val contentType: ContentType = type
            override val contentLength: Long? = length.takeIf { it >= 0 }
            override fun readFrom(): ByteReadChannel = resource.openStream().toByteReadChannel(context = Dispatchers.IO)
        })
    }

    private fun isSafeResourcePath(path: String): Boolean =
        path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') && !path.contains('\u0000') &&
            path.split('/').all { it.isNotBlank() && it != "." && it != ".." }

    private fun staticWeakTag(path: String, encoding: String, length: Long, modified: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$path|$encoding|$length|$modified".toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
        return "W/\"$digest\""
    }

    private fun ApplicationCall.ensureVaryAcceptEncoding() {
        val varyValues = response.headers.values(HttpHeaders.Vary)
            .flatMap { it.split(',') }
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (varyValues.none { it.equals(HttpHeaders.AcceptEncoding, ignoreCase = true) }) {
            response.header(HttpHeaders.Vary, HttpHeaders.AcceptEncoding)
        }
    }

    /**
     * Render Pebble template
     */
    private suspend fun renderTemplate(
        call: ApplicationCall,
        templateName: String,
        context: Map<String, Any?>
    ) {
        val session = currentSession(call)
        if (session == null) {
            call.respondRedirect("/app/login")
            return
        }
        val writer = StringWriter()
        val template = pebble.getTemplate(templateName)
        
        template.evaluate(writer, context + mapOf("version" to "1.0.0-SNAPSHOT"))
        
        call.respondText(writer.toString(), io.ktor.http.ContentType.Text.Html)
    }

    /**
     * Get current user from session/JWT
     */
    private fun getCurrentUser(call: ApplicationCall): Map<String, Any>? = currentSession(call)?.identity?.let {
        mapOf("id" to it.playerId.toString(), "username" to it.username, "hasPermission" to { _: String -> false })
    }

    private fun cookieToken(call: ApplicationCall): String? = call.request.cookies[SESSION_COOKIE]

    private fun currentSession(call: ApplicationCall): WebUxAuthStore.Session? = authStore.resolve(cookieToken(call))

    private fun validHost(call: ApplicationCall): Boolean {
        val hostHeader = call.request.headers[HttpHeaders.Host] ?: return false
        val uri = runCatching { java.net.URI("http://$hostHeader") }.getOrNull() ?: return false
        if (uri.rawUserInfo != null || uri.rawPath.orEmpty().isNotEmpty() || uri.rawQuery != null || uri.rawFragment != null) return false
        val parsed = uri.host?.lowercase() ?: return false
        return if (allowedHosts.isNotEmpty()) allowedHosts.any { it.trim().lowercase() == parsed }
        else parsed in setOf("localhost", "127.0.0.1", "::1")
    }

    private fun validOrigin(call: ApplicationCall): Boolean {
        val rawOrigin = call.request.headers[HttpHeaders.Origin]?.trim() ?: return false
        val parsedOrigin = runCatching { java.net.URI(rawOrigin) }.getOrNull() ?: return false
        if (parsedOrigin.scheme !in setOf("http", "https") || parsedOrigin.host.isNullOrBlank() ||
            parsedOrigin.rawUserInfo != null || parsedOrigin.rawPath.orEmpty().isNotEmpty() ||
            parsedOrigin.rawQuery != null || parsedOrigin.rawFragment != null) return false
        if (allowedOrigins.any { configured ->
                configured.trim().trimEnd('/').equals(rawOrigin.trimEnd('/'), ignoreCase = true)
            }) return true
        if (allowRemote) return false
        val hostHeader = call.request.headers[HttpHeaders.Host]?.trim() ?: return false
        val parsedHost = runCatching { java.net.URI("http://$hostHeader") }.getOrNull() ?: return false
        val scheme = call.request.local.scheme.lowercase()
        val expectedPort = when (scheme) { "https" -> 443; else -> 80 }
        val originPort = parsedOrigin.port.takeIf { it >= 0 } ?: expectedPort
        val hostPort = parsedHost.port.takeIf { it >= 0 } ?: expectedPort
        return parsedOrigin.scheme.equals(scheme, ignoreCase = true) &&
            parsedOrigin.host.equals(parsedHost.host, ignoreCase = true) && originPort == hostPort
    }

    private fun validCsrf(call: ApplicationCall, session: WebUxAuthStore.Session): Boolean {
        val supplied = call.request.headers[CSRF_HEADER] ?: return false
        return MessageDigest.isEqual(supplied.toByteArray(), session.csrfToken.toByteArray())
    }

    private fun sessionCookie(token: String, expiresAt: Instant): String = buildString {
        append("$SESSION_COOKIE=$token; Path=/; HttpOnly; SameSite=Lax; Max-Age=${Duration.between(Instant.now(), expiresAt).seconds.coerceAtLeast(0)}")
        if (secureCookies) append("; Secure")
    }

    private fun clearSessionCookie(): String = buildString {
        append("$SESSION_COOKIE=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0")
        if (secureCookies) append("; Secure")
    }

    private fun auditAuthEvent(identity: WebUxAuthStore.Identity, action: String) {
        runCatching {
            auditService?.append(AuditEntry(
                actorId = identity.playerId,
                targetId = null,
                category = "WEB_AUTH",
                action = action,
                payload = mapOf("method" to "INGAME_PAIRING"),
                correlationId = CorrelationId(UUID.randomUUID()),
                actorKind = "PLAYER",
                actorRef = identity.playerId.toString(),
                actionKey = ActionKey("homeclaim:web_${action.lowercase()}"),
                status = AuditStatus.COMPLETED
            ))
        }.onFailure { /* Authentication remains available if the bounded audit sink is closing. */ }
    }

    private fun auditFailedLogin() {
        runCatching {
            auditService?.append(AuditEntry(
                actorId = null,
                targetId = null,
                category = "WEB_AUTH",
                action = "LOGIN_FAILED",
                payload = mapOf("method" to "INGAME_PAIRING", "reason" to "INVALID_OR_EXPIRED_CODE", "sampled" to true),
                correlationId = CorrelationId(UUID.randomUUID()),
                actorKind = "EXTERNAL",
                actorRef = null,
                actionKey = ActionKey("homeclaim:web_login_failed"),
                status = AuditStatus.FAILED
            ))
        }.onFailure { /* Authentication remains available if the bounded audit sink is closing. */ }
    }

    /**
     * Handle incoming WebSocket messages
     */
    private suspend fun handleWsMessage(message: String, session: WebSocketSession) {
        val type = runCatching { json.readTree(message).path("type").asText("") }.getOrDefault("")
        when (type) {
            "ping" -> session.send(Frame.Text("{\"type\":\"pong\"}"))
            else -> session.send(Frame.Text("{\"type\":\"error\",\"error\":\"unsupported_message\"}"))
        }
    }

    /**
     * Broadcast message to all connected WebSocket clients
     */
    suspend fun broadcast(type: String, data: Any) {
        val message = """{"type":"$type","data":$data}"""
        wsConnections.forEach { session ->
            try {
                session.send(Frame.Text(message))
            } catch (e: Exception) {
                println("[WS] Failed to send to client: ${e.message}")
            }
        }
    }

    /**
     * Broadcast plot update to all clients
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun broadcastPlotUpdate(plotId: String, plot: Any) {
        broadcast("plot_updated", plot)
    }

    /**
     * Broadcast like added event
     */
    suspend fun broadcastLikeAdded(plotId: String, userId: String) {
        broadcast("plot_like_added", mapOf("plotId" to plotId, "userId" to userId))
    }

    /**
     * Broadcast visit tracked event
     */
    suspend fun broadcastVisitTracked(plotId: String) {
        broadcast("plot_visit_tracked", mapOf("plotId" to plotId))
    }

    /**
     * Send notification to specific users (Admins/Mods)
     */
    suspend fun sendNotification(userIds: Set<String>, message: String, type: String = "info") {
        val notificationMsg = """{"type":"notification","data":{"message":"$message","type":"$type"}}"""
        userIds.forEach { userId ->
            userSessions[userId]?.forEach { session ->
                try {
                    session.send(Frame.Text(notificationMsg))
                } catch (e: Exception) {
                    println("[WS] Failed to send notification to user $userId: ${e.message}")
                }
            }
        }
    }

    /**
     * Register user session for filtering notifications
     */
    fun registerUserSession(userId: String, session: WebSocketSession) {
        userSessions.computeIfAbsent(userId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(session)
    }

    /**
     * Unregister user session on disconnect
     */
    fun unregisterUserSession(userId: String, session: WebSocketSession) {
        userSessions.computeIfPresent(userId) { _, sessions ->
            sessions.remove(session)
            sessions.takeIf { it.isNotEmpty() }
        }
    }

    private suspend fun closeUserConnections(userId: String) {
        userSessions[userId]?.toList()?.forEach { socket ->
            runCatching { socket.close(CloseReason(CloseReason.Codes.NORMAL, "Logged out")) }
        }
    }

    data class PairRequest(val code: String)

    companion object {
        const val SESSION_COOKIE = "hc_session"
        const val CSRF_HEADER = "X-CSRF-Token"
    }
}
