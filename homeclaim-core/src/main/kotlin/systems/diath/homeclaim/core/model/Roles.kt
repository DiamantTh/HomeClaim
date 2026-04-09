package systems.diath.homeclaim.core.model

enum class RegionRole {
    OWNER,
    TRUSTED,
    MEMBER,
    VISITOR,
    BANNED
}

data class RegionRoles(
    val trusted: Set<PlayerId> = emptySet(),
    val members: Set<PlayerId> = emptySet(),
    val banned: Set<PlayerId> = emptySet()
) {
    fun resolve(playerId: PlayerId, ownerId: PlayerId): RegionRole {
        return when {
            playerId == ownerId -> RegionRole.OWNER
            banned.contains(playerId) -> RegionRole.BANNED
            trusted.contains(playerId) -> RegionRole.TRUSTED
            members.contains(playerId) -> RegionRole.MEMBER
            else -> RegionRole.VISITOR
        }
    }
}
