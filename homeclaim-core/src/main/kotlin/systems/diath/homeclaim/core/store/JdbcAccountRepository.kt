package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.model.*
import systems.diath.homeclaim.core.service.AccountService
import systems.diath.homeclaim.core.service.AccountUpdate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcAccountRepository(
    private val dataSource: DataSource
) : AccountService {

    override fun getAccountById(accountId: AccountId): Account? {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM accounts WHERE id = ?").use { stmt ->
                stmt.setObject(1, accountId.value)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toAccount() else null
                }
            }
        }
    }

    override fun getAccountByPlayerId(playerId: PlayerId): Account? {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM accounts WHERE player_id = ?").use { stmt ->
                stmt.setObject(1, playerId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toAccount() else null
                }
            }
        }
    }

    override fun getAccountByUsername(username: String): Account? {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM accounts WHERE LOWER(username) = LOWER(?)").use { stmt ->
                stmt.setString(1, username)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toAccount() else null
                }
            }
        }
    }

    override fun getAccountByEmail(email: String): Account? {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM accounts WHERE LOWER(email) = LOWER(?)").use { stmt ->
                stmt.setString(1, email)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toAccount() else null
                }
            }
        }
    }

    override fun createAccount(playerId: PlayerId, username: String, email: String?): Account {
        val accountId = AccountId(UUID.randomUUID())
        val now = Instant.now()
        
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                INSERT INTO accounts (id, player_id, username, email, email_verified, locale, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, false, 'de', 'ACTIVE', ?, ?)
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, accountId.value)
                stmt.setObject(2, playerId)
                stmt.setString(3, username)
                stmt.setString(4, email)
                stmt.setTimestamp(5, Timestamp.from(now))
                stmt.setTimestamp(6, Timestamp.from(now))
                stmt.executeUpdate()
            }
        }
        
        return getAccountById(accountId)!!
    }

    override fun updateAccount(accountId: AccountId, update: AccountUpdate): Account? {
        val setters = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        
        update.username?.let { setters += "username = ?"; values += it }
        update.email?.let { setters += "email = ?"; values += it; setters += "email_verified = ?"; values += false }
        update.avatarUrl?.let { setters += "avatar_url = ?"; values += it }
        update.locale?.let { setters += "locale = ?"; values += it }
        
        if (setters.isEmpty()) return getAccountById(accountId)
        
        setters += "updated_at = ?"
        values += Timestamp.from(Instant.now())
        values += accountId.value
        
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                UPDATE accounts SET ${setters.joinToString(", ")} WHERE id = ?
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
        
        return getAccountById(accountId)
    }

    override fun deleteAccount(accountId: AccountId): Boolean {
        return updateStatus(accountId, AccountStatus.DELETED)
    }

    override fun suspendAccount(accountId: AccountId, reason: String): Boolean {
        return updateStatus(accountId, AccountStatus.SUSPENDED)
    }

    override fun reactivateAccount(accountId: AccountId): Boolean {
        return updateStatus(accountId, AccountStatus.ACTIVE)
    }

    override fun verifyEmail(accountId: AccountId): Boolean {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE accounts SET email_verified = true, updated_at = ? WHERE id = ?").use { stmt ->
                stmt.setTimestamp(1, Timestamp.from(Instant.now()))
                stmt.setObject(2, accountId.value)
                stmt.executeUpdate() > 0
            }
        }
    }

    override fun isEmailTaken(email: String): Boolean {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT 1 FROM accounts WHERE LOWER(email) = LOWER(?) AND status != 'DELETED'").use { stmt ->
                stmt.setString(1, email)
                stmt.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    override fun isUsernameTaken(username: String): Boolean {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT 1 FROM accounts WHERE LOWER(username) = LOWER(?) AND status != 'DELETED'").use { stmt ->
                stmt.setString(1, username)
                stmt.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    private fun updateStatus(accountId: AccountId, status: AccountStatus): Boolean {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE accounts SET status = ?, updated_at = ? WHERE id = ?").use { stmt ->
                stmt.setString(1, status.name)
                stmt.setTimestamp(2, Timestamp.from(Instant.now()))
                stmt.setObject(3, accountId.value)
                stmt.executeUpdate() > 0
            }
        }
    }

    private fun java.sql.ResultSet.toAccount(): Account {
        return Account(
            id = AccountId(UUID.fromString(getString("id"))),
            playerId = UUID.fromString(getString("player_id")),
            username = getString("username"),
            email = getString("email"),
            emailVerified = getBoolean("email_verified"),
            avatarUrl = getString("avatar_url"),
            locale = getString("locale") ?: "de",
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
            lastLoginAt = getTimestamp("last_login_at")?.toInstant(),
            status = AccountStatus.valueOf(getString("status") ?: "ACTIVE")
        )
    }
}
