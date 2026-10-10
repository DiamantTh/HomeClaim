package systems.diath.homeclaim.core.model

enum class RegionRole {
    OWNER,
    TRUSTED,
    MEMBER,
    VISITOR,
    BANNED;

    fun allowsInheritedAction(): Boolean = when (this) {
        OWNER, TRUSTED, MEMBER -> true
        VISITOR, BANNED -> false
    }
}

fun PlotMemberRole.toRegionRole(): RegionRole = when (this) {
    PlotMemberRole.OWNER -> RegionRole.OWNER
    PlotMemberRole.TRUSTED -> RegionRole.TRUSTED
    PlotMemberRole.MEMBER -> RegionRole.MEMBER
    PlotMemberRole.GUEST -> RegionRole.VISITOR
    PlotMemberRole.DENIED -> RegionRole.BANNED
}

data class RegionRoles(
    val trusted: Set<PlayerId> = emptySet(),
    val members: Set<PlayerId> = emptySet(),
    val banned: Set<PlayerId> = emptySet()
) {
    fun resolve(playerId: PlayerId, ownerId: PlayerId): RegionRole {
        return when {
            // Deny is an explicit hard restriction and must win over ownership or trust.
            banned.contains(playerId) -> RegionRole.BANNED
            playerId == ownerId -> RegionRole.OWNER
            trusted.contains(playerId) -> RegionRole.TRUSTED
            members.contains(playerId) -> RegionRole.MEMBER
            else -> RegionRole.VISITOR
        }
    }
}
