package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.auth.CryptoSettingsRepository
import systems.diath.homeclaim.core.model.AccountId
import systems.diath.homeclaim.core.model.CryptoProfile
import systems.diath.homeclaim.core.model.CryptoSettings
import systems.diath.homeclaim.core.service.CryptoService
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC implementation of CryptoSettingsRepository
 */
class JdbcCryptoSettingsRepository(
    private val dataSource: DataSource,
    private val cryptoService: CryptoService
) : CryptoSettingsRepository {

    override fun getByAccountId(accountId: AccountId): CryptoSettings? {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT * FROM account_crypto_settings WHERE account_id = ?
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, accountId.value)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toCryptoSettings() else null
                }
            }
        }
    }

    override fun create(accountId: AccountId, passwordHash: String, profile: CryptoProfile): CryptoSettings {
        val now = Instant.now()
        val tokenSalt = cryptoService.generateSalt(16)

        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                INSERT INTO account_crypto_settings 
                (account_id, password_hash, password_profile, password_changed_at, token_salt, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, accountId.value)
                stmt.setString(2, passwordHash)
                stmt.setString(3, profile.name)
                stmt.setTimestamp(4, Timestamp.from(now))
                stmt.setBytes(5, tokenSalt)
                stmt.setTimestamp(6, Timestamp.from(now))
                stmt.executeUpdate()
            }
        }

        return CryptoSettings(
            accountId = accountId,
            passwordHash = passwordHash,
            passwordProfile = profile,
            passwordChangedAt = now,
            tokenSalt = tokenSalt,
            createdAt = now
        )
    }

    override fun updatePasswordHash(accountId: AccountId, passwordHash: String): Boolean {
        val now = Instant.now()

        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                UPDATE account_crypto_settings 
                SET password_hash = ?, password_changed_at = ?
                WHERE account_id = ?
            """.trimIndent()).use { stmt ->
                stmt.setString(1, passwordHash)
                stmt.setTimestamp(2, Timestamp.from(now))
                stmt.setObject(3, accountId.value)
                stmt.executeUpdate() > 0
            }
        }
    }

    override fun updateProfile(accountId: AccountId, profile: CryptoProfile): Boolean {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                UPDATE account_crypto_settings 
                SET password_profile = ?
                WHERE account_id = ?
            """.trimIndent()).use { stmt ->
                stmt.setString(1, profile.name)
                stmt.setObject(2, accountId.value)
                stmt.executeUpdate() > 0
            }
        }
    }

    override fun delete(accountId: AccountId): Boolean {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                DELETE FROM account_crypto_settings WHERE account_id = ?
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, accountId.value)
                stmt.executeUpdate() > 0
            }
        }
    }

    private fun ResultSet.toCryptoSettings(): CryptoSettings {
        return CryptoSettings(
            accountId = AccountId(getObject("account_id") as UUID),
            passwordHash = getString("password_hash"),
            passwordProfile = CryptoProfile.valueOf(getString("password_profile")),
            passwordChangedAt = getTimestamp("password_changed_at").toInstant(),
            tokenSalt = getBytes("token_salt"),
            createdAt = getTimestamp("created_at").toInstant()
        )
    }
}
