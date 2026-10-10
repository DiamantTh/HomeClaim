package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.Zone
import systems.diath.homeclaim.core.model.ZoneId
import systems.diath.homeclaim.core.service.ZoneService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemoryZoneStore : ZoneService {
    private val zones = ConcurrentHashMap<ZoneId, Zone>()
    private val zonesByArea = ConcurrentHashMap<AreaId, MutableSet<ZoneId>>()

    override fun getZonesAt(areaId: AreaId, position: Position): List<Zone> {
        return zonesByArea[areaId].orEmpty().mapNotNull { zones[it] }.filter { it.appliesTo(position) }
    }

    override fun getZoneById(zoneId: ZoneId): Zone? = zones[zoneId]

    override fun listZones(areaId: AreaId): List<Zone> = zonesByArea[areaId].orEmpty().mapNotNull { zones[it] }

    override fun createZone(zone: Zone): ZoneId {
        zones[zone.id] = zone
        zonesByArea.computeIfAbsent(zone.areaId) { mutableSetOf() }.add(zone.id)
        return zone.id
    }

    override fun deleteZone(zoneId: ZoneId) {
        val zone = zones.remove(zoneId) ?: return
        zonesByArea[zone.areaId]?.remove(zoneId)
    }
}
