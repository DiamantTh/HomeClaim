package systems.diath.homeclaim.core.model

import java.time.Instant
import java.util.UUID

/**
 * User account for web access
 */
data class Account(
    val id: AccountId,
    val playerId: PlayerId,
    val username: String,
    val email: String?,
    val emailVerified: Boolean,
    val avatarUrl: String?,
    val locale: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastLoginAt: Instant?,
    val status: AccountStatus
)

enum class AccountStatus {
    ACTIVE,
    SUSPENDED,
    DELETED
}

/**
 * Crypto settings per account (Argon2id profiles)
 */
data class CryptoSettings(
    val accountId: AccountId,
    val passwordHash: String,
    val passwordProfile: CryptoProfile,
    val passwordChangedAt: Instant,
    val tokenSalt: ByteArray,
    val createdAt: Instant
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CryptoSettings
        return accountId == other.accountId
    }

    override fun hashCode(): Int = accountId.hashCode()
}

/**
 * Argon2id profiles (InspIRCd-inspired)
 */
enum class CryptoProfile(
    val memoryCostKiB: Int,
    val timeCost: Int,
    val parallelism: Int,
    val hashLength: Int
) {
    LOW(32 * 1024, 3, 1, 32),           // 32 MiB - Low-end
    STANDARD(64 * 1024, 3, 2, 32),      // 64 MiB - Default
    HIGH(96 * 1024, 4, 2, 32),          // 96 MiB - High security
    PARANOID(128 * 1024, 5, 4, 64)      // 128 MiB - Maximum
}

/**
 * Session token
 */
data class AccountSession(
    val id: UUID,
    val accountId: AccountId,
    val tokenHash: String,
    val userAgent: String?,
    val ipAddress: String?,
    val createdAt: Instant,
    val lastUsedAt: Instant,
    val expiresAt: Instant,
    val revoked: Boolean = false
)

/**
 * TOTP (2FA)
 */
data class AccountTotp(
    val id: UUID,
    val accountId: AccountId,
    val secret: ByteArray,
    val backupCodes: List<String>,
    val enabled: Boolean,
    val createdAt: Instant,
    val enabledAt: Instant?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AccountTotp
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * WebAuthn credential
 */
data class AccountWebAuthn(
    val id: UUID,
    val accountId: AccountId,
    val credentialId: UUID,
    val publicKey: ByteArray,
    val signCount: Int,
    val deviceName: String?,
    val createdAt: Instant,
    val lastUsedAt: Instant
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AccountWebAuthn
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Magic link for passwordless login
 */
data class MagicLink(
    val id: UUID,
    val accountId: AccountId,
    val tokenHash: String,
    val purpose: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val usedAt: Instant?
)

/**
 * OAuth connection
 */
data class OAuthConnection(
    val id: UUID,
    val accountId: AccountId,
    val provider: OAuthProvider,
    val providerUserId: String,
    val accessToken: String?,
    val refreshToken: String?,
    val expiresAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)

enum class OAuthProvider {
    DISCORD,
    GITHUB,
    GOOGLE,
    MICROSOFT
}

/**
 * Account audit log entry
 */
data class AccountAuditEntry(
    val id: UUID,
    val accountId: AccountId,
    val action: AccountAuditAction,
    val ipAddress: String?,
    val userAgent: String?,
    val details: String?,
    val createdAt: Instant
)

enum class AccountAuditAction {
    LOGIN_SUCCESS,
    LOGIN_FAILED,
    LOGOUT,
    PASSWORD_CHANGED,
    PASSWORD_CHANGE_FAILED,
    PASSWORD_RESET,
    PASSWORD_RESET_REQUESTED,
    EMAIL_CHANGED,
    TOTP_ENABLED,
    TOTP_DISABLED,
    WEBAUTHN_ADDED,
    WEBAUTHN_REMOVED,
    OAUTH_LINKED,
    OAUTH_UNLINKED,
    SESSION_REVOKED,
    SESSION_REVOKED_ALL,
    ACCOUNT_CREATED,
    ACCOUNT_SUSPENDED,
    ACCOUNT_REACTIVATED,
    ACCOUNT_DELETED
}
