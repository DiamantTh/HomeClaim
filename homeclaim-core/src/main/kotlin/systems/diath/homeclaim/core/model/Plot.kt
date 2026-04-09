package systems.diath.homeclaim.core.model

import java.time.Instant
import java.util.UUID

/**
 * Plot-specific identifiers
 */
@JvmInline
value class PlotId(val value: UUID)

@JvmInline
value class AccountId(val value: UUID)

/**
 * Plot metadata and social data
 */
data class Plot(
    val id: PlotId,
    val regionId: RegionId,
    val owner: PlayerId,
    
    // Metadata
    val title: String?,
    val alias: String?,
    val description: String?,
    val internalDescription: String?,
    val notes: String?,
    val category: String?,
    val tags: List<String>,
    val visibility: PlotVisibility,
    
    // Warp
    val warpX: Double?,
    val warpY: Double?,
    val warpZ: Double?,
    val warpYaw: Float?,
    val warpPitch: Float?,
    
    // Stats
    val likesTotal: Int,
    val likes24h: Int,
    val likes7d: Int,
    val likes30d: Int,
    val visitsTotal: Int,
    val visits24h: Int,
    val visits7d: Int,
    val bookmarksTotal: Int,
    
    // Settings
    val statsVisibility: StatsVisibility,
    val statsWhitelist: List<PlayerId>,
    val broadcastingEnabled: Boolean,
    val featured: Boolean,
    
    // Timestamps
    val createdAt: Instant,
    val updatedAt: Instant
)

enum class PlotVisibility {
    PUBLIC,
    UNLISTED,
    PRIVATE
}

data class StatsVisibility(
    val likes: Boolean = true,
    val visits: Boolean = true,
    val bookmarks: Boolean = true,
    val members: Boolean = true
)

/**
 * Plot member with permission mode
 */
data class PlotMember(
    val plotId: PlotId,
    val playerId: PlayerId,
    val role: PlotMemberRole,
    val permissionMode: PermissionMode,
    val addedAt: Instant,
    val addedBy: PlayerId
)

enum class PlotMemberRole {
    OWNER,
    TRUSTED,
    MEMBER,
    GUEST,
    DENIED
}

enum class PermissionMode {
    PERMANENT,      // Always has access
    OWNER_ONLINE    // Only when owner is online
}

/**
 * Temporary kick from plot
 */
data class PlotKick(
    val plotId: PlotId,
    val playerId: PlayerId,
    val kickedBy: PlayerId,
    val reason: String?,
    val expiresAt: Instant,
    val createdAt: Instant
)

/**
 * Plot like
 */
data class PlotLike(
    val plotId: PlotId,
    val playerId: PlayerId,
    val createdAt: Instant
)

/**
 * Plot bookmark with notes
 */
data class PlotBookmark(
    val plotId: PlotId,
    val playerId: PlayerId,
    val note: String?,
    val tags: List<String>,
    val createdAt: Instant
)

/**
 * Plot visit
 */
data class PlotVisit(
    val plotId: PlotId,
    val playerId: PlayerId,
    val visitedAt: Instant
)

/**
 * Plot screenshot
 */
data class PlotScreenshot(
    val id: UUID,
    val plotId: PlotId,
    val url: String,
    val isPrimary: Boolean,
    val sortOrder: Int,
    val uploadedAt: Instant
)

/**
 * Notification for mods/admins about plot changes
 */
data class PlotNotification(
    val id: UUID,
    val plotId: PlotId,
    val changeType: PlotChangeType,
    val changedBy: PlayerId,
    val oldValue: String?,
    val newValue: String?,
    val acknowledgedBy: PlayerId?,
    val acknowledgedAt: Instant?,
    val createdAt: Instant
)

enum class PlotChangeType {
    DESCRIPTION,
    MEMBER_ADD,
    MEMBER_REMOVE,
    DENY,
    KICK,
    SETTINGS,
    WARP,
    ALIAS
}
