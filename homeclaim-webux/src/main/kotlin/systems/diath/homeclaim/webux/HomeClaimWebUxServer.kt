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

/**
 * WebUX Server Module
 * 
 * Provides a SvelteKit-first frontend with Pebble fallback for legacy pages.
 * Handles WebSocket connections for real-time updates.
 */
class HomeClaimWebUxServer(
    private val port: Int = 8081,
    private val host: String = "0.0.0.0"
) {
    fun start(): ApplicationEngine = embeddedServer(Netty, host = host, port = port) {
        module()
    }.start(wait = false)

    private val pebble: PebbleEngine = PebbleEngine.Builder()
        .loader(ClasspathLoader().apply {
            prefix = "templates"
            suffix = ".peb"
        })
        .autoEscaping(true)
        .build()

    private val wsConnections = mutableSetOf<WebSocketSession>()
    private val userSessions = mutableMapOf<String, MutableSet<WebSocketSession>>()

    /**
     * Configure Ktor application
     */
    fun Application.module() {
        // WebSocket support
        install(WebSockets) {
            pingPeriod = Duration.ofSeconds(15)
            timeout = Duration.ofSeconds(15)
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }

        routing {
            get("/static/{path...}") { serveStatic(call, "static", call.parameters.getAll("path")) }
            get("/app") { respondSvelteApp(call) }
            get("/app/{path...}") {
                val path = call.parameters.getAll("path")
                val assetPath = path?.joinToString("/")
                if (assetPath == null || !serveSpaResourceIfPresent(call, assetPath)) respondSvelteApp(call)
            }

            // WebSocket endpoint
            webSocket("/ws") {
                wsConnections += this
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val message = frame.readText()
                            // Handle incoming WebSocket messages
                            handleWsMessage(message)
                        }
                    }
                } catch (e: ClosedReceiveChannelException) {
                    println("[WS] Connection closed: ${closeReason.await()}")
                } finally {
                    wsConnections -= this
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
                if (hasSvelteKitBuild()) {
                    call.respondRedirect("/app/admin/plots")
                    return@get
                }

                renderTemplate(call, "admin-plots", mapOf(
                    "title" to "Admin - Plots",
                    "user" to getCurrentUser(call)
                ))
            }

            get("/admin/users") {
                if (hasSvelteKitBuild()) {
                    call.respondRedirect("/app/admin/users")
                    return@get
                }

                renderTemplate(call, "admin-users", mapOf(
                    "title" to "Admin - Benutzer",
                    "user" to getCurrentUser(call)
                ))
            }

            get("/admin/notifications") {
                if (hasSvelteKitBuild()) {
                    call.respondRedirect("/app/admin/notifications")
                    return@get
                }

                renderTemplate(call, "admin-notifications", mapOf(
                    "title" to "Admin - Benachrichtigungen",
                    "user" to getCurrentUser(call)
                ))
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
        val writer = StringWriter()
        val template = pebble.getTemplate(templateName)
        
        template.evaluate(writer, context + mapOf(
            "version" to "1.0.0-SNAPSHOT"
        ))
        
        call.respondText(writer.toString(), io.ktor.http.ContentType.Text.Html)
    }

    /**
     * Get current user from session/JWT
     */
    private fun getCurrentUser(call: ApplicationCall): Map<String, Any>? {
        // Extract Bearer token from Authorization header
        val authHeader = call.request.headers["Authorization"] ?: return null
        if (!authHeader.startsWith("Bearer ", ignoreCase = true)) return null
        
        val token = authHeader.substring(7)
        
        // TODO: In production, inject AccountService and validate token
        // For now, return authenticated user data
        // Example: val session = accountService.validateToken(token) ?: return null
        // return mapOf("id" to session.accountId, "username" to session.userName, ...)
        
        return mapOf(
            "id" to "550e8400-e29b-41d4-a716-446655440000",
            "username" to "TestUser",
            "avatarUrl" to "https://api.dicebear.com/7.x/avataaars/svg?seed=TestUser",
            "hasPermission" to { _: String -> false }
        )
    }

    /**
     * Handle incoming WebSocket messages
     */
    private suspend fun handleWsMessage(message: String) {
        try {
            // Simple JSON parsing for message routing
            val msgStart = message.indexOf("\"type\"") + 8
            val msgEnd = message.indexOf("\"", msgStart)
            if (msgStart < 8 || msgEnd < 0) return
            
            val messageType = message.substring(msgStart, msgEnd)
            
            when (messageType) {
                "ping" -> {
                    // Keep-alive message
                    println("[WS] Ping received")
                }
                "plot_command" -> {
                    // TODO: Parse plotId and command, route to PlotService
                    println("[WS] Plot command: $message")
                }
                "notification_ack" -> {
                    // TODO: Handle notification acknowledgment
                    println("[WS] Notification ack: $message")
                }
                else -> {
                    println("[WS] Unknown message type: $messageType")
                }
            }
        } catch (e: Exception) {
            println("[WS] Error handling message: ${e.message}")
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
        userSessions.computeIfAbsent(userId) { mutableSetOf() }.add(session)
    }

    /**
     * Unregister user session on disconnect
     */
    fun unregisterUserSession(userId: String, session: WebSocketSession) {
        userSessions[userId]?.remove(session)
        if (userSessions[userId]?.isEmpty() == true) {
            userSessions.remove(userId)
        }
    }
}
