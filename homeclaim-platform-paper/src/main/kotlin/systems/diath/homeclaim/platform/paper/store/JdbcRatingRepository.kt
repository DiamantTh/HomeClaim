package systems.diath.homeclaim.platform.paper.store

import systems.diath.homeclaim.core.model.PlotRating
import systems.diath.homeclaim.core.model.PlotRatingStats
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.store.RatingRepository
import javax.sql.DataSource
import java.time.Instant
import java.util.UUID

class JdbcRatingRepository(private val dataSource: DataSource) : RatingRepository {
    
    override fun saveRating(rating: PlotRating): PlotRating {
        dataSource.connection.use { conn ->
            // Check if rating exists
            val existing = getRating(rating.regionId, rating.raterId)
            
            val now = Instant.now()
            val id = existing?.id ?: rating.id
            
            conn.prepareStatement(
                if (existing == null) {
                    """
                    INSERT INTO plot_ratings (id, region_id, rater_id, rating, comment, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                } else {
                    """
                    UPDATE plot_ratings SET rating = ?, comment = ?, updated_at = ?
                    WHERE id = ?
                    """.trimIndent()
                }
            ).apply {
                if (existing == null) {
                    setObject(1, id)
                    setObject(2, rating.regionId.value)
                    setObject(3, rating.raterId)
                    setInt(4, rating.rating)
                    setString(5, rating.comment)
                    setObject(6, now)
                    setObject(7, now)
                } else {
                    setInt(1, rating.rating)
                    setString(2, rating.comment)
                    setObject(3, now)
                    setObject(4, id)
                }
            }.executeUpdate()
            
            return rating.copy(id = id, updatedAt = now)
        }
    }
    
    override fun getRating(regionId: RegionId, raterId: UUID): PlotRating? {
        dataSource.connection.use { conn ->
            return conn.prepareStatement(
                "SELECT id, region_id, rater_id, rating, comment, created_at, updated_at FROM plot_ratings WHERE region_id = ? AND rater_id = ?"
            ).apply {
                setObject(1, regionId.value)
                setObject(2, raterId)
            }.executeQuery().use { rs ->
                if (rs.next()) rs.toPlotRating() else null
            }
        }
    }
    
    override fun getPlotRatings(regionId: RegionId): List<PlotRating> {
        dataSource.connection.use { conn ->
            return conn.prepareStatement(
                "SELECT id, region_id, rater_id, rating, comment, created_at, updated_at FROM plot_ratings WHERE region_id = ? ORDER BY created_at DESC"
            ).apply {
                setObject(1, regionId.value)
            }.executeQuery().use { rs ->
                val ratings = mutableListOf<PlotRating>()
                while (rs.next()) {
                    ratings.add(rs.toPlotRating())
                }
                ratings
            }
        }
    }
    
    override fun getStats(regionId: RegionId): PlotRatingStats {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) as cnt, AVG(rating) as avg, rating FROM plot_ratings WHERE region_id = ? GROUP BY rating"
            ).apply {
                setObject(1, regionId.value)
            }.executeQuery().use { rs ->
                val distribution = mutableMapOf<Int, Int>()
                var totalCount = 0
                var sumRating = 0.0
                
                while (rs.next()) {
                    val rating = rs.getInt("rating")
                    val count = rs.getInt("cnt")
                    distribution[rating] = count
                    totalCount += count
                    sumRating += rating * count
                }
                
                val avgRating = if (totalCount > 0) sumRating / totalCount else 0.0
                
                return PlotRatingStats(
                    regionId = regionId,
                    totalRatings = totalCount,
                    averageRating = avgRating,
                    ratingDistribution = distribution
                )
            }
        }
    }
    
    override fun deleteRating(id: UUID): Boolean {
        dataSource.connection.use { conn ->
            return conn.prepareStatement("DELETE FROM plot_ratings WHERE id = ?").apply {
                setObject(1, id)
            }.executeUpdate() > 0
        }
    }
    
    private fun java.sql.ResultSet.toPlotRating(): PlotRating {
        return PlotRating(
            id = getObject("id") as UUID,
            regionId = RegionId(getObject("region_id") as UUID),
            raterId = getObject("rater_id") as UUID,
            rating = getInt("rating"),
            comment = getString("comment"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant()
        )
    }
}
