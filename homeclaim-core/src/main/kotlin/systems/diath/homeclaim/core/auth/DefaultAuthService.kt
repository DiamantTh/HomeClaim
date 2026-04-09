package systems.diath.homeclaim.core.auth

import systems.diath.homeclaim.core.model.*
import systems.diath.homeclaim.core.service.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Main authentication service implementation
 * Combines account, crypto, session, and audit services
 */
class DefaultAuthService(
    private val accountService: AccountService,
    private val cryptoSettingsRepository: CryptoSettingsRepository,
    private val cryptoService: CryptoService,
    private val sessionService: SessionService,
    private val auditService: AccountAuditService,
    private val totpRepository: TotpRepository,
    private val webAuthnRepository: WebAuthnRepository,
    private val magicLinkRepository: MagicLinkRepository
) : AuthService {

    companion object {
        private const val MAX_FAILED_LOGINS = 5
        private val LOCKOUT_DURATION = ChronoUnit.MINUTES to 15L
        private val SESSION_DURATION = ChronoUnit.DAYS to 7L
    }

    // ============ LOGIN ============

    override fun login(
        usernameOrEmail: String,
        password: String,
        totpCode: String?,
        userAgent: String?,
        ipAddress: String?
    ): AuthResult {
        // Find account
        val account = accountService.getAccountByUsername(usernameOrEmail)
            ?: accountService.getAccountByEmail(usernameOrEmail)
            ?: run {
                // Log failed attempt anyway (without account ID)
                return AuthResult.Failure(AuthFailureReason.INVALID_CREDENTIALS)
            }

        // Check rate limiting
        val failedCount = auditService.countFailedLogins(
            account.id,
            Instant.now().minus(LOCKOUT_DURATION.second, LOCKOUT_DURATION.first)
        )
        if (failedCount >= MAX_FAILED_LOGINS) {
            auditService.log(account.id, AccountAuditAction.LOGIN_FAILED, "Rate limited", ipAddress, userAgent)
            return AuthResult.Failure(AuthFailureReason.RATE_LIMITED)
        }

        // Check account status
        when (account.status) {
            AccountStatus.SUSPENDED -> {
                auditService.log(account.id, AccountAuditAction.LOGIN_FAILED, "Account suspended", ipAddress, userAgent)
                return AuthResult.Failure(AuthFailureReason.ACCOUNT_SUSPENDED)
            }
            AccountStatus.DELETED -> {
                auditService.log(account.id, AccountAuditAction.LOGIN_FAILED, "Account deleted", ipAddress, userAgent)
                return AuthResult.Failure(AuthFailureReason.ACCOUNT_DELETED)
            }
            AccountStatus.ACTIVE -> { /* continue */ }
        }

        // Get crypto settings
        val cryptoSettings = cryptoSettingsRepository.getByAccountId(account.id)
            ?: run {
                auditService.log(account.id, AccountAuditAction.LOGIN_FAILED, "No crypto settings", ipAddress, userAgent)
                return AuthResult.Failure(AuthFailureReason.INVALID_CREDENTIALS)
            }

        // Verify password
        if (!cryptoService.verifyPassword(password, cryptoSettings.passwordHash)) {
            auditService.log(account.id, AccountAuditAction.LOGIN_FAILED, "Invalid password", ipAddress, userAgent)
            return AuthResult.Failure(AuthFailureReason.INVALID_CREDENTIALS)
        }

        // Check if TOTP is enabled
        val totp = totpRepository.getTotpSettings(account.id)
        if (totp != null && totp.enabled) {
            if (totpCode == null) {
                return AuthResult.TotpRequired(account.id)
            }
            if (!cryptoService.verifyTotpCode(totp.secret, totpCode)) {
                // Check backup codes
                if (!totpRepository.useBackupCode(account.id, totpCode)) {
                    auditService.log(account.id, AccountAuditAction.LOGIN_FAILED, "Invalid TOTP code", ipAddress, userAgent)
                    return AuthResult.Failure(AuthFailureReason.TOTP_INVALID)
                }
            }
        }

        // Create session
        val (session, token) = sessionService.createSession(
            account.id,
            userAgent,
            ipAddress,
            Instant.now().plus(SESSION_DURATION.second, SESSION_DURATION.first)
        )

        auditService.log(account.id, AccountAuditAction.LOGIN_SUCCESS, null, ipAddress, userAgent)

        // Check if password needs rehash
        if (cryptoService.needsRehash(cryptoSettings.passwordHash, cryptoSettings.passwordProfile)) {
            val newHash = cryptoService.hashPassword(password, cryptoSettings.passwordProfile)
            cryptoSettingsRepository.updatePasswordHash(account.id, newHash)
        }

        return AuthResult.Success(session, token)
    }

    override fun loginWithMagicLink(token: String, userAgent: String?, ipAddress: String?): AuthResult {
        val magicLink = magicLinkRepository.getByToken(token) ?: return AuthResult.Failure(AuthFailureReason.TOKEN_INVALID)

        if (magicLink.usedAt != null) {
            return AuthResult.Failure(AuthFailureReason.TOKEN_INVALID)
        }

        if (magicLink.expiresAt.isBefore(Instant.now())) {
            return AuthResult.Failure(AuthFailureReason.TOKEN_EXPIRED)
        }

        // Mark as used
        magicLinkRepository.markUsed(magicLink.id)

        val account = accountService.getAccountById(magicLink.accountId)
            ?: return AuthResult.Failure(AuthFailureReason.INVALID_CREDENTIALS)

        // Create session
        val (session, sessionToken) = sessionService.createSession(
            account.id,
            userAgent,
            ipAddress,
            Instant.now().plus(SESSION_DURATION.second, SESSION_DURATION.first)
        )

        auditService.log(account.id, AccountAuditAction.LOGIN_SUCCESS, "Magic link", ipAddress, userAgent)

        return AuthResult.Success(session, sessionToken)
    }

    override fun loginWithOAuth(provider: OAuthProvider, code: String, userAgent: String?, ipAddress: String?): AuthResult {
        // OAuth implementation would exchange code for tokens and create/link account
        // This is a placeholder for OAuth integration
        return AuthResult.Failure(AuthFailureReason.INVALID_CREDENTIALS)
    }

    // ============ LOGOUT ============

    override fun logout(sessionToken: String): Boolean {
        val session = sessionService.validateToken(sessionToken) ?: return false
        auditService.log(session.accountId, AccountAuditAction.LOGOUT, null, session.ipAddress, session.userAgent)
        return sessionService.revokeSession(session.id)
    }

    override fun logoutAll(accountId: AccountId): Int {
        auditService.log(accountId, AccountAuditAction.SESSION_REVOKED_ALL, null, null, null)
        return sessionService.revokeAllSessions(accountId)
    }

    // ============ PASSWORD ============

    override fun changePassword(accountId: AccountId, currentPassword: String, newPassword: String): Boolean {
        val cryptoSettings = cryptoSettingsRepository.getByAccountId(accountId) ?: return false

        if (!cryptoService.verifyPassword(currentPassword, cryptoSettings.passwordHash)) {
            auditService.log(accountId, AccountAuditAction.PASSWORD_CHANGE_FAILED, "Invalid current password", null, null)
            return false
        }

        val newHash = cryptoService.hashPassword(newPassword, cryptoSettings.passwordProfile)
        val success = cryptoSettingsRepository.updatePasswordHash(accountId, newHash)

        if (success) {
            auditService.log(accountId, AccountAuditAction.PASSWORD_CHANGED, null, null, null)
        }

        return success
    }

    override fun resetPassword(accountId: AccountId, newPassword: String): Boolean {
        val cryptoSettings = cryptoSettingsRepository.getByAccountId(accountId) ?: return false
        val newHash = cryptoService.hashPassword(newPassword, cryptoSettings.passwordProfile)
        val success = cryptoSettingsRepository.updatePasswordHash(accountId, newHash)

        if (success) {
            auditService.log(accountId, AccountAuditAction.PASSWORD_RESET, null, null, null)
            sessionService.revokeAllSessions(accountId) // Force re-login
        }

        return success
    }

    override fun requestPasswordReset(email: String): Boolean {
        val account = accountService.getAccountByEmail(email) ?: return true // Don't leak email existence

        val token = cryptoService.generateToken(32)
        magicLinkRepository.create(
            accountId = account.id,
            token = token,
            purpose = "password_reset",
            expiresAt = Instant.now().plus(1, ChronoUnit.HOURS)
        )

        // Send password reset email
        sendResetEmail(email, token)
        auditService.log(account.id, AccountAuditAction.PASSWORD_RESET_REQUESTED, null, null, null)

        return true
    }

    // ============ MAGIC LINK ============

    override fun sendMagicLink(email: String): Boolean {
        val account = accountService.getAccountByEmail(email) ?: return true // Don't leak email existence

        val token = cryptoService.generateToken(32)
        magicLinkRepository.create(
            accountId = account.id,
            token = token,
            purpose = "login",
            expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES)
        )

        // Send magic link email
        sendMagicLinkEmail(email, token)

        return true
    }

    // ============ TOTP ============

    override fun enableTotp(accountId: AccountId): TotpSetupResult {
        val secret = cryptoService.generateTotpSecret()
        val backupCodes = cryptoService.generateBackupCodes(10)
        val account = accountService.getAccountById(accountId)!!

        // Store pending TOTP (not enabled until verified)
        totpRepository.createPending(accountId, secret, backupCodes)

        // Generate QR code URL (otpauth://)
        val base32Secret = base32Encode(secret)
        val qrCodeUrl = "otpauth://totp/HomeClaim:${account.username}?secret=$base32Secret&issuer=HomeClaim"

        return TotpSetupResult(secret, qrCodeUrl, backupCodes)
    }

    override fun verifyTotpSetup(accountId: AccountId, code: String): Boolean {
        val pending = totpRepository.getPendingTotp(accountId) ?: return false

        if (!cryptoService.verifyTotpCode(pending.secret, code)) {
            return false
        }

        totpRepository.enableTotp(accountId)
        auditService.log(accountId, AccountAuditAction.TOTP_ENABLED, null, null, null)

        return true
    }

    override fun disableTotp(accountId: AccountId, password: String): Boolean {
        val cryptoSettings = cryptoSettingsRepository.getByAccountId(accountId) ?: return false

        if (!cryptoService.verifyPassword(password, cryptoSettings.passwordHash)) {
            return false
        }

        totpRepository.deleteTotp(accountId)
        auditService.log(accountId, AccountAuditAction.TOTP_DISABLED, null, null, null)

        return true
    }

    // ============ WEBAUTHN ============

    override fun beginWebAuthnRegistration(accountId: AccountId): WebAuthnRegistrationChallenge {
        val challenge = cryptoService.generateSalt(32)
        val account = accountService.getAccountById(accountId)!!

        webAuthnRepository.storeChallenge(accountId, challenge)

        return WebAuthnRegistrationChallenge(
            challenge = challenge,
            rpId = "homeclaim.example.com", // TODO: Configure
            rpName = "HomeClaim",
            userId = accountId.value.toString().toByteArray(),
            userName = account.username
        )
    }

    override fun completeWebAuthnRegistration(accountId: AccountId, response: WebAuthnRegistrationResponse): Boolean {
        val storedChallenge = webAuthnRepository.getChallenge(accountId) ?: return false

        try {
            // TODO: Full WebAuthn verification including:
            // - Challenge verification
            // - Type verification (must be "webauthn.create" for registration)
            // - Origin verification
            // - Attestation statement validation (depending on fmt: packed, fido-u2f, none)
            // - Certificate chain verification
            // - Authenticator data parsing
            // - Signature verification with public key

            val credentialId = UUID.randomUUID()
            webAuthnRepository.createCredential(
                accountId = accountId,
                credentialId = credentialId,
                publicKey = response.publicKey,
                signCount = 0
            )

            auditService.log(accountId, AccountAuditAction.WEBAUTHN_ADDED, null, null, null)
            return true
        } catch (e: Exception) {
            println("[WebAuthn] Registration verification failed: ${e.message}")
            return false
        }
    }

    override fun beginWebAuthnLogin(usernameOrEmail: String): WebAuthnLoginChallenge {
        val account = accountService.getAccountByUsername(usernameOrEmail)
            ?: accountService.getAccountByEmail(usernameOrEmail)
            ?: throw IllegalArgumentException("Account not found")

        val challenge = cryptoService.generateSalt(32)
        webAuthnRepository.storeChallenge(account.id, challenge)

        val credentials = webAuthnRepository.listCredentials(account.id)

        return WebAuthnLoginChallenge(
            challenge = challenge,
            rpId = "homeclaim.example.com",
            allowCredentials = credentials.map { it.credentialId.toString().toByteArray() }
        )
    }

    override fun completeWebAuthnLogin(response: WebAuthnLoginResponse, userAgent: String?, ipAddress: String?): AuthResult {
        // TODO: Full WebAuthn verification
        // This is a placeholder
        return AuthResult.Failure(AuthFailureReason.WEBAUTHN_INVALID)
    }

    override fun removeWebAuthnCredential(accountId: AccountId, credentialId: UUID): Boolean {
        val success = webAuthnRepository.deleteCredential(accountId, credentialId)
        if (success) {
            auditService.log(accountId, AccountAuditAction.WEBAUTHN_REMOVED, credentialId.toString(), null, null)
        }
        return success
    }

    override fun listWebAuthnCredentials(accountId: AccountId): List<AccountWebAuthn> {
        return webAuthnRepository.listCredentials(accountId)
    }

    // ============ HELPERS ============

    private fun base32Encode(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val result = StringBuilder()
        var buffer = 0
        var bitsLeft = 0

        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                result.append(alphabet[(buffer shr (bitsLeft - 5)) and 0x1F])
                bitsLeft -= 5
            }
        }

        if (bitsLeft > 0) {
            result.append(alphabet[(buffer shl (5 - bitsLeft)) and 0x1F])
        }

        return result.toString()
    }

    private fun sendResetEmail(email: String, token: String) {
        // TODO: Configure email service (SMTP)
        // Example: emailService.send(
        //     to = email,
        //     subject = "Password Reset Request",
        //     body = "Click here to reset your password: https://homeclaim.local/reset?token=$token"
        // )
        println("[Email] Password reset token for $email: $token")
    }

    private fun sendMagicLinkEmail(email: String, token: String) {
        // TODO: Configure email service (SMTP)
        // Example: emailService.send(
        //     to = email,
        //     subject = "Your HomeClaim Login Link",
        //     body = "Click here to login: https://homeclaim.local/auth/magic?token=$token"
        // )
        println("[Email] Magic link token for $email: $token")
    }
}

// ============ REPOSITORY INTERFACES ============

/**
 * Crypto settings repository interface
 */
interface CryptoSettingsRepository {
    fun getByAccountId(accountId: AccountId): CryptoSettings?
    fun create(accountId: AccountId, passwordHash: String, profile: CryptoProfile): CryptoSettings
    fun updatePasswordHash(accountId: AccountId, passwordHash: String): Boolean
    fun updateProfile(accountId: AccountId, profile: CryptoProfile): Boolean
    fun delete(accountId: AccountId): Boolean
}

/**
 * TOTP repository interface
 */
interface TotpRepository {
    fun getTotpSettings(accountId: AccountId): AccountTotp?
    fun getPendingTotp(accountId: AccountId): AccountTotp?
    fun createPending(accountId: AccountId, secret: ByteArray, backupCodes: List<String>)
    fun enableTotp(accountId: AccountId)
    fun deleteTotp(accountId: AccountId)
    fun useBackupCode(accountId: AccountId, code: String): Boolean
}

/**
 * WebAuthn repository interface
 */
interface WebAuthnRepository {
    fun storeChallenge(accountId: AccountId, challenge: ByteArray)
    fun getChallenge(accountId: AccountId): ByteArray?
    fun createCredential(accountId: AccountId, credentialId: UUID, publicKey: ByteArray, signCount: Int)
    fun getCredential(accountId: AccountId, credentialId: UUID): AccountWebAuthn?
    fun listCredentials(accountId: AccountId): List<AccountWebAuthn>
    fun updateSignCount(accountId: AccountId, credentialId: UUID, signCount: Int)
    fun deleteCredential(accountId: AccountId, credentialId: UUID): Boolean
}

/**
 * Magic link repository interface
 */
interface MagicLinkRepository {
    fun create(accountId: AccountId, token: String, purpose: String, expiresAt: Instant): MagicLink
    fun getByToken(token: String): MagicLink?
    fun markUsed(id: UUID)
    fun cleanupExpired()
}
