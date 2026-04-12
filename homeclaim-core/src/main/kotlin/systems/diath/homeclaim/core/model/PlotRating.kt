package systems.diath.homeclaim.core.model

import java.time.Instant
import java.util.UUID

/**
 * Plot rating/vote from a player.
 * Ratings are 1-5 stars. One rating per player per plot.
 */
data class PlotRating(
    val id: UUID = UUID.randomUUID(),
    val regionId: RegionId,
    val raterId: PlayerId,
    val rating: Int, // 1-5
    val comment: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {
    init {
        require(rating in 1..5) { "Rating must be between 1 and 5" }
    }
}

/**
 * Aggregated rating statistics for a plot.
 */
data class PlotRatingStats(
    val regionId: RegionId,
    val totalRatings: Int = 0,
    val averageRating: Double = 0.0,
    val ratingDistribution: Map<Int, Int> = mapOf() // 1->count, 2->count, etc
) {
    /**
     * Format as star display: "★★★★☆ (4.2/5, 42 votes)"
     */
    fun toStarDisplay(): String {
        if (totalRatings == 0) return "No ratings yet"
        val filledStars = "★".repeat(averageRating.toInt())
        val emptyStars = "☆".repeat(5 - averageRating.toInt())
        return "$filledStars$emptyStars (%.1f/5, %d votes)".format(averageRating, totalRatings)
    }
}
