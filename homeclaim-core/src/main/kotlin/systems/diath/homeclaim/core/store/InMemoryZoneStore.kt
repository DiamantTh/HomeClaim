package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.Zone
import systems.diath.homeclaim.core.model.ZoneId
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.service.ZoneService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemoryZoneStore : ZoneService {
    private val zones = ConcurrentHashMap<ZoneId, Zone>()
    private val zonesByWorld = ConcurrentHashMap<WorldId, MutableSet<ZoneId>>()

    override fun getZonesAt(world: WorldId, position: Position): List<Zone> {
        return zonesByWorld[world].orEmpty().mapNotNull { zones[it] }.filter { it.appliesTo(position) }
    }

    override fun getZoneById(zoneId: ZoneId): Zone? = zones[zoneId]

    override fun listZones(world: WorldId): List<Zone> = zonesByWorld[world].orEmpty().mapNotNull { zones[it] }

    override fun createZone(zone: Zone): ZoneId {
        zones[zone.id] = zone
        zonesByWorld.computeIfAbsent(zone.world) { mutableSetOf() }.add(zone.id)
        return zone.id
    }

    override fun deleteZone(zoneId: ZoneId) {
        val zone = zones.remove(zoneId) ?: return
        zonesByWorld[zone.world]?.remove(zoneId)
    }
}
