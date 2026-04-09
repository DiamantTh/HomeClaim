package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.model.*
import systems.diath.homeclaim.core.service.AccountAuditService
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC implementation of AccountAuditService
 */
class JdbcAccountAuditRepository(
    private val dataSource: DataSource
) : AccountAuditService {

    override fun log(
        accountId: AccountId,
        action: AccountAuditAction,
        details: String?,
        ipAddress: String?,
        userAgent: String?
    ) {
        val id = UUID.randomUUID()
        val now = Instant.now()

        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                INSERT INTO account_audit_log 
                (id, account_id, action, details, ip_address, user_agent, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, id)
                stmt.setObject(2, accountId.value)
                stmt.setString(3, action.name)
                stmt.setString(4, details)
                stmt.setString(5, ipAddress)
                stmt.setString(6, userAgent)
                stmt.setTimestamp(7, Timestamp.from(now))
                stmt.executeUpdate()
            }
        }
    }

    override fun getAuditLog(
        accountId: AccountId,
        limit: Int,
        offset: Int
    ): List<AccountAuditEntry> {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT * FROM account_audit_log 
                WHERE account_id = ? 
                ORDER BY created_at DESC 
                LIMIT ? OFFSET ?
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, accountId.value)
                stmt.setInt(2, limit)
                stmt.setInt(3, offset)
                stmt.executeQuery().use { rs ->
                    val entries = mutableListOf<AccountAuditEntry>()
                    while (rs.next()) {
                        entries += rs.toAuditEntry()
                    }
                    entries
                }
            }
        }
    }

    override fun getAuditLogByAction(
        accountId: AccountId,
        action: AccountAuditAction,
        limit: Int
    ): List<AccountAuditEntry> {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT * FROM account_audit_log 
                WHERE account_id = ? AND action = ?
                ORDER BY created_at DESC 
                LIMIT ?
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, accountId.value)
                stmt.setString(2, action.name)
                stmt.setInt(3, limit)
                stmt.executeQuery().use { rs ->
                    val entries = mutableListOf<AccountAuditEntry>()
                    while (rs.next()) {
                        entries += rs.toAuditEntry()
                    }
                    entries
                }
            }
        }
    }

    override fun getRecentLogins(accountId: AccountId, limit: Int): List<AccountAuditEntry> {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT * FROM account_audit_log 
                WHERE account_id = ? AND action IN ('LOGIN_SUCCESS', 'LOGIN_FAILED')
                ORDER BY created_at DESC 
                LIMIT ?
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, accountId.value)
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    val entries = mutableListOf<AccountAuditEntry>()
                    while (rs.next()) {
                        entries += rs.toAuditEntry()
                    }
                    entries
                }
            }
        }
    }

    override fun getSecurityEvents(accountId: AccountId, since: Instant): List<AccountAuditEntry> {
        val securityActions = listOf(
            "LOGIN_FAILED", "PASSWORD_CHANGED", "PASSWORD_RESET", "TOTP_ENABLED",
            "TOTP_DISABLED", "WEBAUTHN_ADDED", "WEBAUTHN_REMOVED", "SESSION_REVOKED_ALL"
        )

        return dataSource.connection.use { conn ->
            val placeholders = securityActions.joinToString(",") { "?" }
            conn.prepareStatement("""
                SELECT * FROM account_audit_log 
                WHERE account_id = ? AND action IN ($placeholders) AND created_at > ?
                ORDER BY created_at DESC
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, accountId.value)
                securityActions.forEachIndexed { index, action ->
                    stmt.setString(index + 2, action)
                }
                stmt.setTimestamp(securityActions.size + 2, Timestamp.from(since))
                stmt.executeQuery().use { rs ->
                    val entries = mutableListOf<AccountAuditEntry>()
                    while (rs.next()) {
                        entries += rs.toAuditEntry()
                    }
                    entries
                }
            }
        }
    }

    override fun countFailedLogins(accountId: AccountId, since: Instant): Int {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT COUNT(*) FROM account_audit_log 
                WHERE account_id = ? AND action = 'LOGIN_FAILED' AND created_at > ?
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, accountId.value)
                stmt.setTimestamp(2, Timestamp.from(since))
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    override fun cleanupOldEntries(olderThan: Instant): Int {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                DELETE FROM account_audit_log WHERE created_at < ?
            """.trimIndent()).use { stmt ->
                stmt.setTimestamp(1, Timestamp.from(olderThan))
                stmt.executeUpdate()
            }
        }
    }

    private fun ResultSet.toAuditEntry(): AccountAuditEntry {
        return AccountAuditEntry(
            id = getObject("id") as UUID,
            accountId = AccountId(getObject("account_id") as UUID),
            action = AccountAuditAction.valueOf(getString("action")),
            details = getString("details"),
            ipAddress = getString("ip_address"),
            userAgent = getString("user_agent"),
            createdAt = getTimestamp("created_at").toInstant()
        )
    }
}
