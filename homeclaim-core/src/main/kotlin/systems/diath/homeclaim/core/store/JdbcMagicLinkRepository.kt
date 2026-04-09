package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.auth.MagicLinkRepository
import systems.diath.homeclaim.core.model.AccountId
import systems.diath.homeclaim.core.model.MagicLink
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC implementation of MagicLinkRepository
 */
class JdbcMagicLinkRepository(
    private val dataSource: DataSource
) : MagicLinkRepository {

    override fun create(accountId: AccountId, token: String, purpose: String, expiresAt: Instant): MagicLink {
        val id = UUID.randomUUID()
        val now = Instant.now()

        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                INSERT INTO magic_links 
                (id, account_id, token_hash, purpose, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { stmt ->
                // Store token hash, not raw token (for security)
                val tokenHash = hashToken(token)
                stmt.setObject(1, id)
                stmt.setObject(2, accountId.value)
                stmt.setString(3, tokenHash)
                stmt.setString(4, purpose)
                stmt.setTimestamp(5, Timestamp.from(now))
                stmt.setTimestamp(6, Timestamp.from(expiresAt))
                stmt.executeUpdate()
            }
        }

        return MagicLink(
            id = id,
            accountId = accountId,
            tokenHash = hashToken(token),
            purpose = purpose,
            createdAt = now,
            expiresAt = expiresAt,
            usedAt = null
        )
    }

    override fun getByToken(token: String): MagicLink? {
        val tokenHash = hashToken(token)

        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT * FROM magic_links WHERE token_hash = ?
            """.trimIndent()).use { stmt ->
                stmt.setString(1, tokenHash)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toMagicLink() else null
                }
            }
        }
    }

    override fun markUsed(id: UUID) {
        val now = Instant.now()

        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE magic_links SET used_at = ? WHERE id = ?").use { stmt ->
                stmt.setTimestamp(1, Timestamp.from(now))
                stmt.setObject(2, id)
                stmt.executeUpdate()
            }
        }
    }

    override fun cleanupExpired() {
        val now = Instant.now()

        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                DELETE FROM magic_links WHERE expires_at < ? OR used_at IS NOT NULL
            """.trimIndent()).use { stmt ->
                stmt.setTimestamp(1, Timestamp.from(now))
                stmt.executeUpdate()
            }
        }
    }

    private fun hashToken(token: String): String {
        // Simple SHA-256 hash for token lookup
        // The actual token security comes from its randomness
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(token.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }

    private fun ResultSet.toMagicLink(): MagicLink {
        return MagicLink(
            id = getObject("id") as UUID,
            accountId = AccountId(getObject("account_id") as UUID),
            tokenHash = getString("token_hash"),
            purpose = getString("purpose"),
            createdAt = getTimestamp("created_at").toInstant(),
            expiresAt = getTimestamp("expires_at").toInstant(),
            usedAt = getTimestamp("used_at")?.toInstant()
        )
    }
}
