package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.MergeGroupId
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.core.event.EventDispatcher
import systems.diath.homeclaim.core.event.RegionBuyEvent
import systems.diath.homeclaim.core.event.PostRegionBuyEvent
import systems.diath.homeclaim.core.event.RegionClaimEvent
import systems.diath.homeclaim.core.event.PostRegionClaimEvent
import systems.diath.homeclaim.core.event.RegionCreateEvent
import systems.diath.homeclaim.core.event.PostRegionCreateEvent
import systems.diath.homeclaim.core.event.RegionUpdateEvent
import systems.diath.homeclaim.core.event.PostRegionUpdateEvent
import systems.diath.homeclaim.core.event.RegionDeleteEvent
import systems.diath.homeclaim.core.event.PostRegionDeleteEvent
import systems.diath.homeclaim.core.event.EventResult
import systems.diath.homeclaim.core.economy.EconService
import systems.diath.homeclaim.core.service.AuditEntry
import systems.diath.homeclaim.core.service.AuditService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemoryRegionStore(
    private val eventDispatcher: EventDispatcher? = null,
    private val auditService: AuditService? = null
) : RegionService {
    private val regions = ConcurrentHashMap<RegionId, Region>()
    private val regionsByOwner = ConcurrentHashMap<PlayerId, MutableSet<RegionId>>()
    private val chunkIndex = ConcurrentHashMap<WorldId, ConcurrentHashMap<String, MutableSet<RegionId>>>()

    override fun getRegionAt(world: WorldId, x: Int, y: Int, z: Int): RegionId? {
        val chunkX = x shr 4
        val chunkZ = z shr 4
        val key = "$chunkX,$chunkZ"
        val ids = chunkIndex[world]?.get(key).orEmpty()
        return ids.firstOrNull { id ->
            regions[id]?.bounds?.contains(x, y, z) ?: false
        }
    }
    
    override fun getRegionAt2D(world: WorldId, x: Int, z: Int): RegionId? {
        val chunkX = x shr 4
        val chunkZ = z shr 4
        val key = "$chunkX,$chunkZ"
        val ids = chunkIndex[world]?.get(key).orEmpty()
        return ids.firstOrNull { id ->
            regions[id]?.bounds?.contains2D(x, z) ?: false
        }
    }

    override fun listRegionsByOwner(ownerId: PlayerId): List<Region> {
        return regionsByOwner[ownerId].orEmpty().mapNotNull { regions[it] }
    }

    override fun listAllRegions(): List<Region> {
        return regions.values.toList()
    }

    override fun getRegionById(regionId: RegionId): Region? = regions[regionId]

    override fun mergeRegions(regionIds: Collection<RegionId>): MergeGroupId {
        val mergeId = MergeGroupId(UUID.randomUUID())
        regionIds.forEach { id ->
            regions[id]?.let { regions[id] = it.copy(mergeGroupId = mergeId) }
        }
        return mergeId
    }

    override fun createRegion(region: Region, bounds: Bounds): RegionId {
        // Dispatch pre-event
        val createEvent = RegionCreateEvent(
            regionId = region.id,
            initiatorId = region.owner,
            world = region.world,
            bounds = bounds
        )
        eventDispatcher?.dispatch(createEvent)
        
        if (createEvent.cancelled) {
            throw IllegalStateException("Region creation cancelled by event listener")
        }
        
        regions[region.id] = region
        regionsByOwner.computeIfAbsent(region.owner) { mutableSetOf() }.add(region.id)
        indexRegion(region)
        
        // Audit log
        auditService?.append(
            AuditEntry(
                actorId = region.owner,
                targetId = region.id.value,
                category = "REGION",
                action = "CREATED",
                payload = mapOf(
                    "world" to region.world,
                    "bounds" to "${bounds.minX},${bounds.minY},${bounds.minZ} to ${bounds.maxX},${bounds.maxY},${bounds.maxZ}",
                    "shape" to region.shape.name
                )
            )
        )
        
        // Dispatch post-event
        eventDispatcher?.dispatch(
            PostRegionCreateEvent(
                regionId = region.id,
                initiatorId = region.owner,
                world = region.world
            )
        )
        
        return region.id
    }

    override fun updateRegion(region: Region) {
        val existing = regions[region.id] ?: return
        
        // Dispatch pre-event
        val changes = mutableMapOf<String, Any>()
        if (existing.owner != region.owner) changes["owner"] = "${existing.owner} -> ${region.owner}"
        if (existing.price != region.price) changes["price"] = region.price
        
        val updateEvent = RegionUpdateEvent(
            regionId = region.id,
            initiatorId = region.owner,
            changes = changes
        )
        eventDispatcher?.dispatch(updateEvent)
        
        if (updateEvent.cancelled) {
            throw IllegalStateException("Region update cancelled by event listener")
        }
        
        regions[region.id] = region
        if (existing.owner != region.owner) {
            regionsByOwner[existing.owner]?.remove(region.id)
            regionsByOwner.computeIfAbsent(region.owner) { mutableSetOf() }.add(region.id)
        }
        deindexRegion(existing)
        indexRegion(region)
        
        // Audit log
        auditService?.append(
            AuditEntry(
                actorId = region.owner,
                targetId = region.id.value,
                category = "REGION",
                action = "UPDATED",
                payload = mapOf(
                    "world" to region.world,
                    "owner" to region.owner.toString(),
                    "ownerChanged" to (existing.owner != region.owner)
                )
            )
        )
        
        // Dispatch post-event
        eventDispatcher?.dispatch(
            PostRegionUpdateEvent(
                regionId = region.id,
                initiatorId = region.owner,
                changes = changes
            )
        )
    }

    override fun deleteRegion(regionId: RegionId) {
        val region = regions[regionId] ?: return
        
        // Dispatch pre-event
        val deleteEvent = RegionDeleteEvent(
            regionId = regionId,
            initiatorId = region.owner,
            world = region.world
        )
        eventDispatcher?.dispatch(deleteEvent)
        
        if (deleteEvent.cancelled) {
            throw IllegalStateException("Region deletion cancelled by event listener")
        }
        
        regions.remove(regionId)
        regionsByOwner[region.owner]?.remove(regionId)
        deindexRegion(region)
        
        // Audit log
        auditService?.append(
            AuditEntry(
                actorId = null,  // Actor unknown in this context
                targetId = regionId.value,
                category = "REGION",
                action = "DELETED",
                payload = mapOf(
                    "world" to region.world,
                    "owner" to region.owner.toString()
                )
            )
        )
        
        // Dispatch post-event
        eventDispatcher?.dispatch(
            PostRegionDeleteEvent(
                regionId = regionId,
                initiatorId = region.owner,
                world = region.world
            )
        )
    }

    private fun indexRegion(region: Region) {
        val minChunkX = region.bounds.minX shr 4
        val maxChunkX = region.bounds.maxX shr 4
        val minChunkZ = region.bounds.minZ shr 4
        val maxChunkZ = region.bounds.maxZ shr 4
        if (!chunkIndex.containsKey(region.world)) {
            chunkIndex[region.world] = ConcurrentHashMap()
        }
        val worldIndex = chunkIndex[region.world]!!
        for (cx in minChunkX..maxChunkX) {
            for (cz in minChunkZ..maxChunkZ) {
                val key = "$cx,$cz"
                if (!worldIndex.containsKey(key)) {
                    worldIndex[key] = ConcurrentHashMap.newKeySet()
                }
                worldIndex[key]!!.add(region.id)
            }
        }
    }

    private fun deindexRegion(region: Region) {
        val minChunkX = region.bounds.minX shr 4
        val maxChunkX = region.bounds.maxX shr 4
        val minChunkZ = region.bounds.minZ shr 4
        val maxChunkZ = region.bounds.maxZ shr 4
        val worldIndex = chunkIndex[region.world] ?: return
        for (cx in minChunkX..maxChunkX) {
            for (cz in minChunkZ..maxChunkZ) {
                val key = "$cx,$cz"
                worldIndex[key]?.remove(region.id)
            }
        }
    }
    
    override fun buyRegion(regionId: RegionId, buyerId: PlayerId, econService: EconService): Boolean {
        val region = getRegionById(regionId) ?: return false
        if (region.price <= 0) return false
        
        val seller = region.owner
        val price = region.price
        
        val buyEvent = RegionBuyEvent(regionId, buyerId, seller, price)
        eventDispatcher?.dispatch(buyEvent)
        
        if (buyEvent.eventResult == EventResult.DENY || buyEvent.cancelled) return false
        
        val finalPrice = if (buyEvent.eventResult == EventResult.FORCE) 0.0 else buyEvent.price
        
        if (finalPrice > 0 && !econService.transfer(buyerId, seller, finalPrice, "Region purchase")) {
            return false
        }
        
        val updated = region.copy(owner = buyerId, price = 0.0)
        updateRegion(updated)
        
        val postEvent = PostRegionBuyEvent(regionId, buyerId, seller, finalPrice)
        eventDispatcher?.dispatch(postEvent)
        
        return true
    }
    
    override fun claimRegion(regionId: RegionId, claimerId: PlayerId, cost: Double, econService: EconService): Boolean {
        val region = getRegionById(regionId) ?: return false
        
        val claimEvent = RegionClaimEvent(regionId, claimerId, cost)
        eventDispatcher?.dispatch(claimEvent)
        
        if (claimEvent.eventResult == EventResult.DENY || claimEvent.cancelled) return false
        
        val finalCost = if (claimEvent.eventResult == EventResult.FORCE) 0.0 else claimEvent.cost
        
        if (finalCost > 0 && !econService.charge(claimerId, finalCost, "Region claim")) {
            return false
        }
        
        val updated = region.copy(owner = claimerId)
        updateRegion(updated)
        
        val postEvent = PostRegionClaimEvent(regionId, claimerId, finalCost)
        eventDispatcher?.dispatch(postEvent)
        
        return true
    }
    
    override fun getEventDispatcher(): EventDispatcher? = eventDispatcher
}
