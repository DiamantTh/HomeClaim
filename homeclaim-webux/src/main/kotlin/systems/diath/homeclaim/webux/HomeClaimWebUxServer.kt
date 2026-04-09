package systems.diath.homeclaim.webux

import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.ClasspathLoader
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import java.io.StringWriter
import java.time.Duration

/**
 * WebUX Server Module
 * 
 * Provides web frontend with Pebble templates, Bootstrap 5, and Alpine.js.
 * Handles WebSocket connections for real-time updates.
 */
class HomeClaimWebUxServer(
    private val port: Int = 8081,
    private val host: String = "0.0.0.0"
) {
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
            // Static assets
            staticResources("/static", "static")

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
                renderTemplate(call, "plots-list", mapOf(
                    "title" to "HomeClaim - Alle Plots",
                    "user" to getCurrentUser(call)
                ))
            }

            get("/plots") {
                renderTemplate(call, "plots-list", mapOf(
                    "title" to "Alle Plots",
                    "user" to getCurrentUser(call)
                ))
            }

            get("/plot/{id}") {
                val plotId = call.parameters["id"]
                renderTemplate(call, "plot-detail", mapOf(
                    "title" to "Plot Details",
                    "plotId" to plotId,
                    "user" to getCurrentUser(call)
                ))
            }

            get("/my-plots") {
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
                renderTemplate(call, "dashboard", mapOf(
                    "title" to "Dashboard",
                    "user" to getCurrentUser(call)
                ))
            }

            get("/bookmarks") {
                renderTemplate(call, "bookmarks", mapOf(
                    "title" to "Lesezeichen",
                    "user" to getCurrentUser(call)
                ))
            }

            get("/settings") {
                renderTemplate(call, "settings", mapOf(
                    "title" to "Einstellungen",
                    "user" to getCurrentUser(call)
                ))
            }

            get("/login") {
                renderTemplate(call, "login", mapOf(
                    "title" to "Anmelden",
                    "showSidebar" to false
                ))
            }

            get("/admin/plots") {
                renderTemplate(call, "admin-plots", mapOf(
                    "title" to "Admin - Plots",
                    "user" to getCurrentUser(call)
                ))
            }

            get("/admin/users") {
                renderTemplate(call, "admin-users", mapOf(
                    "title" to "Admin - Benutzer",
                    "user" to getCurrentUser(call)
                ))
            }

            get("/admin/notifications") {
                renderTemplate(call, "admin-notifications", mapOf(
                    "title" to "Admin - Benachrichtigungen",
                    "user" to getCurrentUser(call)
                ))
            }
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
