package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.Zone
import systems.diath.homeclaim.core.model.ZoneId
import systems.diath.homeclaim.core.model.WorldId

interface ZoneService {
    fun getZonesAt(areaId: AreaId, position: Position): List<Zone>
    fun getZoneById(zoneId: ZoneId): Zone?
    fun listZones(areaId: AreaId): List<Zone>

    @Deprecated("Resolve an Area first, then use the AreaId overload")
    fun getZonesAt(world: WorldId, position: Position): List<Zone> =
        getZonesAt(AreaId.legacyDefault(world), position)

    @Deprecated("Resolve an Area first, then use the AreaId overload")
    fun getZonesAt(worldName: String, position: Position): List<Zone> =
        getZonesAt(AreaId.legacyDefault(WorldId.fromLegacyName(worldName)), position)

    @Deprecated("Resolve an Area first, then use the AreaId overload")
    fun listZones(world: WorldId): List<Zone> = listZones(AreaId.legacyDefault(world))

    @Deprecated("Resolve an Area first, then use the AreaId overload")
    fun listZones(worldName: String): List<Zone> = listZones(AreaId.legacyDefault(WorldId.fromLegacyName(worldName)))
    fun createZone(zone: Zone): ZoneId
    fun deleteZone(zoneId: ZoneId)
}
