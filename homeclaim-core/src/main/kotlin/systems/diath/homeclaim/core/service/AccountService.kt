package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.*
import java.time.Instant

/**
 * Account service for user management
 */
interface AccountService {
    // CRUD
    fun getAccountById(accountId: AccountId): Account?
    fun getAccountByPlayerId(playerId: PlayerId): Account?
    fun getAccountByUsername(username: String): Account?
    fun getAccountByEmail(email: String): Account?
    fun createAccount(playerId: PlayerId, username: String, email: String?): Account
    fun updateAccount(accountId: AccountId, update: AccountUpdate): Account?
    fun deleteAccount(accountId: AccountId): Boolean
    fun suspendAccount(accountId: AccountId, reason: String): Boolean
    fun reactivateAccount(accountId: AccountId): Boolean
    
    // Verification
    fun verifyEmail(accountId: AccountId): Boolean
    fun isEmailTaken(email: String): Boolean
    fun isUsernameTaken(username: String): Boolean
}

data class AccountUpdate(
    val username: String? = null,
    val email: String? = null,
    val avatarUrl: String? = null,
    val locale: String? = null
)

/**
 * Crypto service for password hashing and token generation
 */
interface CryptoService {
    // Password hashing (Argon2id)
    fun hashPassword(password: String, profile: CryptoProfile = CryptoProfile.STANDARD): String
    fun verifyPassword(password: String, hash: String): Boolean
    fun needsRehash(hash: String, targetProfile: CryptoProfile): Boolean
    
    // Token generation (BLAKE2b)
    fun generateToken(length: Int = 32): String
    fun hashToken(token: String): String  // Uses fixed salt/key derivation
    fun hashToken(token: String, salt: ByteArray): String
    fun verifyToken(token: String, hash: String): Boolean
    fun verifyToken(token: String, hash: String, salt: ByteArray): Boolean
    
    // Salt generation
    fun generateSalt(length: Int = 16): ByteArray
    
    // TOTP
    fun generateTotpSecret(): ByteArray
    fun generateTotpCode(secret: ByteArray, time: Long = System.currentTimeMillis()): String
    fun verifyTotpCode(secret: ByteArray, code: String, windowSize: Int = 1): Boolean
    fun generateBackupCodes(count: Int = 10): List<String>
}

/**
 * Session service
 */
interface SessionService {
    fun createSession(
        accountId: AccountId,
        userAgent: String?,
        ipAddress: String?,
        expiresAt: Instant = Instant.now().plusSeconds(7 * 24 * 3600)
    ): Pair<AccountSession, String> // Returns session + raw token
    
    fun validateToken(token: String): AccountSession?
    fun getSession(sessionId: java.util.UUID): AccountSession?
    fun listSessions(accountId: AccountId): List<AccountSession>
    fun revokeSession(sessionId: java.util.UUID): Boolean
    fun revokeAllSessions(accountId: AccountId): Int
    fun revokeOtherSessions(accountId: AccountId, currentSessionId: java.util.UUID): Int
    fun updateLastUsed(sessionId: java.util.UUID): Boolean
    fun cleanupExpiredSessions(): Int
}

/**
 * Account audit service
 */
interface AccountAuditService {
    fun log(
        accountId: AccountId,
        action: AccountAuditAction,
        details: String? = null,
        ipAddress: String? = null,
        userAgent: String? = null
    )
    
    fun getAuditLog(accountId: AccountId, limit: Int = 50, offset: Int = 0): List<AccountAuditEntry>
    fun getAuditLogByAction(accountId: AccountId, action: AccountAuditAction, limit: Int = 20): List<AccountAuditEntry>
    fun getRecentLogins(accountId: AccountId, limit: Int = 10): List<AccountAuditEntry>
    fun getSecurityEvents(accountId: AccountId, since: Instant): List<AccountAuditEntry>
    fun countFailedLogins(accountId: AccountId, since: Instant): Int
    fun cleanupOldEntries(olderThan: Instant): Int
}

/**
 * Authentication service (combines account, crypto, session)
 */
interface AuthService {
    // Login
    fun login(
        usernameOrEmail: String,
        password: String,
        totpCode: String?,
        userAgent: String?,
        ipAddress: String?
    ): AuthResult
    
    fun loginWithMagicLink(token: String, userAgent: String?, ipAddress: String?): AuthResult
    fun loginWithOAuth(provider: OAuthProvider, code: String, userAgent: String?, ipAddress: String?): AuthResult
    
    // Logout
    fun logout(sessionToken: String): Boolean
    fun logoutAll(accountId: AccountId): Int
    
    // Password
    fun changePassword(accountId: AccountId, currentPassword: String, newPassword: String): Boolean
    fun resetPassword(accountId: AccountId, newPassword: String): Boolean
    fun requestPasswordReset(email: String): Boolean
    
    // Magic Link
    fun sendMagicLink(email: String): Boolean
    
    // TOTP
    fun enableTotp(accountId: AccountId): TotpSetupResult
    fun verifyTotpSetup(accountId: AccountId, code: String): Boolean
    fun disableTotp(accountId: AccountId, password: String): Boolean
    
    // WebAuthn
    fun beginWebAuthnRegistration(accountId: AccountId): WebAuthnRegistrationChallenge
    fun completeWebAuthnRegistration(accountId: AccountId, response: WebAuthnRegistrationResponse): Boolean
    fun beginWebAuthnLogin(usernameOrEmail: String): WebAuthnLoginChallenge
    fun completeWebAuthnLogin(response: WebAuthnLoginResponse, userAgent: String?, ipAddress: String?): AuthResult
    fun removeWebAuthnCredential(accountId: AccountId, credentialId: java.util.UUID): Boolean
    fun listWebAuthnCredentials(accountId: AccountId): List<AccountWebAuthn>
}

sealed class AuthResult {
    data class Success(val session: AccountSession, val token: String) : AuthResult()
    data class TotpRequired(val accountId: AccountId) : AuthResult()
    data class WebAuthnRequired(val challenge: WebAuthnLoginChallenge) : AuthResult()
    data class Failure(val reason: AuthFailureReason) : AuthResult()
}

enum class AuthFailureReason {
    INVALID_CREDENTIALS,
    ACCOUNT_SUSPENDED,
    ACCOUNT_DELETED,
    TOTP_INVALID,
    WEBAUTHN_INVALID,
    TOKEN_EXPIRED,
    TOKEN_INVALID,
    RATE_LIMITED
}

data class TotpSetupResult(
    val secret: ByteArray,
    val qrCodeUrl: String,
    val backupCodes: List<String>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TotpSetupResult
        return secret.contentEquals(other.secret)
    }

    override fun hashCode(): Int = secret.contentHashCode()
}

// WebAuthn types (simplified)
data class WebAuthnRegistrationChallenge(
    val challenge: ByteArray,
    val rpId: String,
    val rpName: String,
    val userId: ByteArray,
    val userName: String
)

data class WebAuthnRegistrationResponse(
    val credentialId: ByteArray,
    val publicKey: ByteArray,
    val attestationObject: ByteArray,
    val clientDataJson: ByteArray
)

data class WebAuthnLoginChallenge(
    val challenge: ByteArray,
    val rpId: String,
    val allowCredentials: List<ByteArray>
)

data class WebAuthnLoginResponse(
    val credentialId: ByteArray,
    val authenticatorData: ByteArray,
    val signature: ByteArray,
    val clientDataJson: ByteArray
)
