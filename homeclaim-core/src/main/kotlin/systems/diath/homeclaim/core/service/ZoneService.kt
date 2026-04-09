package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.Zone
import systems.diath.homeclaim.core.model.ZoneId
import systems.diath.homeclaim.core.model.WorldId

interface ZoneService {
    fun getZonesAt(world: WorldId, position: Position): List<Zone>
    fun getZoneById(zoneId: ZoneId): Zone?
    fun listZones(world: WorldId): List<Zone>
    fun createZone(zone: Zone): ZoneId
    fun deleteZone(zoneId: ZoneId)
}
