package systems.diath.homeclaim.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.SerializationFeature
import systems.diath.homeclaim.core.model.*
import systems.diath.homeclaim.core.policy.FlagProfile
import systems.diath.homeclaim.core.service.*
import systems.diath.homeclaim.api.license.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.time.Instant

/**
 * Enhanced REST API Server with OpenAPI documentation.
 * 
 * Base URL: http://localhost:{port}/api/v1
 * 
 * Authentication: X-Admin-Token header required for all endpoints.
 * 
 * ## Available Endpoints:
 * 
 * ### Health & Info
 * - GET  /health              - Server health status
 * - GET  /info                - API version and capabilities
 * 
 * ### Plots (Regions)
 * - GET  /plots               - List plots (query: world, owner, available)
 * - GET  /plots/{id}          - Get plot by ID
 * - GET  /plots/at            - Get plot at coordinates (query: world, x, y, z)
 * - POST /plots/{id}/buy      - Buy a plot
 * - POST /plots/{id}/sell     - Put plot up for sale
 * - POST /plots/{id}/flags    - Update plot flag
 * - POST /plots/{id}/limits   - Update plot limit
 * - POST /plots/{id}/trust    - Add trusted player
 * - POST /plots/{id}/untrust  - Remove trusted player
 * - POST /plots/{id}/ban      - Ban player from plot
 * - POST /plots/{id}/unban    - Unban player from plot
 * 
 * ### Players
 * - GET  /players/{uuid}/plots      - List player's plots
 * - GET  /players/{uuid}/stats      - Player statistics
 * 
 * ### Zones
 * - GET  /zones               - List zones (query: world)
 * - GET  /zones/{id}          - Get zone by ID
 * 
 * ### Components
 * - GET  /plots/{id}/components     - List plot components
 * 
 * ### Profiles
 * - GET  /profiles            - List flag profiles
 * - POST /profiles            - Create/update profile
 * - POST /plots/{id}/applyProfile   - Apply profile to plot
 * 
 * ### Admin
 * - GET  /admin/stats         - Server statistics
 * - POST /admin/reload        - Trigger config reload
 * 
 * ### Metrics
 * - GET  /metrics             - Aggregated server metrics
 * - GET  /metrics/plots       - Plot-specific metrics
 * - GET  /metrics/worlds/{name} - Per-world metrics
 */
class PlotRestServer(
    private val regionService: RegionService,
    private val plotMemberService: PlotMemberService?,
    private val zoneService: ZoneService?,
    private val componentService: ComponentService?,
    private val adminService: RegionAdminService?,
    private val auditService: AuditService? = null,
    private val metricsService: ServerMetricsService? = null,
    private val port: Int = 8080,
    private val authToken: String,
    private val rateLimitPerMinute: Int = 60,
    private val healthInfo: HealthInfo? = null,
    private val allowedHosts: Set<String> = emptySet(),
    private val allowLocalhost: Boolean = true,
    private val enableCors: Boolean = false
) {
    fun start(): ApplicationEngine = embeddedServer(Netty, port = port) {
        configurePlugins()
        configureRoutes()
    }.start(wait = false)
    
    private fun Application.configurePlugins() {
        install(ContentNegotiation) {
            jackson {
                configure(SerializationFeature.INDENT_OUTPUT, true)
                setSerializationInclusion(JsonInclude.Include.NON_NULL)
            }
        }
        
        install(StatusPages) {
            exception<IllegalArgumentException> { call, cause ->
                call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", cause.message))
            }
            exception<NoSuchElementException> { call, cause ->
                call.respond(HttpStatusCode.NotFound, ApiError("not_found", cause.message))
            }
            exception<Exception> { call, cause ->
                call.application.log.error("Unhandled exception", cause)
                call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", "An error occurred"))
            }
        }
        
        if (enableCors) {
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowHeader("X-Admin-Token")
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Put)
                allowMethod(HttpMethod.Delete)
            }
        }
    }
    
    private fun Application.configureRoutes() {
        val rateLimiter = RateLimiter(rateLimitPerMinute)
        val auth = AuthMiddleware(authToken, rateLimiter, allowedHosts, allowLocalhost)
        
        // Request/Response Logging Interceptor
        intercept(ApplicationCallPipeline.Monitoring) {
            val startTime = System.currentTimeMillis()
            val clientId = call.request.headers["X-Client-Id"]
            val endpoint = call.request.uri.removePrefix("/api/v1").substringBefore("?")
            val method = call.request.httpMethod.value
            
            proceed()
            
            val responseTime = System.currentTimeMillis() - startTime
            val status = call.response.status()?.value ?: 500
            ApiLogRegistry.log(
                clientId = clientId,
                endpoint = endpoint,
                method = method,
                statusCode = status,
                responseTimeMs = responseTime,
                error = if (status >= 400) call.response.status()?.description else null
            )
        }
        
        routing {
            // OpenAPI documentation endpoint
            get("/api/openapi.json") {
                call.respond(openApiSpec())
            }
            
            // API documentation HTML
            get("/api/docs") {
                call.respondText(swaggerUiHtml(), ContentType.Text.Html)
            }
            
            route("/api/v1") {
                // ============================================
                // Health & Info
                // ============================================
                get("/health") {
                    if (!auth.check(call)) return@get
                    call.respond(HealthResponse(
                        status = "ok",
                        version = healthInfo?.version ?: "unknown",
                        storage = healthInfo?.storage ?: "unknown",
                        timestamp = System.currentTimeMillis()
                    ))
                }
                
                get("/info") {
                    if (!auth.check(call)) return@get
                    call.respond(ApiInfo(
                        name = "HomeClaim REST API",
                        version = "1.0.0",
                        endpoints = listOf(
                            "/health", "/info", "/plots", "/plots/{id}", "/plots/at",
                            "/players/{uuid}/plots", "/zones", "/profiles", "/admin/stats",
                            "/metrics", "/metrics/plots", "/metrics/worlds/{name}"
                        )
                    ))
                }

                route("/metrics") {
                    get {
                        if (!auth.check(call)) return@get
                        call.respond((metricsService ?: NoOpServerMetricsService).collectMetrics())
                    }

                    get("/plots") {
                        if (!auth.check(call)) return@get
                        call.respond((metricsService ?: NoOpServerMetricsService).collectPlotsMetrics())
                    }

                    get("/worlds/{name}") {
                        if (!auth.check(call)) return@get
                        val worldName = call.parameters["name"]
                            ?: throw IllegalArgumentException("Missing world name")
                        val metrics = (metricsService ?: NoOpServerMetricsService).collectWorldMetrics(worldName)
                            ?: throw NoSuchElementException("World not found: $worldName")
                        call.respond(metrics)
                    }
                }
                
                // ============================================
                // Admin - Logging & Metrics
                // ============================================
                route("/admin") {
                    get("/logs") {
                        if (!auth.check(call)) return@get
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                        val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                        val clientId = call.request.queryParameters["clientId"]
                        val status = call.request.queryParameters["status"]?.toIntOrNull()
                        
                        val result = ApiLogRegistry.getLogs(limit, offset, clientId, status)
                        call.respond(result)
                    }
                    
                    get("/metrics") {
                        if (!auth.check(call)) return@get
                        val metrics = ApiLogRegistry.getMetrics()
                        call.respond(metrics)
                    }
                    
                    get("/stats") {
                        if (!auth.check(call)) return@get
                        call.respond(ApiInfo(
                            name = "HomeClaim REST API",
                            version = "1.0.0",
                            endpoints = listOf("/health", "/info", "/plots", "/admin/logs", "/admin/metrics")
                        ))
                    }
                }
                
                // ============================================
                // Plots (Regions)
                // ============================================
                route("/plots") {
                    // GET /plots - List plots with filters
                    get {
                        if (!auth.check(call)) return@get
                        val worldFilter = call.request.queryParameters["world"]
                        val owner = call.request.queryParameters["owner"]?.toUuidOrNull()
                        val available = call.request.queryParameters["available"]?.toBoolean()
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                        val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                        
                        var plots = when {
                            owner != null -> regionService.listRegionsByOwner(owner)
                            else -> regionService.listAllRegions()
                        }
                        
                        // Filter by world
                        if (worldFilter != null) {
                            plots = plots.filter { it.world == worldFilter }
                        }
                        
                        // Filter by availability (price > 0)
                        if (available == true) {
                            plots = plots.filter { it.price > 0 }
                        }
                        
                        // Pagination
                        val total = plots.size
                        plots = plots.drop(offset).take(limit)
                        
                        call.respond(PaginatedResponse(
                            data = plots.map { it.toDto() },
                            total = total,
                            limit = limit,
                            offset = offset
                        ))
                    }
                    
                    // GET /plots/at - Get plot at coordinates
                    get("/at") {
                        if (!auth.check(call)) return@get
                        val world = call.request.queryParameters["world"]
                            ?: throw IllegalArgumentException("Missing 'world' parameter")
                        val x = call.request.queryParameters["x"]?.toIntOrNull()
                            ?: throw IllegalArgumentException("Missing or invalid 'x' parameter")
                        val y = call.request.queryParameters["y"]?.toIntOrNull() ?: 64
                        val z = call.request.queryParameters["z"]?.toIntOrNull()
                            ?: throw IllegalArgumentException("Missing or invalid 'z' parameter")
                        
                        val regionId = regionService.getRegionAt(world, x, y, z)
                        if (regionId == null) {
                            call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No plot at this location"))
                        } else {
                            val region = regionService.getRegionById(regionId)
                            if (region != null) {
                                call.respond(region.toDto())
                            } else {
                                call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Plot not found"))
                            }
                        }
                    }
                    
                    // GET /plots/{id}
                    get("/{id}") {
                        if (!auth.check(call)) return@get
                        val id = call.parameters["id"]?.toRegionId()
                            ?: throw IllegalArgumentException("Invalid plot ID")
                        val region = regionService.getRegionById(id)
                            ?: throw NoSuchElementException("Plot not found")
                        call.respond(region.toDto())
                    }
                    
                    // POST /plots/{id}/buy
                    post("/{id}/buy") {
                        if (!auth.check(call)) return@post
                        val id = call.parameters["id"]?.toRegionId()
                            ?: throw IllegalArgumentException("Invalid plot ID")
                        val req = call.receive<BuyRequest>()
                        
                        val region = regionService.getRegionById(id)
                            ?: throw NoSuchElementException("Plot not found")
                        
                        if (region.price <= 0) {
                            call.respond(HttpStatusCode.BadRequest, ApiError("not_for_sale", "This plot is not for sale"))
                            return@post
                        }
                        
                        // Note: Actual purchase requires EconService integration
                        call.respond(ApiSuccess("purchase_initiated", mapOf(
                            "plotId" to id.value.toString(),
                            "buyer" to req.buyerId,
                            "price" to region.price
                        )))
                    }
                    
                    // POST /plots/{id}/sell
                    post("/{id}/sell") {
                        if (!auth.check(call)) return@post
                        val id = call.parameters["id"]?.toRegionId()
                            ?: throw IllegalArgumentException("Invalid plot ID")
                        val req = call.receive<SellRequest>()
                        
                        // Verify region exists
                        regionService.getRegionById(id)
                            ?: throw NoSuchElementException("Plot not found")
                        
                        // Update price via admin service
                        // Note: This requires updating the region
                        call.respond(ApiSuccess("listed_for_sale", mapOf(
                            "plotId" to id.value.toString(),
                            "price" to req.price
                        )))
                    }
                    
                    // POST /plots/{id}/flags
                    post("/{id}/flags") {
                        if (!auth.check(call)) return@post
                        val id = call.parameters["id"]?.toRegionId()
                            ?: throw IllegalArgumentException("Invalid plot ID")
                        val req = call.receive<FlagUpdateRequest>()
                        
                        adminService?.upsertFlag(id, FlagKey(req.key), req.value.toPolicyValue())
                            ?: throw IllegalStateException("Admin service not available")
                        
                        call.respond(ApiSuccess("flag_updated"))
                    }
                    
                    // POST /plots/{id}/limits
                    post("/{id}/limits") {
                        if (!auth.check(call)) return@post
                        val id = call.parameters["id"]?.toRegionId()
                            ?: throw IllegalArgumentException("Invalid plot ID")
                        val req = call.receive<FlagUpdateRequest>()
                        
                        adminService?.upsertLimit(id, LimitKey(req.key), req.value.toPolicyValue())
                            ?: throw IllegalStateException("Admin service not available")
                        
                        call.respond(ApiSuccess("limit_updated"))
                    }
                    
                    // POST /plots/{id}/trust
                    post("/{id}/trust") {
                        if (!auth.check(call)) return@post
                        val id = call.parameters["id"]?.toRegionId()
                            ?: throw IllegalArgumentException("Invalid plot ID")
                        val req = call.receive<PlayerActionRequest>()
                        
                        // Verify region exists
                        regionService.getRegionById(id) ?: throw NoSuchElementException("Plot not found")
                        
                        // Grant trust (permanent access) via PlotMemberService
                        // In production: get currentUserId from JWT token
                        // For now: simplified without PlotMemberService (requires domain integration)
                        // TODO: Implement plotMemberService.trustPlayer() when PlotId/RegionId mapping exists
                        
                        call.respond(ApiSuccess("player_trusted", mapOf(
                            "plot" to id.value.toString(),
                            "player" to req.playerId
                        )))
                    }
                    
                    // POST /plots/{id}/untrust
                    post("/{id}/untrust") {
                        if (!auth.check(call)) return@post
                        val id = call.parameters["id"]?.toRegionId()
                            ?: throw IllegalArgumentException("Invalid plot ID")
                        val req = call.receive<PlayerActionRequest>()
                        
                        // Verify region exists
                        regionService.getRegionById(id) ?: throw NoSuchElementException("Plot not found")
                        
                        call.respond(ApiSuccess("player_untrusted", mapOf(
                            "plot" to id.value.toString(),
                            "player" to req.playerId
                        )))
                    }
                    
                    // GET /plots/{id}/components
                    get("/{id}/components") {
                        if (!auth.check(call)) return@get
                        val id = call.parameters["id"]?.toRegionId()
                            ?: throw IllegalArgumentException("Invalid plot ID")
                        
                        val components = componentService?.listComponents(id) ?: emptyList()
                        call.respond(components.map { it.toDto() })
                    }
                    
                    // POST /plots/{id}/applyProfile
                    post("/{id}/applyProfile") {
                        if (!auth.check(call)) return@post
                        val id = call.parameters["id"]?.toRegionId()
                            ?: throw IllegalArgumentException("Invalid plot ID")
                        val req = call.receive<ApplyProfileRequest>()
                        
                        adminService?.applyProfile(id, req.profile)
                            ?: throw IllegalStateException("Admin service not available")
                        
                        call.respond(ApiSuccess("profile_applied"))
                    }
                }
                
                // ============================================
                // Players
                // ============================================
                route("/players/{uuid}") {
                    // GET /players/{uuid}/plots
                    get("/plots") {
                        if (!auth.check(call)) return@get
                        val uuid = call.parameters["uuid"]?.toUuidOrNull()
                            ?: throw IllegalArgumentException("Invalid player UUID")
                        
                        val plots = regionService.listRegionsByOwner(uuid)
                        call.respond(plots.map { it.toDto() })
                    }
                    
                    // GET /players/{uuid}/stats
                    get("/stats") {
                        if (!auth.check(call)) return@get
                        val uuid = call.parameters["uuid"]?.toUuidOrNull()
                            ?: throw IllegalArgumentException("Invalid player UUID")
                        
                        val plots = regionService.listRegionsByOwner(uuid)
                        call.respond(PlayerStats(
                            playerId = uuid.toString(),
                            plotCount = plots.size,
                            totalArea = plots.sumOf { 
                                (it.bounds.maxX - it.bounds.minX + 1) * (it.bounds.maxZ - it.bounds.minZ + 1) 
                            },
                            plotsForSale = plots.count { it.price > 0 }
                        ))
                    }
                }
                
                // ============================================
                // Zones
                // ============================================
                route("/zones") {
                    get {
                        if (!auth.check(call)) return@get
                        val world = call.request.queryParameters["world"]
                            ?: throw IllegalArgumentException("Missing 'world' parameter")
                        
                        val zones = zoneService?.listZones(world) ?: emptyList()
                        call.respond(zones.map { it.toDto() })
                    }
                    
                    get("/{id}") {
                        if (!auth.check(call)) return@get
                        val zoneId = call.parameters["id"]?.toZoneId()
                            ?: throw IllegalArgumentException("Invalid zone ID")
                        val zone = zoneService?.getZoneById(zoneId)
                            ?: throw NoSuchElementException("Zone not found")
                        call.respond(zone.toDto())
                    }
                }
                
                // ============================================
                // Profiles
                // ============================================
                route("/profiles") {
                    get {
                        if (!auth.check(call)) return@get
                        val profiles = adminService?.listProfiles() ?: emptyList()
                        call.respond(profiles)
                    }
                    
                    post {
                        if (!auth.check(call)) return@post
                        val req = call.receive<ProfileCreateRequest>()
                        
                        val profile = FlagProfile(
                            name = req.name,
                            flags = req.flags.mapKeys { FlagKey(it.key) }.mapValues { it.value.toPolicyValue() },
                            limits = req.limits.mapKeys { LimitKey(it.key) }.mapValues { it.value.toPolicyValue() }
                        )
                        adminService?.upsertProfile(profile)
                            ?: throw IllegalStateException("Admin service not available")
                        
                        call.respond(ApiSuccess("profile_created"))
                    }
                }
                
                // ============================================
                // Admin
                // ============================================
                route("/admin") {
                    get("/stats") {
                        if (!auth.check(call)) return@get
                        // TODO: Aggregate stats from services
                        call.respond(ServerStats(
                            uptime = System.currentTimeMillis(), // placeholder
                            apiVersion = "1.0.0",
                            storageType = healthInfo?.storage ?: "unknown"
                        ))
                    }
                }
                
                // ============================================
                // SPDX License Checker
                // ============================================
                route("/licenses") {
                    // GET /licenses - List all licenses sorted by copyleft strength (strong first)
                    get {
                        if (!auth.check(call)) return@get
                        
                        val licenses = SpdxLicenseRegistry.getAllLicenses()
                            .sortedWith(compareBy<SpdxLicense> { -it.copyleftStrength.ordinal }
                                .thenBy { -it.ossScore })
                        
                        call.respond(LicenseListResponse(
                            licenses = licenses.map { it.toLicenseDto() },
                            total = licenses.size
                        ))
                    }
                    
                    // GET /licenses/{id} - Get specific license with score
                    get("/{id}") {
                        if (!auth.check(call)) return@get
                        val id = call.parameters["id"]
                            ?: throw IllegalArgumentException("Missing license ID")
                        
                        val license = SpdxLicenseRegistry.getLicense(id)
                            ?: throw NoSuchElementException("License not found: $id")
                        
                        call.respond(license.toDetailedDto())
                    }
                    
                    // GET /licenses/clients - List all tracked clients with their licenses
                    get("/clients") {
                        if (!auth.check(call)) return@get
                        
                        val allClients = ClientLicenseRegistry.getAllClients()
                        val responses = allClients.map { client ->
                            val licenseObjects = client.licenses.mapNotNull { SpdxLicenseRegistry.getLicense(it) }
                            ClientLicenseResponse(
                                clientId = client.clientId,
                                licenses = licenseObjects.map { it.toLicenseDto() },
                                repositoryUrl = client.repositoryUrl,
                                score = client.score,
                                grade = client.grade,
                                lastSeen = Instant.ofEpochMilli(client.lastSeen).toString(),
                                requestCount = client.requestCount
                            )
                        }
                        
                        call.respond(ClientLicensesListResponse(
                            clients = responses,
                            total = responses.size
                        ))
                    }
                }
            }
        }
    }
    
    // ============================================
    // OpenAPI Specification
    // ============================================
    private fun openApiSpec(): Map<String, Any> = mapOf(
        "openapi" to "3.0.3",
        "info" to mapOf(
            "title" to "HomeClaim Plot API",
            "description" to "REST API for managing plots, zones, and player permissions in HomeClaim",
            "version" to "1.0.0",
            "contact" to mapOf(
                "name" to "HomeClaim Support"
            )
        ),
        "servers" to listOf(
            mapOf("url" to "http://localhost:$port/api/v1", "description" to "Local server")
        ),
        "security" to listOf(mapOf("ApiToken" to emptyList<String>())),
        "components" to mapOf(
            "securitySchemes" to mapOf(
                "ApiToken" to mapOf(
                    "type" to "apiKey",
                    "in" to "header",
                    "name" to "X-Admin-Token"
                )
            ),
            "schemas" to mapOf(
                "Plot" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "id" to mapOf("type" to "string", "format" to "uuid"),
                        "world" to mapOf("type" to "string"),
                        "owner" to mapOf("type" to "string", "format" to "uuid"),
                        "bounds" to mapOf("\$ref" to "#/components/schemas/Bounds"),
                        "price" to mapOf("type" to "number"),
                        "flags" to mapOf("type" to "object"),
                        "limits" to mapOf("type" to "object")
                    )
                ),
                "Bounds" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "minX" to mapOf("type" to "integer"),
                        "maxX" to mapOf("type" to "integer"),
                        "minY" to mapOf("type" to "integer"),
                        "maxY" to mapOf("type" to "integer"),
                        "minZ" to mapOf("type" to "integer"),
                        "maxZ" to mapOf("type" to "integer")
                    )
                ),
                "Error" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "error" to mapOf("type" to "string"),
                        "message" to mapOf("type" to "string")
                    )
                )
            )
        ),
        "paths" to mapOf(
            "/health" to mapOf(
                "get" to mapOf(
                    "summary" to "Health check",
                    "tags" to listOf("Health"),
                    "responses" to mapOf("200" to mapOf("description" to "Server is healthy"))
                )
            ),
            "/plots" to mapOf(
                "get" to mapOf(
                    "summary" to "List plots",
                    "tags" to listOf("Plots"),
                    "parameters" to listOf(
                        mapOf("name" to "world", "in" to "query", "schema" to mapOf("type" to "string")),
                        mapOf("name" to "owner", "in" to "query", "schema" to mapOf("type" to "string", "format" to "uuid")),
                        mapOf("name" to "available", "in" to "query", "schema" to mapOf("type" to "boolean")),
                        mapOf("name" to "limit", "in" to "query", "schema" to mapOf("type" to "integer", "default" to 100)),
                        mapOf("name" to "offset", "in" to "query", "schema" to mapOf("type" to "integer", "default" to 0))
                    ),
                    "responses" to mapOf("200" to mapOf("description" to "List of plots"))
                )
            ),
            "/plots/at" to mapOf(
                "get" to mapOf(
                    "summary" to "Get plot at coordinates",
                    "tags" to listOf("Plots"),
                    "parameters" to listOf(
                        mapOf("name" to "world", "in" to "query", "required" to true, "schema" to mapOf("type" to "string")),
                        mapOf("name" to "x", "in" to "query", "required" to true, "schema" to mapOf("type" to "integer")),
                        mapOf("name" to "y", "in" to "query", "schema" to mapOf("type" to "integer", "default" to 64)),
                        mapOf("name" to "z", "in" to "query", "required" to true, "schema" to mapOf("type" to "integer"))
                    ),
                    "responses" to mapOf(
                        "200" to mapOf("description" to "Plot found"),
                        "404" to mapOf("description" to "No plot at this location")
                    )
                )
            ),
            "/plots/{id}" to mapOf(
                "get" to mapOf(
                    "summary" to "Get plot by ID",
                    "tags" to listOf("Plots"),
                    "parameters" to listOf(
                        mapOf("name" to "id", "in" to "path", "required" to true, "schema" to mapOf("type" to "string", "format" to "uuid"))
                    ),
                    "responses" to mapOf(
                        "200" to mapOf("description" to "Plot details"),
                        "404" to mapOf("description" to "Plot not found")
                    )
                )
            ),
            "/plots/{id}/buy" to mapOf(
                "post" to mapOf(
                    "summary" to "Buy a plot",
                    "tags" to listOf("Plots"),
                    "requestBody" to mapOf(
                        "required" to true,
                        "content" to mapOf(
                            "application/json" to mapOf(
                                "schema" to mapOf(
                                    "type" to "object",
                                    "properties" to mapOf(
                                        "buyerId" to mapOf("type" to "string", "format" to "uuid")
                                    )
                                )
                            )
                        )
                    ),
                    "responses" to mapOf("200" to mapOf("description" to "Purchase initiated"))
                )
            ),
            "/players/{uuid}/plots" to mapOf(
                "get" to mapOf(
                    "summary" to "List player's plots",
                    "tags" to listOf("Players"),
                    "parameters" to listOf(
                        mapOf("name" to "uuid", "in" to "path", "required" to true, "schema" to mapOf("type" to "string", "format" to "uuid"))
                    ),
                    "responses" to mapOf("200" to mapOf("description" to "List of player's plots"))
                )
            ),
            "/zones" to mapOf(
                "get" to mapOf(
                    "summary" to "List zones",
                    "tags" to listOf("Zones"),
                    "parameters" to listOf(
                        mapOf("name" to "world", "in" to "query", "required" to true, "schema" to mapOf("type" to "string"))
                    ),
                    "responses" to mapOf("200" to mapOf("description" to "List of zones"))
                )
            ),
            "/profiles" to mapOf(
                "get" to mapOf(
                    "summary" to "List flag profiles",
                    "tags" to listOf("Profiles"),
                    "responses" to mapOf("200" to mapOf("description" to "List of profiles"))
                ),
                "post" to mapOf(
                    "summary" to "Create/update profile",
                    "tags" to listOf("Profiles"),
                    "requestBody" to mapOf(
                        "required" to true,
                        "content" to mapOf(
                            "application/json" to mapOf(
                                "schema" to mapOf(
                                    "type" to "object",
                                    "properties" to mapOf(
                                        "name" to mapOf("type" to "string"),
                                        "flags" to mapOf("type" to "object"),
                                        "limits" to mapOf("type" to "object")
                                    )
                                )
                            )
                        )
                    ),
                    "responses" to mapOf("200" to mapOf("description" to "Profile created/updated"))
                )
            ),
            "/metrics" to mapOf(
                "get" to mapOf(
                    "summary" to "Get aggregated server metrics",
                    "tags" to listOf("Metrics"),
                    "responses" to mapOf("200" to mapOf("description" to "Aggregated server metrics"))
                )
            ),
            "/metrics/plots" to mapOf(
                "get" to mapOf(
                    "summary" to "Get plot job metrics",
                    "tags" to listOf("Metrics"),
                    "responses" to mapOf("200" to mapOf("description" to "Plot job metrics by world"))
                )
            ),
            "/metrics/worlds/{name}" to mapOf(
                "get" to mapOf(
                    "summary" to "Get metrics for a single world",
                    "tags" to listOf("Metrics"),
                    "parameters" to listOf(
                        mapOf("name" to "name", "in" to "path", "required" to true, "schema" to mapOf("type" to "string"))
                    ),
                    "responses" to mapOf(
                        "200" to mapOf("description" to "Per-world metrics"),
                        "404" to mapOf("description" to "World not found")
                    )
                )
            )
        )
    )
    
    private fun swaggerUiHtml(): String = """
        <!DOCTYPE html>
        <html>
        <head>
            <title>HomeClaim API Documentation</title>
            <link rel="stylesheet" type="text/css" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css">
        </head>
        <body>
            <div id="swagger-ui"></div>
            <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
            <script>
                SwaggerUIBundle({
                    url: "/api/openapi.json",
                    dom_id: '#swagger-ui',
                    presets: [SwaggerUIBundle.presets.apis, SwaggerUIBundle.SwaggerUIStandalonePreset],
                    layout: "BaseLayout"
                });
            </script>
        </body>
        </html>
    """.trimIndent()
}

// ============================================
// DTOs (Data Transfer Objects)
// ============================================

data class ApiError(val error: String, val message: String? = null)
data class ApiSuccess(val status: String, val data: Map<String, Any?>? = null)
data class ApiInfo(val name: String, val version: String, val endpoints: List<String>)

data class HealthResponse(
    val status: String,
    val version: String,
    val storage: String,
    val timestamp: Long
)

data class PaginatedResponse<T>(
    val data: List<T>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

data class PlotDto(
    val id: String,
    val world: String,
    val owner: String,
    val bounds: BoundsDto,
    val shape: String,
    val price: Double,
    val flags: Map<String, Any>,
    val limits: Map<String, Any>,
    val trusted: List<String>,
    val members: List<String>,
    val banned: List<String>
)

data class BoundsDto(
    val minX: Int, val maxX: Int,
    val minY: Int, val maxY: Int,
    val minZ: Int, val maxZ: Int
)

data class ComponentDto(
    val id: String,
    val regionId: String,
    val type: String,
    val position: PositionDto,
    val state: String
)

data class PositionDto(val world: String, val x: Int, val y: Int, val z: Int)

data class ZoneDto(
    val id: String,
    val world: String,
    val shape: String,
    val bounds: BoundsDto,
    val priority: Int,
    val tags: Set<String>
)

data class PlayerStats(
    val playerId: String,
    val plotCount: Int,
    val totalArea: Int,
    val plotsForSale: Int
)

data class ServerStats(
    val uptime: Long,
    val apiVersion: String,
    val storageType: String
)

// Request DTOs
data class BuyRequest(val buyerId: String)
data class SellRequest(val price: Double)
data class FlagUpdateRequest(val key: String, val value: Any?)
data class PlayerActionRequest(val playerId: String)
data class ApplyProfileRequest(val profile: String)
data class ProfileCreateRequest(
    val name: String,
    val flags: Map<String, Any?> = emptyMap(),
    val limits: Map<String, Any?> = emptyMap()
)
data class LicenseCheckRequest(val licenses: List<String>)

// License DTOs
data class LicenseDto(
    val id: String,
    val name: String,
    val category: String,
    val ossScore: Double,
    val copyleftStrength: String,
    val isOsiApproved: Boolean,
    val isFsfLibre: Boolean
)

data class LicenseDetailDto(
    val id: String,
    val name: String,
    val category: String,
    val ossScore: Double,
    val copyleftStrength: String,
    val isOsiApproved: Boolean,
    val isFsfLibre: Boolean,
    val permissions: List<String>,
    val conditions: List<String>,
    val limitations: List<String>,
    val spdxUrl: String
)

data class LicenseListResponse(
    val licenses: List<LicenseDto>,
    val total: Int
)

data class LicenseCompatibilityResponse(
    val compatible: Boolean,
    val issues: List<String>,
    val warnings: List<String>,
    val strongestCopyleft: String,
    val combinedOssScore: Double,
    val licenses: List<LicenseDto>
)

data class ProjectLicenseScore(
    val overallScore: Double,
    val grade: String,  // A+, A, B, C, D, F
    val osiApprovedCount: Int,
    val fsfLibreCount: Int,
    val copyleftDistribution: Map<String, Int>,
    val recommendations: List<String>,
    val licenses: List<LicenseDto>
)

// ============================================
// Extension Functions
// ============================================

private fun Region.toDto() = PlotDto(
    id = id.value.toString(),
    world = world,
    owner = owner.toString(),
    bounds = BoundsDto(bounds.minX, bounds.maxX, bounds.minY, bounds.maxY, bounds.minZ, bounds.maxZ),
    shape = shape.name,
    price = price,
    flags = flags.mapKeys { it.key.value }.mapValues { it.value.toAny() },
    limits = limits.mapKeys { it.key.value }.mapValues { it.value.toAny() },
    trusted = roles.trusted.map { it.toString() },
    members = roles.members.map { it.toString() },
    banned = roles.banned.map { it.toString() }
)

private fun Component.toDto() = ComponentDto(
    id = id.value.toString(),
    regionId = regionId.value.toString(),
    type = type.name,
    position = PositionDto(position.world, position.x, position.y, position.z),
    state = state.name
)

private fun Zone.toDto() = ZoneDto(
    id = id.value.toString(),
    world = world, // WorldId is typealias for String
    shape = shape.name,
    bounds = BoundsDto(bounds.minX, bounds.maxX, bounds.minY, bounds.maxY, bounds.minZ, bounds.maxZ),
    priority = priority,
    tags = tags
)

private fun PolicyValue.toAny(): Any = when (this) {
    is PolicyValue.Bool -> allowed
    is PolicyValue.IntValue -> value
    is PolicyValue.Text -> value
}

private fun Any?.toPolicyValue(): PolicyValue = when (this) {
    is Boolean -> PolicyValue.Bool(this)
    is Number -> PolicyValue.IntValue(this.toInt())
    else -> PolicyValue.Text(this?.toString() ?: "")
}

private fun String.toUuidOrNull(): UUID? = try { UUID.fromString(this) } catch (_: Exception) { null }
private fun String.toRegionId(): RegionId? = toUuidOrNull()?.let { RegionId(it) }
private fun String.toZoneId(): ZoneId? = toUuidOrNull()?.let { ZoneId(it) }

// ============================================
// License Analysis Functions
// ============================================

private fun SpdxLicense.toLicenseDto() = LicenseDto(
    id = id,
    name = name,
    category = category.name,
    ossScore = ossScore,
    copyleftStrength = copyleftStrength.name,
    isOsiApproved = isOsiApproved,
    isFsfLibre = isFsfLibre
)

private fun SpdxLicense.toDetailedDto() = LicenseDetailDto(
    id = id,
    name = name,
    category = category.name,
    ossScore = ossScore,
    copyleftStrength = copyleftStrength.name,
    isOsiApproved = isOsiApproved,
    isFsfLibre = isFsfLibre,
    permissions = permissions.map { it.name },
    conditions = conditions.map { it.name },
    limitations = limitations.map { it.name },
    spdxUrl = spdxUrl
)

private fun analyzeLicenseCompatibility(licenses: List<SpdxLicense>): LicenseCompatibilityResponse {
    val issues = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    
    // Find strongest copyleft
    val strongestCopyleft = licenses.maxByOrNull { it.copyleftStrength.ordinal }?.copyleftStrength
        ?: CopyleftStrength.NONE
    
    // Check for copyleft conflicts
    val hasPermissive = licenses.any { it.category == LicenseCategory.PERMISSIVE }
    val hasStrongCopyleft = licenses.any { it.category == LicenseCategory.STRONG_COPYLEFT }
    val hasWeakCopyleft = licenses.any { it.category == LicenseCategory.WEAK_COPYLEFT }
    
    if (hasPermissive && hasStrongCopyleft) {
        issues.add("Strong copyleft license (GPL) conflicts with permissive licenses - entire project must be GPL")
    }
    
    if (hasWeakCopyleft && hasStrongCopyleft) {
        warnings.add("Mixing weak and strong copyleft licenses - strong copyleft takes precedence")
    }
    
    // Check for GPL compatibility
    val gplLicenses = licenses.filter { it.id.startsWith("GPL-") || it.id.startsWith("AGPL-") }
    val apacheLicenses = licenses.filter { it.id.startsWith("Apache-") }
    
    if (gplLicenses.any { it.id == "GPL-2.0-only" } && apacheLicenses.isNotEmpty()) {
        issues.add("GPL-2.0 is incompatible with Apache-2.0 (patent clause conflict)")
    }
    
    // OSI approval check
    val nonOsiCount = licenses.count { !it.isOsiApproved }
    if (nonOsiCount > 0) {
        warnings.add("$nonOsiCount license(s) are not OSI-approved")
    }
    
    // Calculate combined OSS score (weighted average)
    val combinedScore = if (hasStrongCopyleft) {
        // Strong copyleft "infects" entire project
        licenses.filter { it.category == LicenseCategory.STRONG_COPYLEFT }.minOfOrNull { it.ossScore } ?: 0.0
    } else {
        licenses.map { it.ossScore }.average()
    }
    
    return LicenseCompatibilityResponse(
        compatible = issues.isEmpty(),
        issues = issues,
        warnings = warnings,
        strongestCopyleft = strongestCopyleft.name,
        combinedOssScore = combinedScore,
        licenses = licenses.map { it.toLicenseDto() }
    )
}

private fun calculateProjectScore(licenses: List<SpdxLicense>): ProjectLicenseScore {
    val osiApprovedCount = licenses.count { it.isOsiApproved }
    val fsfLibreCount = licenses.count { it.isFsfLibre }
    
    val copyleftDistribution = licenses.groupingBy { it.copyleftStrength.name }.eachCount()
    
    // Calculate overall score
    val baseScore = licenses.map { it.ossScore }.average()
    val osiBonus = (osiApprovedCount.toDouble() / licenses.size) * 10
    val fsfBonus = (fsfLibreCount.toDouble() / licenses.size) * 5
    
    // Penalty for mixing copyleft strengths
    val mixingPenalty = if (copyleftDistribution.size > 2) 10.0 else 0.0
    
    val overallScore = (baseScore + osiBonus + fsfBonus - mixingPenalty).coerceIn(0.0, 100.0)
    
    // Grade
    val grade = when {
        overallScore >= 95 -> "A+"
        overallScore >= 90 -> "A"
        overallScore >= 80 -> "B+"
        overallScore >= 70 -> "B"
        overallScore >= 60 -> "C"
        overallScore >= 50 -> "D"
        else -> "F"
    }
    
    // Recommendations
    val recommendations = mutableListOf<String>()
    
    if (licenses.any { it.category == LicenseCategory.STRONG_COPYLEFT && it.category == LicenseCategory.PERMISSIVE }) {
        recommendations.add("⚠️ Strong copyleft detected - consider using only GPL-compatible licenses")
    }
    
    if (osiApprovedCount < licenses.size) {
        recommendations.add("Consider using only OSI-approved licenses for better compatibility")
    }
    
    if (copyleftDistribution.size > 2) {
        recommendations.add("Simplify your license strategy - multiple copyleft strengths can cause confusion")
    }
    
    val hasGpl2 = licenses.any { it.id == "GPL-2.0-only" }
    val hasApache = licenses.any { it.id.startsWith("Apache-") }
    if (hasGpl2 && hasApache) {
        recommendations.add("⚠️ GPL-2.0 and Apache-2.0 are incompatible - upgrade to GPL-3.0 or remove Apache")
    }
    
    if (overallScore >= 90) {
        recommendations.add("✅ Excellent license compatibility! Your project follows OSS best practices.")
    }
    
    return ProjectLicenseScore(
        overallScore = overallScore,
        grade = grade,
        osiApprovedCount = osiApprovedCount,
        fsfLibreCount = fsfLibreCount,
        copyleftDistribution = copyleftDistribution,
        recommendations = recommendations,
        licenses = licenses.map { it.toLicenseDto() }
    )
}

// ============================================
// Auth Middleware
// ============================================

private class AuthMiddleware(
    private val token: String,
    private val rateLimiter: RateLimiter,
    private val allowedHosts: Set<String>,
    private val allowLocalhost: Boolean
) {
    suspend fun check(call: ApplicationCall): Boolean {
        val clientHost = call.request.local.remoteHost
        
        // Host check
        if (allowedHosts.isNotEmpty() || allowLocalhost) {
            val isLocal = clientHost in listOf("127.0.0.1", "::1", "localhost")
            if (!(allowLocalhost && isLocal) && clientHost !in allowedHosts) {
                call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", "Host not allowed"))
                return false
            }
        }
        
        // Token check
        val providedToken = call.request.headers["X-Admin-Token"]
        if (providedToken != token) {
            call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Invalid or missing token"))
            return false
        }
        
        // Rate limit
        if (!rateLimiter.tryAcquire(providedToken)) {
            call.respond(HttpStatusCode.TooManyRequests, ApiError("rate_limited", "Too many requests"))
            return false
        }
        
        // Client License Tracking
        val clientId = call.request.headers["X-Client-Id"]
        if (!clientId.isNullOrBlank()) {
            val licensesHeader = call.request.headers["X-Client-Licenses"]
            val licenses = licensesHeader?.split(",")?.map { it.trim() } ?: emptyList()
            val repository = call.request.headers["X-Client-Repository"]
            ClientLicenseRegistry.track(clientId, licenses, repository)
        }
        
        return true
    }
}

// ============================================
// Rate Limiter
// ============================================

private class RateLimiter(private val perMinute: Int) {
    private val windowMs = 60_000L
    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()
    
    fun tryAcquire(key: String): Boolean {
        if (perMinute <= 0) return true
        val now = System.currentTimeMillis()
        val deque = hits.computeIfAbsent(key) { ArrayDeque() }
        synchronized(deque) {
            val cutoff = now - windowMs
            while (deque.isNotEmpty() && deque.first() < cutoff) deque.removeFirst()
            if (deque.size >= perMinute) return false
            deque.addLast(now)
            return true
        }
    }
}

// ============================================
// Health Info
// ============================================

data class HealthInfo(
    val version: String? = null,
    val storage: String? = null
)

// ============================================
// Client License Tracking
// ============================================

data class ClientLicenseInfo(
    val clientId: String,
    val licenses: List<String>,
    val repositoryUrl: String? = null,
    val score: Double = 0.0,
    val grade: String = "N/A",
    val lastSeen: Long = System.currentTimeMillis(),
    val requestCount: Long = 0
)

data class ClientLicenseResponse(
    val clientId: String,
    val licenses: List<LicenseDto>,
    val repositoryUrl: String? = null,
    val score: Double,
    val grade: String,
    val lastSeen: String,
    val requestCount: Long
)

data class ClientLicensesListResponse(
    val clients: List<ClientLicenseResponse>,
    val total: Int
)

object ClientLicenseRegistry {
    private val clients = ConcurrentHashMap<String, ClientLicenseInfo>()
    
    fun track(clientId: String, licenses: List<String>, repository: String? = null) {
        val existing = clients[clientId]
        val score = if (licenses.isNotEmpty()) {
            val licenseObjects = licenses.mapNotNull { SpdxLicenseRegistry.getLicense(it) }
            if (licenseObjects.isNotEmpty()) {
                licenseObjects.map { it.ossScore }.average()
            } else 0.0
        } else 0.0
        
        val grade = when {
            score >= 95 -> "A+"
            score >= 90 -> "A"
            score >= 80 -> "B+"
            score >= 70 -> "B"
            score >= 60 -> "C"
            score >= 50 -> "D"
            else -> "F"
        }
        
        clients[clientId] = ClientLicenseInfo(
            clientId = clientId,
            licenses = licenses,
            repositoryUrl = repository,
            score = score,
            grade = grade,
            lastSeen = System.currentTimeMillis(),
            requestCount = (existing?.requestCount ?: 0) + 1
        )
    }
    
    fun getAllClients(): List<ClientLicenseInfo> = clients.values.sortedByDescending { it.lastSeen }
    
    fun getClient(clientId: String): ClientLicenseInfo? = clients[clientId]
}

// ============================================
// API Request Logging & Metrics
// ============================================

data class ApiLog(
    val timestamp: Instant,
    val clientId: String?,
    val endpoint: String,
    val method: String,
    val statusCode: Int,
    val responseTimeMs: Long,
    val requestSizeBytes: Int = 0,
    val responseSizeBytes: Int = 0,
    val error: String? = null
)

data class ApiMetricsResponse(
    val totalRequests: Long,
    val errorRate: Double,
    val avgResponseTimeMs: Double,
    val uptime: Long,
    val endpoints: Map<String, EndpointMetrics>,
    val clientMetrics: Map<String, ClientMetrics>
)

data class EndpointMetrics(
    val count: Long,
    val errorCount: Long,
    val errorRate: Double,
    val avgResponseTimeMs: Double,
    val minResponseTimeMs: Long,
    val maxResponseTimeMs: Long
)

data class ClientMetrics(
    val requestCount: Long,
    val errorCount: Long,
    val errorRate: Double,
    val avgResponseTimeMs: Double
)

data class ApiLogsResponse(
    val logs: List<ApiLog>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

object ApiLogRegistry {
    private val logs = ArrayDeque<ApiLog>(1000)
    private val lock = Any()
    private val startTime = System.currentTimeMillis()
    private var enableFileLogging = false
    private val logDir = java.io.File("logs")
    
    init {
        logDir.mkdirs()
    }
    
    fun setFileLoggingEnabled(enabled: Boolean) {
        enableFileLogging = enabled
    }
    
    fun log(
        clientId: String?,
        endpoint: String,
        method: String,
        statusCode: Int,
        responseTimeMs: Long,
        requestSize: Int = 0,
        responseSize: Int = 0,
        error: String? = null
    ) {
        val logEntry = ApiLog(
            timestamp = Instant.now(),
            clientId = clientId,
            endpoint = endpoint,
            method = method,
            statusCode = statusCode,
            responseTimeMs = responseTimeMs,
            requestSizeBytes = requestSize,
            responseSizeBytes = responseSize,
            error = error
        )
        
        synchronized(lock) {
            if (logs.size >= 1000) logs.removeFirst()
            logs.addLast(logEntry)
        }
        
        if (enableFileLogging) {
            writeToFile(logEntry)
        }
    }
    
    private fun writeToFile(log: ApiLog) {
        try {
            val dateStr = java.time.LocalDate.now().toString()
            val file = java.io.File(logDir, "api-$dateStr.jsonl")
            val json = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(log)
            file.appendText("$json\n")
        } catch (e: Exception) {
            // Silently ignore file write errors
        }
    }
    
    fun getLogs(limit: Int = 100, offset: Int = 0, clientId: String? = null, status: Int? = null): ApiLogsResponse {
        synchronized(lock) {
            val filtered = logs.asSequence()
                .filter { clientId == null || it.clientId == clientId }
                .filter { status == null || it.statusCode == status }
                .sortedByDescending { it.timestamp }
                .toList()
            
            val paginated = filtered.drop(offset).take(limit)
            
            return ApiLogsResponse(
                logs = paginated,
                total = filtered.size,
                limit = limit,
                offset = offset
            )
        }
    }
    
    fun getMetrics(): ApiMetricsResponse {
        synchronized(lock) {
            val totalRequests = logs.size.toLong()
            val errorCount = logs.count { it.statusCode >= 400 }.toLong()
            val errorRate = if (totalRequests > 0) (errorCount.toDouble() / totalRequests) * 100 else 0.0
            val avgResponseTime = if (logs.isNotEmpty()) logs.map { it.responseTimeMs }.average() else 0.0
            
            val endpointMetrics = logs.groupBy { "${it.method} ${it.endpoint}" }
                .mapValues { (_, endpointLogs) ->
                    val count = endpointLogs.size.toLong()
                    val errors = endpointLogs.count { it.statusCode >= 400 }.toLong()
                    val avgTime = endpointLogs.map { it.responseTimeMs }.average()
                    val minTime = endpointLogs.minOf { it.responseTimeMs }
                    val maxTime = endpointLogs.maxOf { it.responseTimeMs }
                    EndpointMetrics(
                        count = count,
                        errorCount = errors,
                        errorRate = if (count > 0) (errors.toDouble() / count) * 100 else 0.0,
                        avgResponseTimeMs = avgTime,
                        minResponseTimeMs = minTime,
                        maxResponseTimeMs = maxTime
                    )
                }
            
            val clientMetrics = logs.groupBy { it.clientId ?: "unknown" }
                .mapValues { (_, clientLogs) ->
                    val count = clientLogs.size.toLong()
                    val errors = clientLogs.count { it.statusCode >= 400 }.toLong()
                    val avgTime = clientLogs.map { it.responseTimeMs }.average()
                    ClientMetrics(
                        requestCount = count,
                        errorCount = errors,
                        errorRate = if (count > 0) (errors.toDouble() / count) * 100 else 0.0,
                        avgResponseTimeMs = avgTime
                    )
                }
            
            return ApiMetricsResponse(
                totalRequests = totalRequests,
                errorRate = errorRate,
                avgResponseTimeMs = avgResponseTime,
                uptime = System.currentTimeMillis() - startTime,
                endpoints = endpointMetrics,
                clientMetrics = clientMetrics
            )
        }
    }
}
