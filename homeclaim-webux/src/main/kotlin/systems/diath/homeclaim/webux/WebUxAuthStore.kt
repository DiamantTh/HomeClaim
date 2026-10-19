package systems.diath.homeclaim.webux

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Short-lived in-memory pairing and browser sessions. Restart invalidates every token. */
class WebUxAuthStore(
    private val clock: () -> Instant = Instant::now,
    private val codeTtl: Duration = Duration.ofMinutes(2),
    private val sessionTtl: Duration = Duration.ofHours(12),
    private val random: SecureRandom = SecureRandom()
) {
    data class Identity(val playerId: UUID, val username: String)
    data class Session(val id: UUID, val identity: Identity, val expiresAt: Instant, val csrfToken: String)
    private data class Pairing(val identity: Identity, val expiresAt: Instant)
    private data class StoredSession(val session: Session, val expiresAt: Instant)

    private val pairings = ConcurrentHashMap<String, Pairing>()
    private val sessions = ConcurrentHashMap<String, StoredSession>()
    private val attempts = ConcurrentHashMap<String, java.util.ArrayDeque<Long>>()
    private var lastFailedLoginAuditAt: Instant? = null

    fun issuePairing(playerId: UUID, username: String): String {
        synchronized(pairings) {
            prune()
            require(pairings.size < MAX_ENTRIES) { "Too many pending web login codes" }
            val code = token(24)
            pairings[digest(code)] = Pairing(Identity(playerId, username.take(32)), clock().plus(codeTtl))
            return code
        }
    }

    /** Atomic remove gives one-time behavior even when two exchanges race. */
    fun consumePairing(code: String, remoteHost: String): Pair<Session, String>? {
        if (code.length !in 20..128 || !allowAttempt(remoteHost)) return null
        val pairing = pairings.remove(digest(code)) ?: return null
        val now = clock()
        if (!pairing.expiresAt.isAfter(now)) return null
        prune()
        if (sessions.size >= MAX_ENTRIES) return null
        val rawToken = token(32)
        val session = Session(UUID.randomUUID(), pairing.identity, now.plus(sessionTtl), token(32))
        sessions[digest(rawToken)] = StoredSession(session, session.expiresAt)
        return session to rawToken
    }

    fun resolve(rawToken: String?): Session? {
        if (rawToken.isNullOrBlank() || rawToken.length > 128) return null
        val key = digest(rawToken)
        val stored = sessions[key] ?: return null
        if (!stored.expiresAt.isAfter(clock())) {
            sessions.remove(key, stored)
            return null
        }
        return stored.session
    }

    fun revoke(rawToken: String?) {
        if (!rawToken.isNullOrBlank() && rawToken.length <= 128) sessions.remove(digest(rawToken))
    }

    fun revokeAll() {
        sessions.clear()
        pairings.clear()
    }

    /** Sample failed code exchanges globally so a public login endpoint cannot flood persistent audit. */
    @Synchronized
    fun shouldAuditFailedLogin(): Boolean {
        val now = clock()
        val last = lastFailedLoginAuditAt
        if (last != null && now.isBefore(last.plus(FAILED_LOGIN_AUDIT_INTERVAL))) return false
        lastFailedLoginAuditAt = now
        return true
    }

    private fun allowAttempt(remoteHost: String): Boolean {
        val now = System.currentTimeMillis()
        val key = remoteHost.take(128)
        if (!attempts.containsKey(key) && attempts.size >= MAX_ENTRIES) {
            val cutoff = now - WINDOW_MS
            attempts.entries.removeIf { entry -> synchronized(entry.value) {
                while (entry.value.isNotEmpty() && entry.value.first() < cutoff) entry.value.removeFirst()
                entry.value.isEmpty()
            } }
            if (attempts.size >= MAX_ENTRIES) return false
        }
        val bucket = attempts.computeIfAbsent(key) { java.util.ArrayDeque() }
        synchronized(bucket) {
            while (bucket.isNotEmpty() && bucket.first() < now - WINDOW_MS) bucket.removeFirst()
            if (bucket.size >= MAX_ATTEMPTS) return false
            bucket.addLast(now)
            return true
        }
    }

    private fun prune() {
        val now = clock()
        pairings.entries.removeIf { !it.value.expiresAt.isAfter(now) }
        sessions.entries.removeIf { !it.value.expiresAt.isAfter(now) }
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        attempts.entries.removeIf { entry ->
            synchronized(entry.value) {
                while (entry.value.isNotEmpty() && entry.value.first() < cutoff) entry.value.removeFirst()
                entry.value.isEmpty()
            }
        }
    }

    private fun token(bytes: Int): String = ByteArray(bytes).also(random::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun digest(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    companion object {
        private const val MAX_ENTRIES = 4096
        private const val MAX_ATTEMPTS = 12
        private const val WINDOW_MS = 60_000L
        private val FAILED_LOGIN_AUDIT_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
