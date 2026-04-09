package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.model.*
import systems.diath.homeclaim.core.service.CryptoService
import systems.diath.homeclaim.core.service.SessionService
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC implementation of SessionService
 */
class JdbcSessionRepository(
    private val dataSource: DataSource,
    private val cryptoService: CryptoService
) : SessionService {

    override fun createSession(
        accountId: AccountId,
        userAgent: String?,
        ipAddress: String?,
        expiresAt: Instant
    ): Pair<AccountSession, String> {
        val sessionId = UUID.randomUUID()
        val token = cryptoService.generateToken(32)
        val tokenHash = cryptoService.hashToken(token)
        val now = Instant.now()

        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                INSERT INTO account_sessions 
                (id, account_id, token_hash, user_agent, ip_address, created_at, last_used_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, sessionId)
                stmt.setObject(2, accountId.value)
                stmt.setString(3, tokenHash)
                stmt.setString(4, userAgent)
                stmt.setString(5, ipAddress)
                stmt.setTimestamp(6, Timestamp.from(now))
                stmt.setTimestamp(7, Timestamp.from(now))
                stmt.setTimestamp(8, Timestamp.from(expiresAt))
                stmt.executeUpdate()
            }
        }

        val session = AccountSession(
            id = sessionId,
            accountId = accountId,
            tokenHash = tokenHash,
            userAgent = userAgent,
            ipAddress = ipAddress,
            createdAt = now,
            lastUsedAt = now,
            expiresAt = expiresAt,
            revoked = false
        )

        return session to token
    }

    override fun validateToken(token: String): AccountSession? {
        val tokenHash = cryptoService.hashToken(token)
        
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT * FROM account_sessions 
                WHERE token_hash = ? AND revoked = false AND expires_at > ?
            """.trimIndent()).use { stmt ->
                stmt.setString(1, tokenHash)
                stmt.setTimestamp(2, Timestamp.from(Instant.now()))
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val session = rs.toSession()
                        // Update last_used_at
                        updateLastUsed(session.id)
                        session
                    } else null
                }
            }
        }
    }

    override fun getSession(sessionId: UUID): AccountSession? {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM account_sessions WHERE id = ?").use { stmt ->
                stmt.setObject(1, sessionId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toSession() else null
                }
            }
        }
    }

    override fun listSessions(accountId: AccountId): List<AccountSession> {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT * FROM account_sessions 
                WHERE account_id = ? AND revoked = false 
                ORDER BY last_used_at DESC
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, accountId.value)
                stmt.executeQuery().use { rs ->
                    val sessions = mutableListOf<AccountSession>()
                    while (rs.next()) {
                        sessions += rs.toSession()
                    }
                    sessions
                }
            }
        }
    }

    override fun revokeSession(sessionId: UUID): Boolean {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE account_sessions SET revoked = true WHERE id = ?").use { stmt ->
                stmt.setObject(1, sessionId)
                stmt.executeUpdate() > 0
            }
        }
    }

    override fun revokeAllSessions(accountId: AccountId): Int {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE account_sessions SET revoked = true WHERE account_id = ?").use { stmt ->
                stmt.setObject(1, accountId.value)
                stmt.executeUpdate()
            }
        }
    }

    override fun revokeOtherSessions(accountId: AccountId, currentSessionId: UUID): Int {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                UPDATE account_sessions SET revoked = true 
                WHERE account_id = ? AND id != ?
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, accountId.value)
                stmt.setObject(2, currentSessionId)
                stmt.executeUpdate()
            }
        }
    }

    override fun updateLastUsed(sessionId: UUID): Boolean {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                UPDATE account_sessions SET last_used_at = ? WHERE id = ?
            """.trimIndent()).use { stmt ->
                stmt.setTimestamp(1, Timestamp.from(Instant.now()))
                stmt.setObject(2, sessionId)
                stmt.executeUpdate() > 0
            }
        }
    }

    override fun cleanupExpiredSessions(): Int {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                DELETE FROM account_sessions WHERE expires_at < ? OR revoked = true
            """.trimIndent()).use { stmt ->
                stmt.setTimestamp(1, Timestamp.from(Instant.now()))
                stmt.executeUpdate()
            }
        }
    }

    private fun ResultSet.toSession(): AccountSession {
        return AccountSession(
            id = getObject("id") as UUID,
            accountId = AccountId(getObject("account_id") as UUID),
            tokenHash = getString("token_hash"),
            userAgent = getString("user_agent"),
            ipAddress = getString("ip_address"),
            createdAt = getTimestamp("created_at").toInstant(),
            lastUsedAt = getTimestamp("last_used_at").toInstant(),
            expiresAt = getTimestamp("expires_at").toInstant(),
            revoked = getBoolean("revoked")
        )
    }
}
