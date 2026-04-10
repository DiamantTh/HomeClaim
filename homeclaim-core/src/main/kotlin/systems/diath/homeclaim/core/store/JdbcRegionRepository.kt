package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.model.Bounds
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
import systems.diath.homeclaim.core.service.AuditService
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

class JdbcRegionRepository(
    private val dataSource: DataSource,
    private val eventDispatcher: EventDispatcher? = null,
    private val auditService: AuditService? = null
) : RegionService {
    override fun getRegionAt(world: WorldId, x: Int, y: Int, z: Int): RegionId? {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id FROM regions
                WHERE world = ?
                  AND ? BETWEEN min_x AND max_x
                  AND ? BETWEEN min_y AND max_y
                  AND ? BETWEEN min_z AND max_z
                LIMIT 1
                """.trimIndent()
            ).apply {
                setString(1, world)
                setInt(2, x)
                setInt(3, y)
                setInt(4, z)
            }.executeQuery().use { rs ->
                return if (rs.next()) RegionId(UUID.fromString(rs.getString("id"))) else null
            }
        }
    }
    
    override fun getRegionAt2D(world: WorldId, x: Int, z: Int): RegionId? {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id FROM regions
                WHERE world = ?
                  AND ? BETWEEN min_x AND max_x
                  AND ? BETWEEN min_z AND max_z
                LIMIT 1
                """.trimIndent()
            ).apply {
                setString(1, world)
                setInt(2, x)
                setInt(3, z)
            }.executeQuery().use { rs ->
                return if (rs.next()) RegionId(UUID.fromString(rs.getString("id"))) else null
            }
        }
    }

    override fun listRegionsByOwner(ownerId: PlayerId): List<Region> {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM regions WHERE owner = ?").apply {
                setObject(1, ownerId)
            }.executeQuery().use { rs ->
                val regions = mutableListOf<Region>()
                while (rs.next()) {
                    regions += rs.toRegion(conn)
                }
                return regions
            }
        }
    }

    override fun listAllRegions(): List<Region> {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM regions").use { stmt ->
                stmt.executeQuery().use { rs ->
                    val regions = mutableListOf<Region>()
                    while (rs.next()) {
                        regions += rs.toRegion(conn)
                    }
                    return regions
                }
            }
        }
    }

    override fun getRegionById(regionId: RegionId): Region? {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM regions WHERE id = ?").apply {
                setObject(1, regionId.value)
            }.executeQuery().use { rs ->
                return if (rs.next()) rs.toRegion(conn) else null
            }
        }
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

        eventDispatcher?.dispatch(PostRegionMergeEvent(normalizedIds, initiatorId, mergeId))
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
        
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO regions (id, world, shape, min_x, max_x, min_y, max_y, min_z, max_z, owner, merge_group_id, price, metadata)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).apply {
                    setObject(1, region.id.value)
                    setString(2, region.world)
                    setString(3, region.shape.name)
                    setInt(4, bounds.minX)
                    setInt(5, bounds.maxX)
                    setInt(6, bounds.minY)
                    setInt(7, bounds.maxY)
                    setInt(8, bounds.minZ)
                    setInt(9, bounds.maxZ)
                    setObject(10, region.owner)
                    setObject(11, region.mergeGroupId?.value)
                    setDouble(12, region.price)
                    setString(13, Serialization.toJson(region.metadata))
                }.executeUpdate()

                replaceRoles(conn, region.id, region.roles)
                replaceFlags(conn, region.id, region.flags)
                replaceLimits(conn, region.id, region.limits)
                conn.commit()
                
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
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = true
            }
        }
        
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
        
        // Audit log
        region?.let {
            auditService?.append(
                AuditEntry(
                    actorId = null,  // Actor unknown in this context
                    targetId = regionId.value,
                    category = "REGION",
                    action = "DELETED",
                    payload = mapOf(
                        "world" to it.world,
                        "owner" to it.owner.toString()
                    )
                )
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
                    UPDATE regions SET world = ?, shape = ?, min_x = ?, max_x = ?, min_y = ?, max_y = ?, min_z = ?, max_z = ?, owner = ?, merge_group_id = ?, price = ?, metadata = ?
                    WHERE id = ?
                    """.trimIndent()
                ).apply {
                    setString(1, region.world)
                    setString(2, region.shape.name)
                    setInt(3, region.bounds.minX)
                    setInt(4, region.bounds.maxX)
                    setInt(5, region.bounds.minY)
                    setInt(6, region.bounds.maxY)
                    setInt(7, region.bounds.minZ)
                    setInt(8, region.bounds.maxZ)
                    setObject(9, region.owner)
                    setObject(10, region.mergeGroupId?.value)
                    setDouble(11, region.price)
                    setString(12, Serialization.toJson(region.metadata))
                    setObject(13, region.id.value)
                }.executeUpdate()

                replaceRoles(conn, region.id, region.roles)
                replaceFlags(conn, region.id, region.flags)
                replaceLimits(conn, region.id, region.limits)
                conn.commit()
                
                // Audit log
                auditService?.append(
                    AuditEntry(
                        actorId = region.owner,
                        targetId = region.id.value,
                        category = "REGION",
                        action = "UPDATED",
                        payload = mapOf(
                            "world" to region.world,
                            "owner" to region.owner.toString()
                        )
                    )
                )
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = true
            }
        }
        
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
    
    override fun claimRegion(regionId: RegionId, claimerId: PlayerId, cost: Double, econService: EconService): Boolean {
        val region = getRegionById(regionId) ?: return false
        
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
}
