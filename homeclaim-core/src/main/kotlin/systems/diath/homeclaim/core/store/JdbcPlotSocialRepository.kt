package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.model.*
import systems.diath.homeclaim.core.service.PlotSocialService
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcPlotSocialRepository(
    private val dataSource: DataSource
) : PlotSocialService {

    // ============ LIKES ============

    override fun like(plotId: PlotId, playerId: PlayerId): Boolean {
        if (hasLiked(plotId, playerId)) return false
        
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                INSERT INTO plot_likes (plot_id, player_id, created_at)
                VALUES (?, ?, ?)
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, plotId.value)
                stmt.setObject(2, playerId)
                stmt.setTimestamp(3, Timestamp.from(Instant.now()))
                stmt.executeUpdate() > 0
            }
        }
    }

    override fun unlike(plotId: PlotId, playerId: PlayerId): Boolean {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM plot_likes WHERE plot_id = ? AND player_id = ?").use { stmt ->
                stmt.setObject(1, plotId.value)
                stmt.setObject(2, playerId)
                stmt.executeUpdate() > 0
            }
        }
    }

    override fun hasLiked(plotId: PlotId, playerId: PlayerId): Boolean {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT 1 FROM plot_likes WHERE plot_id = ? AND player_id = ?").use { stmt ->
                stmt.setObject(1, plotId.value)
                stmt.setObject(2, playerId)
                stmt.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    override fun getLikesCount(plotId: PlotId): Int {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM plot_likes WHERE plot_id = ?").use { stmt ->
                stmt.setObject(1, plotId.value)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
    }

    override fun getLikesCountSince(plotId: PlotId, since: Instant): Int {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM plot_likes WHERE plot_id = ? AND created_at >= ?").use { stmt ->
                stmt.setObject(1, plotId.value)
                stmt.setTimestamp(2, Timestamp.from(since))
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
    }

    // ============ BOOKMARKS ============

    override fun bookmark(plotId: PlotId, playerId: PlayerId, note: String?, tags: List<String>): Boolean {
        if (hasBookmarked(plotId, playerId)) return false
        
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                INSERT INTO plot_bookmarks (plot_id, player_id, note, tags, created_at)
                VALUES (?, ?, ?, ?, ?)
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, plotId.value)
                stmt.setObject(2, playerId)
                stmt.setString(3, note)
                stmt.setString(4, tags.joinToString(","))
                stmt.setTimestamp(5, Timestamp.from(Instant.now()))
                stmt.executeUpdate() > 0
            }
        }
    }

    override fun unbookmark(plotId: PlotId, playerId: PlayerId): Boolean {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM plot_bookmarks WHERE plot_id = ? AND player_id = ?").use { stmt ->
                stmt.setObject(1, plotId.value)
                stmt.setObject(2, playerId)
                stmt.executeUpdate() > 0
            }
        }
    }

    override fun hasBookmarked(plotId: PlotId, playerId: PlayerId): Boolean {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT 1 FROM plot_bookmarks WHERE plot_id = ? AND player_id = ?").use { stmt ->
                stmt.setObject(1, plotId.value)
                stmt.setObject(2, playerId)
                stmt.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    override fun getBookmark(plotId: PlotId, playerId: PlayerId): PlotBookmark? {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM plot_bookmarks WHERE plot_id = ? AND player_id = ?").use { stmt ->
                stmt.setObject(1, plotId.value)
                stmt.setObject(2, playerId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toBookmark() else null
                }
            }
        }
    }

    override fun listBookmarks(playerId: PlayerId): List<PlotBookmark> {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM plot_bookmarks WHERE player_id = ? ORDER BY created_at DESC").use { stmt ->
                stmt.setObject(1, playerId)
                stmt.executeQuery().use { rs ->
                    val bookmarks = mutableListOf<PlotBookmark>()
                    while (rs.next()) {
                        bookmarks += rs.toBookmark()
                    }
                    bookmarks
                }
            }
        }
    }

    override fun updateBookmark(plotId: PlotId, playerId: PlayerId, note: String?, tags: List<String>): Boolean {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE plot_bookmarks SET note = ?, tags = ? WHERE plot_id = ? AND player_id = ?").use { stmt ->
                stmt.setString(1, note)
                stmt.setString(2, tags.joinToString(","))
                stmt.setObject(3, plotId.value)
                stmt.setObject(4, playerId)
                stmt.executeUpdate() > 0
            }
        }
    }

    // ============ VISITS ============

    override fun trackVisit(plotId: PlotId, playerId: PlayerId): Boolean {
        // Only track if last visit was more than 30 minutes ago
        val lastVisit = getLastVisit(plotId, playerId)
        val thirtyMinutesAgo = Instant.now().minusSeconds(30 * 60)
        
        if (lastVisit != null && lastVisit.visitedAt.isAfter(thirtyMinutesAgo)) {
            return false // Don't track duplicate visit within 30 min
        }
        
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                INSERT INTO plot_visits (plot_id, player_id, visited_at)
                VALUES (?, ?, ?)
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, plotId.value)
                stmt.setObject(2, playerId)
                stmt.setTimestamp(3, Timestamp.from(Instant.now()))
                stmt.executeUpdate() > 0
            }
        }
    }

    override fun getVisitsCount(plotId: PlotId): Int {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM plot_visits WHERE plot_id = ?").use { stmt ->
                stmt.setObject(1, plotId.value)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
    }

    override fun getVisitsCountSince(plotId: PlotId, since: Instant): Int {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM plot_visits WHERE plot_id = ? AND visited_at >= ?").use { stmt ->
                stmt.setObject(1, plotId.value)
                stmt.setTimestamp(2, Timestamp.from(since))
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
    }

    override fun getLastVisit(plotId: PlotId, playerId: PlayerId): PlotVisit? {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT * FROM plot_visits 
                WHERE plot_id = ? AND player_id = ?
                ORDER BY visited_at DESC
                LIMIT 1
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, plotId.value)
                stmt.setObject(2, playerId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        PlotVisit(
                            plotId = PlotId(UUID.fromString(rs.getString("plot_id"))),
                            playerId = UUID.fromString(rs.getString("player_id")),
                            visitedAt = rs.getTimestamp("visited_at").toInstant()
                        )
                    } else null
                }
            }
        }
    }

    // ============ PRIVACY ============

    override fun canViewStats(plotId: PlotId, viewerId: PlayerId): Boolean {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT owner, visibility, stats_whitelist FROM plots WHERE id = ?
            """.trimIndent()).use useStmt@{ stmt ->
                stmt.setObject(1, plotId.value)
                stmt.executeQuery().use useRs@{ rs ->
                    if (!rs.next()) return@useRs false
                    
                    val owner = UUID.fromString(rs.getString("owner"))
                    val visibility = PlotVisibility.valueOf(rs.getString("visibility") ?: "PUBLIC")
                    val whitelist = rs.getString("stats_whitelist")?.split(",")
                        ?.mapNotNull { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
                        ?: emptyList()
                    
                    // Owner can always view
                    if (owner == viewerId) return@useRs true
                    
                    // Public plots are viewable
                    if (visibility == PlotVisibility.PUBLIC) return@useRs true
                    
                    // Check whitelist
                    whitelist.contains(viewerId)
                }
            }
        }
    }

    private fun java.sql.ResultSet.toBookmark(): PlotBookmark {
        val tagsRaw = getString("tags")
        val tags = if (tagsRaw.isNullOrBlank()) emptyList() else tagsRaw.split(",")
        
        return PlotBookmark(
            plotId = PlotId(UUID.fromString(getString("plot_id"))),
            playerId = UUID.fromString(getString("player_id")),
            note = getString("note"),
            tags = tags,
            createdAt = getTimestamp("created_at").toInstant()
        )
    }
}
