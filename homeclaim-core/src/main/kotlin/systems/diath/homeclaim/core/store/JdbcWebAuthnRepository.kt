package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.auth.WebAuthnRepository
import systems.diath.homeclaim.core.model.AccountId
import systems.diath.homeclaim.core.model.AccountWebAuthn
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * JDBC implementation of WebAuthnRepository
 */
class JdbcWebAuthnRepository(
    private val dataSource: DataSource
) : WebAuthnRepository {

    // Temporary challenge storage (in production, use Redis or similar)
    private val challengeStore = ConcurrentHashMap<AccountId, Pair<ByteArray, Instant>>()

    override fun storeChallenge(accountId: AccountId, challenge: ByteArray) {
        // Challenges expire after 5 minutes
        challengeStore[accountId] = challenge to Instant.now().plusSeconds(300)
    }

    override fun getChallenge(accountId: AccountId): ByteArray? {
        val (challenge, expiresAt) = challengeStore[accountId] ?: return null
        if (Instant.now().isAfter(expiresAt)) {
            challengeStore.remove(accountId)
            return null
        }
        challengeStore.remove(accountId) // Single use
        return challenge
    }

    override fun createCredential(
        accountId: AccountId,
        credentialId: UUID,
        publicKey: ByteArray,
        signCount: Int
    ) {
        val now = Instant.now()

        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                INSERT INTO account_webauthn 
                (id, account_id, credential_id, public_key, sign_count, created_at, last_used_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { stmt ->
                val id = UUID.randomUUID()
                stmt.setObject(1, id)
                stmt.setObject(2, accountId.value)
                stmt.setObject(3, credentialId)
                stmt.setBytes(4, publicKey)
                stmt.setInt(5, signCount)
                stmt.setTimestamp(6, Timestamp.from(now))
                stmt.setTimestamp(7, Timestamp.from(now))
                stmt.executeUpdate()
            }
        }
    }

    override fun getCredential(accountId: AccountId, credentialId: UUID): AccountWebAuthn? {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT * FROM account_webauthn WHERE account_id = ? AND credential_id = ?
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, accountId.value)
                stmt.setObject(2, credentialId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toWebAuthn() else null
                }
            }
        }
    }

    override fun listCredentials(accountId: AccountId): List<AccountWebAuthn> {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT * FROM account_webauthn WHERE account_id = ? ORDER BY created_at DESC
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, accountId.value)
                stmt.executeQuery().use { rs ->
                    val credentials = mutableListOf<AccountWebAuthn>()
                    while (rs.next()) {
                        credentials += rs.toWebAuthn()
                    }
                    credentials
                }
            }
        }
    }

    override fun updateSignCount(accountId: AccountId, credentialId: UUID, signCount: Int) {
        val now = Instant.now()

        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                UPDATE account_webauthn SET sign_count = ?, last_used_at = ? 
                WHERE account_id = ? AND credential_id = ?
            """.trimIndent()).use { stmt ->
                stmt.setInt(1, signCount)
                stmt.setTimestamp(2, Timestamp.from(now))
                stmt.setObject(3, accountId.value)
                stmt.setObject(4, credentialId)
                stmt.executeUpdate()
            }
        }
    }

    override fun deleteCredential(accountId: AccountId, credentialId: UUID): Boolean {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                DELETE FROM account_webauthn WHERE account_id = ? AND credential_id = ?
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, accountId.value)
                stmt.setObject(2, credentialId)
                stmt.executeUpdate() > 0
            }
        }
    }

    private fun ResultSet.toWebAuthn(): AccountWebAuthn {
        return AccountWebAuthn(
            id = getObject("id") as UUID,
            accountId = AccountId(getObject("account_id") as UUID),
            credentialId = getObject("credential_id") as UUID,
            publicKey = getBytes("public_key"),
            signCount = getInt("sign_count"),
            deviceName = getString("device_name"),
            createdAt = getTimestamp("created_at").toInstant(),
            lastUsedAt = getTimestamp("last_used_at").toInstant()
        )
    }
}
