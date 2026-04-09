package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.model.*
import systems.diath.homeclaim.core.service.PlotMemberService
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcPlotMemberRepository(
    private val dataSource: DataSource
) : PlotMemberService {

    override fun getMember(plotId: PlotId, playerId: PlayerId): PlotMember? {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM plot_members WHERE plot_id = ? AND player_id = ?").use { stmt ->
                stmt.setObject(1, plotId.value)
                stmt.setObject(2, playerId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toPlotMember() else null
                }
            }
        }
    }

    override fun listMembers(plotId: PlotId): List<PlotMember> {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM plot_members WHERE plot_id = ? ORDER BY added_at").use { stmt ->
                stmt.setObject(1, plotId.value)
                stmt.executeQuery().use { rs ->
                    val members = mutableListOf<PlotMember>()
                    while (rs.next()) {
                        members += rs.toPlotMember()
                    }
                    members
                }
            }
        }
    }

    override fun listMembersByRole(plotId: PlotId, role: PlotMemberRole): List<PlotMember> {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM plot_members WHERE plot_id = ? AND role = ?").use { stmt ->
                stmt.setObject(1, plotId.value)
                stmt.setString(2, role.name)
                stmt.executeQuery().use { rs ->
                    val members = mutableListOf<PlotMember>()
                    while (rs.next()) {
                        members += rs.toPlotMember()
                    }
                    members
                }
            }
        }
    }

    override fun hasAccess(plotId: PlotId, playerId: PlayerId, ownerOnline: Boolean): Boolean {
        val member = getMember(plotId, playerId) ?: return false
        
        return when (member.role) {
            PlotMemberRole.OWNER, PlotMemberRole.TRUSTED -> true
            PlotMemberRole.MEMBER -> when (member.permissionMode) {
                PermissionMode.PERMANENT -> true
                PermissionMode.OWNER_ONLINE -> ownerOnline
            }
            PlotMemberRole.GUEST -> ownerOnline
            PlotMemberRole.DENIED -> false
        }
    }

    override fun isDenied(plotId: PlotId, playerId: PlayerId): Boolean {
        val member = getMember(plotId, playerId)
        return member?.role == PlotMemberRole.DENIED
    }

    override fun trustPlayer(plotId: PlotId, playerId: PlayerId, addedBy: PlayerId): Boolean {
        return upsertMember(plotId, playerId, PlotMemberRole.TRUSTED, PermissionMode.PERMANENT, addedBy)
    }

    override fun untrustPlayer(plotId: PlotId, playerId: PlayerId): Boolean {
        val member = getMember(plotId, playerId) ?: return false
        if (member.role != PlotMemberRole.TRUSTED) return false
        return removeMemberEntry(plotId, playerId)
    }

    override fun addMember(plotId: PlotId, playerId: PlayerId, addedBy: PlayerId): Boolean {
        return upsertMember(plotId, playerId, PlotMemberRole.MEMBER, PermissionMode.OWNER_ONLINE, addedBy)
    }

    override fun removeMember(plotId: PlotId, playerId: PlayerId): Boolean {
        val member = getMember(plotId, playerId) ?: return false
        if (member.role != PlotMemberRole.MEMBER) return false
        return removeMemberEntry(plotId, playerId)
    }

    override fun denyPlayer(plotId: PlotId, playerId: PlayerId, addedBy: PlayerId): Boolean {
        return upsertMember(plotId, playerId, PlotMemberRole.DENIED, PermissionMode.PERMANENT, addedBy)
    }

    override fun undenyPlayer(plotId: PlotId, playerId: PlayerId): Boolean {
        val member = getMember(plotId, playerId) ?: return false
        if (member.role != PlotMemberRole.DENIED) return false
        return removeMemberEntry(plotId, playerId)
    }

    override fun denyByPattern(plotId: PlotId, pattern: String, addedBy: PlayerId): Int {
        // Pattern like "*spam*" -> SQL LIKE "%spam%"
        val sqlPattern = pattern.replace("*", "%")
        
        return dataSource.connection.use { conn ->
            // Find matching players from accounts
            val matchingPlayers = mutableListOf<UUID>()
            conn.prepareStatement("SELECT player_id FROM accounts WHERE LOWER(username) LIKE LOWER(?)").use { stmt ->
                stmt.setString(1, sqlPattern)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        matchingPlayers += UUID.fromString(rs.getString("player_id"))
                    }
                }
            }
            
            // Deny each matching player
            var count = 0
            matchingPlayers.forEach { playerId ->
                if (denyPlayer(plotId, playerId, addedBy)) count++
            }
            count
        }
    }

    override fun kickPlayer(plotId: PlotId, playerId: PlayerId, kickedBy: PlayerId, reason: String?, durationMinutes: Int): Boolean {
        val now = Instant.now()
        val expiresAt = now.plusSeconds(durationMinutes.toLong() * 60)
        
        return dataSource.connection.use { conn ->
            // Remove existing kick if any
            conn.prepareStatement("DELETE FROM plot_kicks WHERE plot_id = ? AND player_id = ?").use { stmt ->
                stmt.setObject(1, plotId.value)
                stmt.setObject(2, playerId)
                stmt.executeUpdate()
            }
            
            // Insert new kick
            conn.prepareStatement("""
                INSERT INTO plot_kicks (plot_id, player_id, kicked_by, reason, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, plotId.value)
                stmt.setObject(2, playerId)
                stmt.setObject(3, kickedBy)
                stmt.setString(4, reason)
                stmt.setTimestamp(5, Timestamp.from(expiresAt))
                stmt.setTimestamp(6, Timestamp.from(now))
                stmt.executeUpdate() > 0
            }
        }
    }

    override fun isKicked(plotId: PlotId, playerId: PlayerId): PlotKick? {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT * FROM plot_kicks 
                WHERE plot_id = ? AND player_id = ? AND expires_at > ?
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, plotId.value)
                stmt.setObject(2, playerId)
                stmt.setTimestamp(3, Timestamp.from(Instant.now()))
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        PlotKick(
                            plotId = PlotId(UUID.fromString(rs.getString("plot_id"))),
                            playerId = UUID.fromString(rs.getString("player_id")),
                            kickedBy = UUID.fromString(rs.getString("kicked_by")),
                            reason = rs.getString("reason"),
                            expiresAt = rs.getTimestamp("expires_at").toInstant(),
                            createdAt = rs.getTimestamp("created_at").toInstant()
                        )
                    } else null
                }
            }
        }
    }

    override fun unkickPlayer(plotId: PlotId, playerId: PlayerId): Boolean {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM plot_kicks WHERE plot_id = ? AND player_id = ?").use { stmt ->
                stmt.setObject(1, plotId.value)
                stmt.setObject(2, playerId)
                stmt.executeUpdate() > 0
            }
        }
    }

    override fun cleanupExpiredKicks() {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM plot_kicks WHERE expires_at < ?").use { stmt ->
                stmt.setTimestamp(1, Timestamp.from(Instant.now()))
                stmt.executeUpdate()
            }
        }
    }

    private fun upsertMember(
        plotId: PlotId,
        playerId: PlayerId,
        role: PlotMemberRole,
        permissionMode: PermissionMode,
        addedBy: PlayerId
    ): Boolean {
        return dataSource.connection.use { conn ->
            // Check if exists
            val exists = getMember(plotId, playerId) != null
            
            if (exists) {
                conn.prepareStatement("""
                    UPDATE plot_members SET role = ?, permission_mode = ?, added_by = ?, added_at = ?
                    WHERE plot_id = ? AND player_id = ?
                """.trimIndent()).use { stmt ->
                    stmt.setString(1, role.name)
                    stmt.setString(2, permissionMode.name)
                    stmt.setObject(3, addedBy)
                    stmt.setTimestamp(4, Timestamp.from(Instant.now()))
                    stmt.setObject(5, plotId.value)
                    stmt.setObject(6, playerId)
                    stmt.executeUpdate() > 0
                }
            } else {
                conn.prepareStatement("""
                    INSERT INTO plot_members (plot_id, player_id, role, permission_mode, added_by, added_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { stmt ->
                    stmt.setObject(1, plotId.value)
                    stmt.setObject(2, playerId)
                    stmt.setString(3, role.name)
                    stmt.setString(4, permissionMode.name)
                    stmt.setObject(5, addedBy)
                    stmt.setTimestamp(6, Timestamp.from(Instant.now()))
                    stmt.executeUpdate() > 0
                }
            }
        }
    }

    private fun removeMemberEntry(plotId: PlotId, playerId: PlayerId): Boolean {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM plot_members WHERE plot_id = ? AND player_id = ?").use { stmt ->
                stmt.setObject(1, plotId.value)
                stmt.setObject(2, playerId)
                stmt.executeUpdate() > 0
            }
        }
    }

    private fun java.sql.ResultSet.toPlotMember(): PlotMember {
        return PlotMember(
            plotId = PlotId(UUID.fromString(getString("plot_id"))),
            playerId = UUID.fromString(getString("player_id")),
            role = PlotMemberRole.valueOf(getString("role")),
            permissionMode = PermissionMode.valueOf(getString("permission_mode")),
            addedAt = getTimestamp("added_at").toInstant(),
            addedBy = UUID.fromString(getString("added_by"))
        )
    }
}
