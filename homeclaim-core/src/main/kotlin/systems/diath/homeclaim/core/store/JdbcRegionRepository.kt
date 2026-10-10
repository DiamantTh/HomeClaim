package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.MergeGroupId
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionShape
import systems.diath.homeclaim.core.model.RegionRoles
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.model.FlagKey
import systems.diath.homeclaim.core.model.FlagValue
import systems.diath.homeclaim.core.model.LimitKey
import systems.diath.homeclaim.core.model.LimitValue
import systems.diath.homeclaim.core.model.PolicyValue
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
import systems.diath.homeclaim.core.event.RegionMergeEvent
import systems.diath.homeclaim.core.event.PostRegionMergeEvent
import systems.diath.homeclaim.core.event.EventResult
import systems.diath.homeclaim.core.economy.EconService
import systems.diath.homeclaim.core.service.AuditEntry
import systems.diath.homeclaim.core.service.AuditEntries
import systems.diath.homeclaim.core.service.AuditService
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

class JdbcRegionRepository(
    private val dataSource: DataSource,
    private val eventDispatcher: EventDispatcher? = null,
    private val auditService: AuditService? = null,
    private val worldAreaRepository: JdbcWorldAreaRepository? = null
) : RegionService {
    /** A startup-loaded spatial snapshot keeps protection lookups off JDBC event hot paths. */
    private val runtimeIndex = AtomicReference(RegionRuntimeIndex.EMPTY)

    init {
        reloadRuntimeIndex()
    }

    override fun getRegionAt(areaId: AreaId, x: Int, y: Int, z: Int): RegionId? {
        return runtimeIndex.get().findAt(areaId, x, y, z, twoDimensional = false)
    }
    
    override fun getRegionAt2D(areaId: AreaId, x: Int, z: Int): RegionId? {
        return runtimeIndex.get().findAt(areaId, x, 0, z, twoDimensional = true)
    }

    override fun listRegionsByOwner(ownerId: PlayerId): List<Region> {
        return runtimeIndex.get().regions.values.filter { it.owner == ownerId }
    }

    override fun listAllRegions(): List<Region> {
        return runtimeIndex.get().regions.values.toList()
    }

    override fun getRegionById(regionId: RegionId): Region? {
        return runtimeIndex.get().regions[regionId]
    }

    override fun mergeRegions(regionIds: Collection<RegionId>): MergeGroupId {
        val normalizedIds = regionIds.toSet()
        val mergeId = MergeGroupId(UUID.randomUUID())
        if (normalizedIds.isEmpty()) return mergeId

        val existingRegions = normalizedIds.mapNotNull { getRegionById(it) }
        val initiatorId = existingRegions.firstOrNull()?.owner ?: UUID(0L, 0L)
        eventDispatcher?.dispatch(RegionMergeEvent(normalizedIds, initiatorId))

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.prepareStatement("UPDATE regions SET merge_group_id = ? WHERE id = ?").use { ps ->
                for (id in normalizedIds) {
                    ps.setObject(1, mergeId.value)
                    ps.setObject(2, id.value)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.commit()
        }

        replaceCachedRegions { regions ->
            regions.mapValues { (id, region) ->
                if (id in normalizedIds) region.copy(mergeGroupId = mergeId) else region
            }
        }

        eventDispatcher?.dispatch(PostRegionMergeEvent(normalizedIds, initiatorId, mergeId))
        return mergeId
    }

    override fun createRegion(region: Region, bounds: Bounds): RegionId {
        if (regionAreaIsLegacyDefault(region)) worldAreaRepository?.ensureLegacyArea(region.world)
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
        
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO regions (id, world, area_id, shape, min_x, max_x, min_y, max_y, min_z, max_z, owner, merge_group_id, price, metadata)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).apply {
                    setObject(1, region.id.value)
                    setString(2, region.world)
                    setString(3, region.areaId.value.toString())
                    setString(4, region.shape.name)
                    setInt(5, bounds.minX)
                    setInt(6, bounds.maxX)
                    setInt(7, bounds.minY)
                    setInt(8, bounds.maxY)
                    setInt(9, bounds.minZ)
                    setInt(10, bounds.maxZ)
                    setObject(11, region.owner)
                    setObject(12, region.mergeGroupId?.value)
                    setDouble(13, region.price)
                    setString(14, Serialization.toJson(region.metadata))
                }.executeUpdate()

                replaceRoles(conn, region.id, region.roles)
                replaceFlags(conn, region.id, region.flags)
                replaceLimits(conn, region.id, region.limits)
                conn.commit()
                
                // Audit log
                auditService?.append(
                    AuditEntries.regionCreated(
                        actorId = region.owner,
                        targetId = region.id.value,
                        world = region.world,
                        bounds = "${bounds.minX},${bounds.minY},${bounds.minZ} to ${bounds.maxX},${bounds.maxY},${bounds.maxZ}",
                        shape = region.shape.name
                    )
                )
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = true
            }
        }

        replaceCachedRegion(region.copy(bounds = bounds))
        
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

    private fun regionAreaIsLegacyDefault(region: Region): Boolean =
        region.areaId == AreaId.legacyDefault(WorldId.fromLegacyName(region.world))

    override fun deleteRegion(regionId: RegionId) {
        val region = getRegionById(regionId)
        
        // Dispatch pre-event
        if (region != null) {
            val deleteEvent = RegionDeleteEvent(
                regionId = regionId,
                initiatorId = region.owner,
                world = region.world
            )
            eventDispatcher?.dispatch(deleteEvent)
            
            if (deleteEvent.cancelled) {
                throw IllegalStateException("Region deletion cancelled by event listener")
            }
        }
        
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM regions WHERE id = ?").apply {
                setObject(1, regionId.value)
            }.executeUpdate()
        }

        replaceCachedRegions { regions -> regions - regionId }
        
        // Audit log
        region?.let {
            auditService?.append(
                AuditEntries.regionDeleted(null, regionId.value, it.world, it.owner.toString())
            )
            
            // Dispatch post-event
            eventDispatcher?.dispatch(
                PostRegionDeleteEvent(
                    regionId = regionId,
                    initiatorId = it.owner,
                    world = it.world,
                    regionSnapshot = it
                )
            )
        }
    }

    override fun updateRegion(region: Region) {
        val existing = getRegionById(region.id)
        
        // Dispatch pre-event
        val changes = mutableMapOf<String, Any>()
        if (existing != null) {
            if (existing.owner != region.owner) changes["owner"] = "${existing.owner} -> ${region.owner}"
            if (existing.price != region.price) changes["price"] = region.price
            if (existing.mergeGroupId != region.mergeGroupId) {
                changes["mergeGroupId"] = "${existing.mergeGroupId?.value} -> ${region.mergeGroupId?.value}"
            }
        }
        
        val updateEvent = RegionUpdateEvent(
            regionId = region.id,
            initiatorId = region.owner,
            changes = changes
        )
        eventDispatcher?.dispatch(updateEvent)
        
        if (updateEvent.cancelled) {
            throw IllegalStateException("Region update cancelled by event listener")
        }
        
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    UPDATE regions SET world = ?, area_id = ?, shape = ?, min_x = ?, max_x = ?, min_y = ?, max_y = ?, min_z = ?, max_z = ?, owner = ?, merge_group_id = ?, price = ?, metadata = ?
                    WHERE id = ?
                    """.trimIndent()
                ).apply {
                    setString(1, region.world)
                    setString(2, region.areaId.value.toString())
                    setString(3, region.shape.name)
                    setInt(4, region.bounds.minX)
                    setInt(5, region.bounds.maxX)
                    setInt(6, region.bounds.minY)
                    setInt(7, region.bounds.maxY)
                    setInt(8, region.bounds.minZ)
                    setInt(9, region.bounds.maxZ)
                    setObject(10, region.owner)
                    setObject(11, region.mergeGroupId?.value)
                    setDouble(12, region.price)
                    setString(13, Serialization.toJson(region.metadata))
                    setObject(14, region.id.value)
                }.executeUpdate()

                replaceRoles(conn, region.id, region.roles)
                replaceFlags(conn, region.id, region.flags)
                replaceLimits(conn, region.id, region.limits)
                conn.commit()
                
                // Audit log
                auditService?.append(
                    AuditEntries.regionUpdated(
                        actorId = region.owner,
                        targetId = region.id.value,
                        world = region.world,
                        owner = region.owner.toString()
                    )
                )
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = true
            }
        }
        if (existing != null) replaceCachedRegion(region)
        
        // Dispatch post-event
        eventDispatcher?.dispatch(
            PostRegionUpdateEvent(
                regionId = region.id,
                initiatorId = region.owner,
                changes = changes
            )
        )
    }

    private fun ResultSet.toRegion(conn: java.sql.Connection): Region {
        val regionId = RegionId(UUID.fromString(getString("id")))
        return Region(
            id = regionId,
            world = getString("world"),
            areaId = AreaId(UUID.fromString(getString("area_id"))),
            shape = RegionShape.valueOf(getString("shape")),
            bounds = Bounds(
                minX = getInt("min_x"),
                maxX = getInt("max_x"),
                minY = getInt("min_y"),
                maxY = getInt("max_y"),
                minZ = getInt("min_z"),
                maxZ = getInt("max_z")
            ),
            owner = getObject("owner", UUID::class.java),
            roles = conn.fetchRoles(regionId),
            flags = conn.fetchFlags(regionId),
            limits = conn.fetchLimits(regionId),
            mergeGroupId = getObject("merge_group_id", UUID::class.java)?.let { MergeGroupId(it) },
            metadata = Serialization.fromJson(getString("metadata")),
            price = getDouble("price")
        )
    }

    private fun reloadRuntimeIndex() {
        val loaded = dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM regions").use { statement ->
                statement.executeQuery().use { rs ->
                    buildMap {
                        while (rs.next()) {
                            val region = rs.toRegion(conn)
                            put(region.id, region)
                        }
                    }
                }
            }
        }
        runtimeIndex.set(RegionRuntimeIndex.build(loaded))
    }

    private fun replaceCachedRegion(region: Region) = replaceCachedRegions { it + (region.id to region) }

    private fun replaceCachedRegions(transform: (Map<RegionId, Region>) -> Map<RegionId, Region>) {
        runtimeIndex.updateAndGet { current -> RegionRuntimeIndex.build(transform(current.regions)) }
    }

    private class RegionRuntimeIndex private constructor(
        val regions: Map<RegionId, Region>,
        private val byChunk: Map<AreaId, Map<Long, List<RegionId>>>,
        private val wideRegions: Map<AreaId, List<RegionId>>
    ) {
        fun findAt(areaId: AreaId, x: Int, y: Int, z: Int, twoDimensional: Boolean): RegionId? {
            val chunkKey = chunkKey(x shr 4, z shr 4)
            val indexed = byChunk[areaId]?.get(chunkKey).orEmpty()
            val wide = wideRegions[areaId].orEmpty()
            var match: RegionId? = null
            for (id in indexed) {
                val bounds = regions[id]?.bounds ?: continue
                val contains = if (twoDimensional) bounds.contains2D(x, z) else bounds.contains(x, y, z)
                val current = match
                if (contains && (current == null || id.value < current.value)) match = id
            }
            for (id in wide) {
                val bounds = regions[id]?.bounds ?: continue
                val contains = if (twoDimensional) bounds.contains2D(x, z) else bounds.contains(x, y, z)
                val current = match
                if (contains && (current == null || id.value < current.value)) match = id
            }
            return match
        }

        companion object {
            private const val MAX_INDEXED_CHUNKS_PER_REGION = 4096L
            val EMPTY = RegionRuntimeIndex(emptyMap(), emptyMap(), emptyMap())

            fun build(regions: Map<RegionId, Region>): RegionRuntimeIndex {
                val mutableChunks = HashMap<AreaId, HashMap<Long, MutableList<RegionId>>>()
                val mutableWide = HashMap<AreaId, MutableList<RegionId>>()
                for (region in regions.values) {
                    val minChunkX = region.bounds.minX shr 4
                    val maxChunkX = region.bounds.maxX shr 4
                    val minChunkZ = region.bounds.minZ shr 4
                    val maxChunkZ = region.bounds.maxZ shr 4
                    val count = (maxChunkX.toLong() - minChunkX + 1) * (maxChunkZ.toLong() - minChunkZ + 1)
                    if (count > MAX_INDEXED_CHUNKS_PER_REGION) {
                        mutableWide.getOrPut(region.areaId, ::mutableListOf).add(region.id)
                        continue
                    }
                    val chunks = mutableChunks.getOrPut(region.areaId, ::HashMap)
                    for (chunkX in minChunkX..maxChunkX) {
                        for (chunkZ in minChunkZ..maxChunkZ) {
                            chunks.getOrPut(chunkKey(chunkX, chunkZ), ::mutableListOf).add(region.id)
                        }
                    }
                }
                val sortedChunks = mutableChunks.mapValues { (_, chunks) ->
                    chunks.mapValues { (_, ids) -> ids.sortedBy { it.value.toString() } }
                }
                val sortedWide = mutableWide.mapValues { (_, ids) -> ids.sortedBy { it.value.toString() } }
                return RegionRuntimeIndex(regions.toMap(), sortedChunks, sortedWide)
            }

            private fun chunkKey(x: Int, z: Int): Long = (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)
        }
    }

    private fun java.sql.Connection.fetchRoles(regionId: RegionId): RegionRoles {
        prepareStatement("SELECT player_id, role FROM region_roles WHERE region_id = ?").apply {
            setObject(1, regionId.value)
        }.executeQuery().use { rs ->
            val trusted = mutableSetOf<UUID>()
            val members = mutableSetOf<UUID>()
            val banned = mutableSetOf<UUID>()
            while (rs.next()) {
                val player = rs.getObject("player_id", UUID::class.java)
                when (systems.diath.homeclaim.core.model.RegionRole.valueOf(rs.getString("role"))) {
                    systems.diath.homeclaim.core.model.RegionRole.TRUSTED -> trusted += player
                    systems.diath.homeclaim.core.model.RegionRole.MEMBER -> members += player
                    systems.diath.homeclaim.core.model.RegionRole.BANNED -> banned += player
                    else -> {}
                }
            }
            return RegionRoles(trusted = trusted, members = members, banned = banned)
        }
    }

    private fun java.sql.Connection.fetchFlags(regionId: RegionId): Map<FlagKey, FlagValue> {
        prepareStatement("SELECT flag_key, flag_value FROM region_flags WHERE region_id = ?").apply {
            setObject(1, regionId.value)
        }.executeQuery().use { rs ->
            val map = mutableMapOf<FlagKey, FlagValue>()
            while (rs.next()) {
                val key = FlagKey.trusted(rs.getString("flag_key"))
                val value: PolicyValue = Serialization.fromJson(rs.getString("flag_value"))
                map[key] = value
            }
            return map
        }
    }

    private fun java.sql.Connection.fetchLimits(regionId: RegionId): Map<LimitKey, LimitValue> {
        prepareStatement("SELECT limit_key, limit_value FROM region_limits WHERE region_id = ?").apply {
            setObject(1, regionId.value)
        }.executeQuery().use { rs ->
            val map = mutableMapOf<LimitKey, LimitValue>()
            while (rs.next()) {
                val key = LimitKey.trusted(rs.getString("limit_key"))
                val value: PolicyValue = Serialization.fromJson(rs.getString("limit_value"))
                map[key] = value
            }
            return map
        }
    }

    fun replaceRoles(conn: java.sql.Connection, regionId: RegionId, roles: RegionRoles) {
        conn.prepareStatement("DELETE FROM region_roles WHERE region_id = ?").use {
            it.setObject(1, regionId.value)
            it.executeUpdate()
        }
        conn.prepareStatement("INSERT INTO region_roles (region_id, player_id, role) VALUES (?, ?, ?)").use { ps ->
            roles.trusted.forEach {
                ps.setObject(1, regionId.value)
                ps.setObject(2, it)
                ps.setString(3, systems.diath.homeclaim.core.model.RegionRole.TRUSTED.name)
                ps.addBatch()
            }
            roles.members.forEach {
                ps.setObject(1, regionId.value)
                ps.setObject(2, it)
                ps.setString(3, systems.diath.homeclaim.core.model.RegionRole.MEMBER.name)
                ps.addBatch()
            }
            roles.banned.forEach {
                ps.setObject(1, regionId.value)
                ps.setObject(2, it)
                ps.setString(3, systems.diath.homeclaim.core.model.RegionRole.BANNED.name)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    fun replaceFlags(conn: java.sql.Connection, regionId: RegionId, flags: Map<FlagKey, FlagValue>) {
        conn.prepareStatement("DELETE FROM region_flags WHERE region_id = ?").use {
            it.setObject(1, regionId.value)
            it.executeUpdate()
        }
        if (flags.isEmpty()) return
        conn.prepareStatement("INSERT INTO region_flags (region_id, flag_key, flag_value) VALUES (?, ?, ?)").use { ps ->
            flags.forEach { (key, value) ->
                ps.setObject(1, regionId.value)
                ps.setString(2, key.value)
                ps.setString(3, Serialization.toJson(value))
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    fun replaceLimits(conn: java.sql.Connection, regionId: RegionId, limits: Map<LimitKey, LimitValue>) {
        conn.prepareStatement("DELETE FROM region_limits WHERE region_id = ?").use {
            it.setObject(1, regionId.value)
            it.executeUpdate()
        }
        if (limits.isEmpty()) return
        conn.prepareStatement("INSERT INTO region_limits (region_id, limit_key, limit_value) VALUES (?, ?, ?)").use { ps ->
            limits.forEach { (key, value) ->
                ps.setObject(1, regionId.value)
                ps.setString(2, key.value)
                ps.setString(3, Serialization.toJson(value))
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }
    
    override fun buyRegion(regionId: RegionId, buyerId: PlayerId, econService: EconService): Boolean {
        val region = getRegionById(regionId) ?: return false
        if (region.price <= 0) return false // Not for sale
        
        val seller = region.owner
        val price = region.price
        
        // Dispatch pre-event
        val buyEvent = RegionBuyEvent(regionId, buyerId, seller, price)
        eventDispatcher?.dispatch(buyEvent)
        
        // Check if cancelled
        if (buyEvent.eventResult == EventResult.DENY || buyEvent.cancelled) return false
        
        val finalPrice = if (buyEvent.eventResult == EventResult.FORCE) 0.0 else buyEvent.price
        
        // Transfer money
        if (finalPrice > 0 && !econService.transfer(buyerId, seller, finalPrice, "Region purchase")) {
            return false
        }
        
        // Update ownership
        val updated = region.copy(owner = buyerId, price = 0.0) // Remove from sale
        updateRegion(updated)
        
        // Dispatch post-event
        val postEvent = PostRegionBuyEvent(regionId, buyerId, seller, finalPrice)
        eventDispatcher?.dispatch(postEvent)
        
        return true
    }
    
    @Synchronized
    override fun claimRegion(regionId: RegionId, claimerId: PlayerId, cost: Double, econService: EconService): Boolean {
        val region = getRegionById(regionId) ?: return false
        // Claim calls are serialized within this repository instance, and the low-level
        // service refuses to overwrite an already-owned region even if an adapter is stale.
        if (region.owner != UNCLAIMED_OWNER) return false
        
        // Dispatch pre-event
        val claimEvent = RegionClaimEvent(regionId, claimerId, cost)
        eventDispatcher?.dispatch(claimEvent)
        
        // Check if cancelled
        if (claimEvent.eventResult == EventResult.DENY || claimEvent.cancelled) return false
        
        val finalCost = if (claimEvent.eventResult == EventResult.FORCE) 0.0 else claimEvent.cost
        
        // Charge player if cost > 0
        if (finalCost > 0 && !econService.charge(claimerId, finalCost, "Region claim")) {
            return false
        }
        
        // Update ownership
        val updated = region.copy(owner = claimerId)
        updateRegion(updated)
        
        // Dispatch post-event
        val postEvent = PostRegionClaimEvent(regionId, claimerId, finalCost)
        eventDispatcher?.dispatch(postEvent)
        
        return true
    }
    
    override fun getEventDispatcher(): EventDispatcher? = eventDispatcher

    private companion object {
        val UNCLAIMED_OWNER: UUID = UUID(0L, 0L)
    }
}
