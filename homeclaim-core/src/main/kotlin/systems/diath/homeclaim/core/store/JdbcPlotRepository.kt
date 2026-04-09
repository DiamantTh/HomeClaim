package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.model.*
import systems.diath.homeclaim.core.service.PlotService
import systems.diath.homeclaim.core.service.PlotUpdate
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcPlotRepository(
    private val dataSource: DataSource
) : PlotService {

    override fun getPlotById(plotId: PlotId): Plot? {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM plots WHERE id = ?").use { stmt ->
                stmt.setObject(1, plotId.value)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toPlot() else null
                }
            }
        }
    }

    override fun getPlotByRegion(regionId: RegionId): Plot? {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM plots WHERE region_id = ?").use { stmt ->
                stmt.setObject(1, regionId.value)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toPlot() else null
                }
            }
        }
    }

    override fun getPlotByAlias(alias: String): Plot? {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM plots WHERE LOWER(alias) = LOWER(?)").use { stmt ->
                stmt.setString(1, alias)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toPlot() else null
                }
            }
        }
    }

    override fun getPlotByOwnerUsername(username: String): Plot? {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT p.* FROM plots p
                JOIN accounts a ON p.owner = a.player_id
                WHERE LOWER(a.username) = LOWER(?)
                LIMIT 1
            """.trimIndent()).use { stmt ->
                stmt.setString(1, username)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toPlot() else null
                }
            }
        }
    }

    override fun listPlotsByOwner(ownerId: PlayerId): List<Plot> {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM plots WHERE owner = ? ORDER BY created_at DESC").use { stmt ->
                stmt.setObject(1, ownerId)
                stmt.executeQuery().use { rs ->
                    val plots = mutableListOf<Plot>()
                    while (rs.next()) {
                        plots += rs.toPlot()
                    }
                    plots
                }
            }
        }
    }

    override fun listFeaturedPlots(): List<Plot> {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT * FROM plots 
                WHERE featured = true AND visibility = 'PUBLIC'
                ORDER BY likes_total DESC
                LIMIT 50
            """.trimIndent()).use { stmt ->
                stmt.executeQuery().use { rs ->
                    val plots = mutableListOf<Plot>()
                    while (rs.next()) {
                        plots += rs.toPlot()
                    }
                    plots
                }
            }
        }
    }

    override fun listPublicPlots(limit: Int, offset: Int): List<Plot> {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT * FROM plots 
                WHERE visibility = 'PUBLIC'
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
            """.trimIndent()).use { stmt ->
                stmt.setInt(1, limit)
                stmt.setInt(2, offset)
                stmt.executeQuery().use { rs ->
                    val plots = mutableListOf<Plot>()
                    while (rs.next()) {
                        plots += rs.toPlot()
                    }
                    plots
                }
            }
        }
    }

    override fun listAllPlots(limit: Int, offset: Int): List<Plot> {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT * FROM plots 
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
            """.trimIndent()).use { stmt ->
                stmt.setInt(1, limit)
                stmt.setInt(2, offset)
                stmt.executeQuery().use { rs ->
                    val plots = mutableListOf<Plot>()
                    while (rs.next()) {
                        plots += rs.toPlot()
                    }
                    plots
                }
            }
        }
    }

    override fun searchPlots(query: String, limit: Int): List<Plot> {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT * FROM plots 
                WHERE visibility = 'PUBLIC'
                  AND (LOWER(title) LIKE LOWER(?) OR LOWER(description) LIKE LOWER(?) OR LOWER(alias) LIKE LOWER(?))
                ORDER BY likes_total DESC
                LIMIT ?
            """.trimIndent()).use { stmt ->
                val pattern = "%$query%"
                stmt.setString(1, pattern)
                stmt.setString(2, pattern)
                stmt.setString(3, pattern)
                stmt.setInt(4, limit)
                stmt.executeQuery().use { rs ->
                    val plots = mutableListOf<Plot>()
                    while (rs.next()) {
                        plots += rs.toPlot()
                    }
                    plots
                }
            }
        }
    }

    override fun createPlot(regionId: RegionId, owner: PlayerId): Plot {
        val plotId = PlotId(UUID.randomUUID())
        val now = Instant.now()
        
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                INSERT INTO plots (
                    id, region_id, owner, visibility, broadcasting_enabled, featured,
                    likes_total, likes_24h, likes_7d, likes_30d,
                    visits_total, visits_24h, visits_7d, bookmarks_total,
                    created_at, updated_at
                ) VALUES (?, ?, ?, 'PUBLIC', true, false, 0, 0, 0, 0, 0, 0, 0, 0, ?, ?)
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, plotId.value)
                stmt.setObject(2, regionId.value)
                stmt.setObject(3, owner)
                stmt.setTimestamp(4, Timestamp.from(now))
                stmt.setTimestamp(5, Timestamp.from(now))
                stmt.executeUpdate()
            }
        }
        
        return getPlotById(plotId)!!
    }

    override fun updatePlot(plotId: PlotId, update: PlotUpdate): Plot? {
        val setters = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        
        update.title?.let { setters += "title = ?"; values += it }
        update.description?.let { setters += "description = ?"; values += it }
        update.internalDescription?.let { setters += "internal_description = ?"; values += it }
        update.notes?.let { setters += "notes = ?"; values += it }
        update.category?.let { setters += "category = ?"; values += it }
        update.tags?.let { setters += "tags = ?"; values += it.joinToString(",") }
        update.visibility?.let { setters += "visibility = ?"; values += it.name }
        update.broadcastingEnabled?.let { setters += "broadcasting_enabled = ?"; values += it }
        update.featured?.let { setters += "featured = ?"; values += it }
        
        if (setters.isEmpty()) return getPlotById(plotId)
        
        setters += "updated_at = ?"
        values += Timestamp.from(Instant.now())
        values += plotId.value
        
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                UPDATE plots SET ${setters.joinToString(", ")} WHERE id = ?
            """.trimIndent()).use { stmt ->
                values.forEachIndexed { index, value ->
                    when (value) {
                        is String -> stmt.setString(index + 1, value)
                        is Boolean -> stmt.setBoolean(index + 1, value)
                        is Timestamp -> stmt.setTimestamp(index + 1, value)
                        is UUID -> stmt.setObject(index + 1, value)
                        else -> stmt.setObject(index + 1, value)
                    }
                }
                stmt.executeUpdate()
            }
        }
        
        return getPlotById(plotId)
    }

    override fun deletePlot(plotId: PlotId): Boolean {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM plots WHERE id = ?").use { stmt ->
                stmt.setObject(1, plotId.value)
                stmt.executeUpdate() > 0
            }
        }
    }

    override fun setWarp(plotId: PlotId, x: Double, y: Double, z: Double, yaw: Float, pitch: Float): Boolean {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                UPDATE plots SET warp_x = ?, warp_y = ?, warp_z = ?, warp_yaw = ?, warp_pitch = ?, updated_at = ?
                WHERE id = ?
            """.trimIndent()).use { stmt ->
                stmt.setDouble(1, x)
                stmt.setDouble(2, y)
                stmt.setDouble(3, z)
                stmt.setFloat(4, yaw)
                stmt.setFloat(5, pitch)
                stmt.setTimestamp(6, Timestamp.from(Instant.now()))
                stmt.setObject(7, plotId.value)
                stmt.executeUpdate() > 0
            }
        }
    }

    override fun clearWarp(plotId: PlotId): Boolean {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                UPDATE plots SET warp_x = NULL, warp_y = NULL, warp_z = NULL, warp_yaw = NULL, warp_pitch = NULL, updated_at = ?
                WHERE id = ?
            """.trimIndent()).use { stmt ->
                stmt.setTimestamp(1, Timestamp.from(Instant.now()))
                stmt.setObject(2, plotId.value)
                stmt.executeUpdate() > 0
            }
        }
    }

    override fun setAlias(plotId: PlotId, alias: String): Boolean {
        if (!isAliasAvailable(alias)) return false
        
        return dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE plots SET alias = ?, updated_at = ? WHERE id = ?").use { stmt ->
                stmt.setString(1, alias)
                stmt.setTimestamp(2, Timestamp.from(Instant.now()))
                stmt.setObject(3, plotId.value)
                stmt.executeUpdate() > 0
            }
        }
    }

    override fun clearAlias(plotId: PlotId): Boolean {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE plots SET alias = NULL, updated_at = ? WHERE id = ?").use { stmt ->
                stmt.setTimestamp(1, Timestamp.from(Instant.now()))
                stmt.setObject(2, plotId.value)
                stmt.executeUpdate() > 0
            }
        }
    }

    override fun isAliasAvailable(alias: String): Boolean {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM plots WHERE LOWER(alias) = LOWER(?)").use { stmt ->
                stmt.setString(1, alias)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) == 0
                }
            }
        }
    }

    override fun incrementLikes(plotId: PlotId) = updateStat(plotId, "likes_total", 1)
    override fun decrementLikes(plotId: PlotId) = updateStat(plotId, "likes_total", -1)
    override fun incrementVisits(plotId: PlotId) = updateStat(plotId, "visits_total", 1)
    override fun incrementBookmarks(plotId: PlotId) = updateStat(plotId, "bookmarks_total", 1)
    override fun decrementBookmarks(plotId: PlotId) = updateStat(plotId, "bookmarks_total", -1)

    private fun updateStat(plotId: PlotId, column: String, delta: Int) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE plots SET $column = $column + ? WHERE id = ?").use { stmt ->
                stmt.setInt(1, delta)
                stmt.setObject(2, plotId.value)
                stmt.executeUpdate()
            }
        }
    }

    override fun recalculateStats(plotId: PlotId) {
        val now = Instant.now()
        val h24 = now.minusSeconds(24 * 60 * 60)
        val d7 = now.minusSeconds(7 * 24 * 60 * 60)
        val d30 = now.minusSeconds(30 * 24 * 60 * 60)
        
        dataSource.connection.use { conn ->
            // Recalculate likes
            val likes24h = countSince(conn, "plot_likes", plotId, h24)
            val likes7d = countSince(conn, "plot_likes", plotId, d7)
            val likes30d = countSince(conn, "plot_likes", plotId, d30)
            val likesTotal = countTotal(conn, "plot_likes", plotId)
            
            // Recalculate visits
            val visits24h = countSince(conn, "plot_visits", plotId, h24)
            val visits7d = countSince(conn, "plot_visits", plotId, d7)
            val visitsTotal = countTotal(conn, "plot_visits", plotId)
            
            // Recalculate bookmarks
            val bookmarksTotal = countTotal(conn, "plot_bookmarks", plotId)
            
            conn.prepareStatement("""
                UPDATE plots SET
                    likes_24h = ?, likes_7d = ?, likes_30d = ?, likes_total = ?,
                    visits_24h = ?, visits_7d = ?, visits_total = ?,
                    bookmarks_total = ?
                WHERE id = ?
            """.trimIndent()).use { stmt ->
                stmt.setInt(1, likes24h)
                stmt.setInt(2, likes7d)
                stmt.setInt(3, likes30d)
                stmt.setInt(4, likesTotal)
                stmt.setInt(5, visits24h)
                stmt.setInt(6, visits7d)
                stmt.setInt(7, visitsTotal)
                stmt.setInt(8, bookmarksTotal)
                stmt.setObject(9, plotId.value)
                stmt.executeUpdate()
            }
        }
    }

    override fun recalculateAllStats() {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT id FROM plots").use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        recalculateStats(PlotId(UUID.fromString(rs.getString("id"))))
                    }
                }
            }
        }
    }

    private fun countSince(conn: Connection, table: String, plotId: PlotId, since: Instant): Int {
        conn.prepareStatement("SELECT COUNT(*) FROM $table WHERE plot_id = ? AND created_at >= ?").use { stmt ->
            stmt.setObject(1, plotId.value)
            stmt.setTimestamp(2, Timestamp.from(since))
            stmt.executeQuery().use { rs ->
                rs.next()
                return rs.getInt(1)
            }
        }
    }

    private fun countTotal(conn: Connection, table: String, plotId: PlotId): Int {
        conn.prepareStatement("SELECT COUNT(*) FROM $table WHERE plot_id = ?").use { stmt ->
            stmt.setObject(1, plotId.value)
            stmt.executeQuery().use { rs ->
                rs.next()
                return rs.getInt(1)
            }
        }
    }

    private fun ResultSet.toPlot(): Plot {
        val tagsRaw = getString("tags")
        val tags = if (tagsRaw.isNullOrBlank()) emptyList() else tagsRaw.split(",")
        
        val statsWhitelistRaw = getString("stats_whitelist")
        val statsWhitelist = if (statsWhitelistRaw.isNullOrBlank()) emptyList() 
            else statsWhitelistRaw.split(",").map { UUID.fromString(it) }
        
        // Parse StatsVisibility from JSON or use defaults
        val statsVisibility = try {
            val visibilityRaw = getString("stats_visibility")
            if (visibilityRaw.isNullOrBlank()) {
                StatsVisibility()
            } else {
                // Parse JSON: {"likes":true,"visits":true,"bookmarks":true,"members":true}
                val parts = visibilityRaw.removeSurrounding("{", "}").split(",")
                StatsVisibility(
                    likes = parts.firstOrNull { it.contains("\"likes\"") }?.contains("true") ?: true,
                    visits = parts.firstOrNull { it.contains("\"visits\"") }?.contains("true") ?: true,
                    bookmarks = parts.firstOrNull { it.contains("\"bookmarks\"") }?.contains("true") ?: true,
                    members = parts.firstOrNull { it.contains("\"members\"") }?.contains("true") ?: true
                )
            }
        } catch (e: Exception) {
            println("[Plot] Failed to parse stats_visibility: ${e.message}")
            StatsVisibility()
        }
        
        return Plot(
            id = PlotId(UUID.fromString(getString("id"))),
            regionId = RegionId(UUID.fromString(getString("region_id"))),
            owner = UUID.fromString(getString("owner")),
            title = getString("title"),
            alias = getString("alias"),
            description = getString("description"),
            internalDescription = getString("internal_description"),
            notes = getString("notes"),
            category = getString("category"),
            tags = tags,
            visibility = PlotVisibility.valueOf(getString("visibility") ?: "PUBLIC"),
            warpX = getObject("warp_x") as? Double,
            warpY = getObject("warp_y") as? Double,
            warpZ = getObject("warp_z") as? Double,
            warpYaw = getObject("warp_yaw") as? Float,
            warpPitch = getObject("warp_pitch") as? Float,
            likesTotal = getInt("likes_total"),
            likes24h = getInt("likes_24h"),
            likes7d = getInt("likes_7d"),
            likes30d = getInt("likes_30d"),
            visitsTotal = getInt("visits_total"),
            visits24h = getInt("visits_24h"),
            visits7d = getInt("visits_7d"),
            bookmarksTotal = getInt("bookmarks_total"),
            statsVisibility = statsVisibility,
            statsWhitelist = statsWhitelist,
            broadcastingEnabled = getBoolean("broadcasting_enabled"),
            featured = getBoolean("featured"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant()
        )
    }
}
