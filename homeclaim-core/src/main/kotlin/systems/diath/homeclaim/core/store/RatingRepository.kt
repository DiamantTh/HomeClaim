package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.model.PlotRating
import systems.diath.homeclaim.core.model.PlotRatingStats
import systems.diath.homeclaim.core.model.RegionId
import java.util.UUID

/**
 * Repository for managing plot ratings.
 */
interface RatingRepository {
    /**
     * Save or update a rating.
     */
    fun saveRating(rating: PlotRating): PlotRating
    
    /**
     * Get a player's rating for a plot, if any.
     */
    fun getRating(regionId: RegionId, raterId: UUID): PlotRating?
    
    /**
     * Get all ratings for a plot.
     */
    fun getPlotRatings(regionId: RegionId): List<PlotRating>
    
    /**
     * Get aggregated stats for a plot.
     */
    fun getStats(regionId: RegionId): PlotRatingStats
    
    /**
     * Delete a rating.
     */
    fun deleteRating(id: UUID): Boolean
}
