package systems.diath.homeclaim.core.model

import java.time.Instant
import java.util.UUID

@JvmInline
value class EntryDenyId(val value: UUID)

enum class EntryDenyTargetType {
    PLAYER,
    EVERYONE,
    NAME_PATTERN
}

enum class EntryDenyStatus {
    ACTIVE,
    REPORTED,
    REVOKED
}

data class EntryDenyRule(
    val id: EntryDenyId,
    val regionId: RegionId,
    val targetType: EntryDenyTargetType,
    val targetValue: String,
    val reason: String,
    val createdBy: PlayerId,
    val createdAt: Instant,
    val expiresAt: Instant? = null,
    val status: EntryDenyStatus = EntryDenyStatus.ACTIVE,
    val reportedBy: PlayerId? = null,
    val reportedAt: Instant? = null,
    val reportReason: String? = null,
    val revokedBy: PlayerId? = null,
    val revokedAt: Instant? = null,
    val revokeReason: String? = null
) {
    fun isEffective(now: Instant = Instant.now()): Boolean {
        return status != EntryDenyStatus.REVOKED && (expiresAt == null || expiresAt.isAfter(now))
    }

    fun matches(playerId: PlayerId, playerName: String? = null, now: Instant = Instant.now()): Boolean {
        if (!isEffective(now)) return false
        return when (targetType) {
            EntryDenyTargetType.EVERYONE -> true
            EntryDenyTargetType.PLAYER -> targetValue.equals(playerId.toString(), ignoreCase = true)
            EntryDenyTargetType.NAME_PATTERN -> playerName?.let { wildcardMatches(targetValue, it) } ?: false
        }
    }

    companion object {
        fun wildcardMatches(pattern: String, value: String): Boolean {
            val regex = buildString {
                append("^")
                for (ch in pattern) {
                    when (ch) {
                        '*' -> append(".*")
                        '?' -> append('.')
                        '.', '\\', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|' -> {
                            append('\\')
                            append(ch)
                        }
                        else -> append(ch)
                    }
                }
                append("$")
            }.toRegex(RegexOption.IGNORE_CASE)
            return regex.matches(value)
        }
    }
}
