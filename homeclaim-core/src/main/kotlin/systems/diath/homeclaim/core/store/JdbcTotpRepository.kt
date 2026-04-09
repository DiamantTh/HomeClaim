package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.auth.TotpRepository
import systems.diath.homeclaim.core.model.AccountId
import systems.diath.homeclaim.core.model.AccountTotp
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC implementation of TotpRepository
 */
class JdbcTotpRepository(
    private val dataSource: DataSource
) : TotpRepository {

    override fun getTotpSettings(accountId: AccountId): AccountTotp? {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT * FROM account_totp WHERE account_id = ? AND enabled = true
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, accountId.value)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toTotp() else null
                }
            }
        }
    }

    override fun getPendingTotp(accountId: AccountId): AccountTotp? {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT * FROM account_totp WHERE account_id = ? AND enabled = false
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, accountId.value)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toTotp() else null
                }
            }
        }
    }

    override fun createPending(accountId: AccountId, secret: ByteArray, backupCodes: List<String>) {
        val id = UUID.randomUUID()
        val now = Instant.now()

        dataSource.connection.use { conn ->
            // Delete any existing pending TOTP
            conn.prepareStatement("DELETE FROM account_totp WHERE account_id = ? AND enabled = false").use { stmt ->
                stmt.setObject(1, accountId.value)
                stmt.executeUpdate()
            }

            // Insert new pending TOTP
            conn.prepareStatement("""
                INSERT INTO account_totp 
                (id, account_id, secret, backup_codes, enabled, created_at)
                VALUES (?, ?, ?, ?, false, ?)
            """.trimIndent()).use { stmt ->
                stmt.setObject(1, id)
                stmt.setObject(2, accountId.value)
                stmt.setBytes(3, secret)
                stmt.setString(4, backupCodes.joinToString(","))
                stmt.setTimestamp(5, Timestamp.from(now))
                stmt.executeUpdate()
            }
        }
    }

    override fun enableTotp(accountId: AccountId) {
        val now = Instant.now()

        dataSource.connection.use { conn ->
            // Delete any existing enabled TOTP
            conn.prepareStatement("DELETE FROM account_totp WHERE account_id = ? AND enabled = true").use { stmt ->
                stmt.setObject(1, accountId.value)
                stmt.executeUpdate()
            }

            // Enable the pending TOTP
            conn.prepareStatement("""
                UPDATE account_totp SET enabled = true, enabled_at = ? WHERE account_id = ? AND enabled = false
            """.trimIndent()).use { stmt ->
                stmt.setTimestamp(1, Timestamp.from(now))
                stmt.setObject(2, accountId.value)
                stmt.executeUpdate()
            }
        }
    }

    override fun deleteTotp(accountId: AccountId) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM account_totp WHERE account_id = ?").use { stmt ->
                stmt.setObject(1, accountId.value)
                stmt.executeUpdate()
            }
        }
    }

    override fun useBackupCode(accountId: AccountId, code: String): Boolean {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT id, backup_codes FROM account_totp WHERE account_id = ? AND enabled = true
            """.trimIndent()).use useStmt@{ stmt ->
                stmt.setObject(1, accountId.value)
                stmt.executeQuery().use useRs@{ rs ->
                    if (!rs.next()) return@useRs false

                    val id = rs.getObject("id") as UUID
                    val codesRaw = rs.getString("backup_codes") ?: return@useRs false
                    val codes = codesRaw.split(",").toMutableList()

                    // Check if code exists (case-insensitive)
                    val matchIndex = codes.indexOfFirst { it.equals(code, ignoreCase = true) }
                    if (matchIndex == -1) return@useRs false

                    // Remove used code
                    codes.removeAt(matchIndex)

                    // Update backup codes
                    conn.prepareStatement("UPDATE account_totp SET backup_codes = ? WHERE id = ?").use { updateStmt ->
                        updateStmt.setString(1, codes.joinToString(","))
                        updateStmt.setObject(2, id)
                        updateStmt.executeUpdate() > 0
                    }
                }
            }
        }
    }

    private fun ResultSet.toTotp(): AccountTotp {
        val codesRaw = getString("backup_codes")
        val codes = if (codesRaw.isNullOrBlank()) emptyList() else codesRaw.split(",")

        return AccountTotp(
            id = getObject("id") as UUID,
            accountId = AccountId(getObject("account_id") as UUID),
            secret = getBytes("secret"),
            backupCodes = codes,
            enabled = getBoolean("enabled"),
            createdAt = getTimestamp("created_at").toInstant(),
            enabledAt = getTimestamp("enabled_at")?.toInstant()
        )
    }
}
