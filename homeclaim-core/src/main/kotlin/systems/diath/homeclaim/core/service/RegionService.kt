package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.event.EventDispatcher
import systems.diath.homeclaim.core.event.EventResult
import systems.diath.homeclaim.core.event.PostRegionBuyEvent
import systems.diath.homeclaim.core.event.PostRegionClaimEvent
import systems.diath.homeclaim.core.event.RegionBuyEvent
import systems.diath.homeclaim.core.event.RegionClaimEvent
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.MergeGroupId
import systems.diath.homeclaim.core.economy.EconService
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.WorldId

interface RegionService {
    fun getRegionAt(world: WorldId, x: Int, y: Int, z: Int): RegionId?
    
    /**
     * 2D plot lookup (ignores Y axis).
     * Use this for plot commands where height shouldn't matter (claim, info, etc.)
     */
    fun getRegionAt2D(world: WorldId, x: Int, z: Int): RegionId?
    
    fun listRegionsByOwner(ownerId: PlayerId): List<Region>
    fun listAllRegions(): List<Region>
    fun getRegionById(regionId: RegionId): Region?
    fun mergeRegions(regionIds: Collection<RegionId>): MergeGroupId
    fun createRegion(region: Region, bounds: Bounds = region.bounds): RegionId
    fun updateRegion(region: Region)
    fun deleteRegion(regionId: RegionId)
    
    /**
     * Buy a region (transfer ownership + charge buyer, pay seller)
     * Dispatches RegionBuyEvent (pre) and PostRegionBuyEvent (post)
     */
    fun buyRegion(regionId: RegionId, buyerId: PlayerId, econService: EconService): Boolean
    
    /**
     * Claim an unclaimed region (set as owner, charge claim cost)
     * Dispatches RegionClaimEvent (pre) and PostRegionClaimEvent (post)
     */
    fun claimRegion(regionId: RegionId, claimerId: PlayerId, cost: Double = 0.0, econService: EconService): Boolean
    
    /**
     * Get EventDispatcher for economy/buy-sell events
     */
    fun getEventDispatcher(): EventDispatcher?
}
