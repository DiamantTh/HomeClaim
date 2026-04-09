package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.*
import java.time.Instant

/**
 * Plot service for managing plot metadata, warps, and social features
 */
interface PlotService {
    // CRUD
    fun getPlotById(plotId: PlotId): Plot?
    fun getPlotByRegion(regionId: RegionId): Plot?
    fun getPlotByAlias(alias: String): Plot?
    fun getPlotByOwnerUsername(username: String): Plot?
    fun listPlotsByOwner(ownerId: PlayerId): List<Plot>
    fun listFeaturedPlots(): List<Plot>
    fun listPublicPlots(limit: Int = 50, offset: Int = 0): List<Plot>
    fun listAllPlots(limit: Int = 1000, offset: Int = 0): List<Plot>
    fun searchPlots(query: String, limit: Int = 20): List<Plot>
    
    // Create/Update
    fun createPlot(regionId: RegionId, owner: PlayerId): Plot
    fun updatePlot(plotId: PlotId, update: PlotUpdate): Plot?
    fun deletePlot(plotId: PlotId): Boolean
    
    // Warp
    fun setWarp(plotId: PlotId, x: Double, y: Double, z: Double, yaw: Float, pitch: Float): Boolean
    fun clearWarp(plotId: PlotId): Boolean
    
    // Alias (requires permission)
    fun setAlias(plotId: PlotId, alias: String): Boolean
    fun clearAlias(plotId: PlotId): Boolean
    fun isAliasAvailable(alias: String): Boolean
    
    // Stats update (called by other services)
    fun incrementLikes(plotId: PlotId)
    fun decrementLikes(plotId: PlotId)
    fun incrementVisits(plotId: PlotId)
    fun incrementBookmarks(plotId: PlotId)
    fun decrementBookmarks(plotId: PlotId)
    
    // Stats aggregation (scheduled job)
    fun recalculateStats(plotId: PlotId)
    fun recalculateAllStats()
}

/**
 * Update DTO for plots
 */
data class PlotUpdate(
    val title: String? = null,
    val description: String? = null,
    val internalDescription: String? = null,
    val notes: String? = null,
    val category: String? = null,
    val tags: List<String>? = null,
    val visibility: PlotVisibility? = null,
    val statsVisibility: StatsVisibility? = null,
    val statsWhitelist: List<PlayerId>? = null,
    val broadcastingEnabled: Boolean? = null,
    val featured: Boolean? = null
)

/**
 * Plot member service
 */
interface PlotMemberService {
    // Query
    fun getMember(plotId: PlotId, playerId: PlayerId): PlotMember?
    fun listMembers(plotId: PlotId): List<PlotMember>
    fun listMembersByRole(plotId: PlotId, role: PlotMemberRole): List<PlotMember>
    fun hasAccess(plotId: PlotId, playerId: PlayerId, ownerOnline: Boolean): Boolean
    fun isDenied(plotId: PlotId, playerId: PlayerId): Boolean
    
    // Trust (permanent access)
    fun trustPlayer(plotId: PlotId, playerId: PlayerId, addedBy: PlayerId): Boolean
    fun untrustPlayer(plotId: PlotId, playerId: PlayerId): Boolean
    
    // Member (owner-online access)
    fun addMember(plotId: PlotId, playerId: PlayerId, addedBy: PlayerId): Boolean
    fun removeMember(plotId: PlotId, playerId: PlayerId): Boolean
    
    // Deny
    fun denyPlayer(plotId: PlotId, playerId: PlayerId, addedBy: PlayerId): Boolean
    fun undenyPlayer(plotId: PlotId, playerId: PlayerId): Boolean
    fun denyByPattern(plotId: PlotId, pattern: String, addedBy: PlayerId): Int // Wildcard support
    
    // Kick (temporary)
    fun kickPlayer(plotId: PlotId, playerId: PlayerId, kickedBy: PlayerId, reason: String?, durationMinutes: Int = 30): Boolean
    fun isKicked(plotId: PlotId, playerId: PlayerId): PlotKick?
    fun unkickPlayer(plotId: PlotId, playerId: PlayerId): Boolean
    fun cleanupExpiredKicks()
}

/**
 * Plot social service (likes, bookmarks, visits)
 */
interface PlotSocialService {
    // Likes
    fun like(plotId: PlotId, playerId: PlayerId): Boolean
    fun unlike(plotId: PlotId, playerId: PlayerId): Boolean
    fun hasLiked(plotId: PlotId, playerId: PlayerId): Boolean
    fun getLikesCount(plotId: PlotId): Int
    fun getLikesCountSince(plotId: PlotId, since: Instant): Int
    
    // Bookmarks
    fun bookmark(plotId: PlotId, playerId: PlayerId, note: String? = null, tags: List<String> = emptyList()): Boolean
    fun unbookmark(plotId: PlotId, playerId: PlayerId): Boolean
    fun hasBookmarked(plotId: PlotId, playerId: PlayerId): Boolean
    fun getBookmark(plotId: PlotId, playerId: PlayerId): PlotBookmark?
    fun listBookmarks(playerId: PlayerId): List<PlotBookmark>
    fun updateBookmark(plotId: PlotId, playerId: PlayerId, note: String?, tags: List<String>): Boolean
    
    // Visits
    fun trackVisit(plotId: PlotId, playerId: PlayerId): Boolean
    fun getVisitsCount(plotId: PlotId): Int
    fun getVisitsCountSince(plotId: PlotId, since: Instant): Int
    fun getLastVisit(plotId: PlotId, playerId: PlayerId): PlotVisit?
    
    // Privacy check
    fun canViewStats(plotId: PlotId, viewerId: PlayerId): Boolean
}

/**
 * Plot notification service (for mods/admins)
 */
interface PlotNotificationService {
    fun createNotification(
        plotId: PlotId,
        changeType: PlotChangeType,
        changedBy: PlayerId,
        oldValue: String? = null,
        newValue: String? = null
    ): PlotNotification
    
    fun listUnacknowledged(limit: Int = 50): List<PlotNotification>
    fun listByPlot(plotId: PlotId): List<PlotNotification>
    fun acknowledge(notificationId: java.util.UUID, acknowledgedBy: PlayerId): Boolean
    fun acknowledgeAll(acknowledgedBy: PlayerId): Int
    fun isBroadcastingEnabled(plotId: PlotId): Boolean
}

/**
 * Plot screenshot service
 */
interface PlotScreenshotService {
    fun addScreenshot(plotId: PlotId, url: String, isPrimary: Boolean = false): PlotScreenshot
    fun removeScreenshot(screenshotId: java.util.UUID): Boolean
    fun setPrimary(screenshotId: java.util.UUID): Boolean
    fun listScreenshots(plotId: PlotId): List<PlotScreenshot>
    fun reorderScreenshots(plotId: PlotId, orderedIds: List<java.util.UUID>): Boolean
}
