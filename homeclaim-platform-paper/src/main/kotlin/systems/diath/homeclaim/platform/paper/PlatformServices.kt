package systems.diath.homeclaim.platform.paper

import systems.diath.homeclaim.core.event.EventDispatcher
import systems.diath.homeclaim.core.service.AuditService
import systems.diath.homeclaim.core.service.AuditReader
import systems.diath.homeclaim.core.service.ComponentService
import systems.diath.homeclaim.core.service.ComponentOperationService
import systems.diath.homeclaim.core.service.EntryDenyService
import systems.diath.homeclaim.core.service.EntryDenyOperationService
import systems.diath.homeclaim.core.service.FlagProfileService
import systems.diath.homeclaim.core.service.RegionAdminService
import systems.diath.homeclaim.core.service.RegionHomeService
import systems.diath.homeclaim.core.service.RegionUnclaimService
import systems.diath.homeclaim.core.service.RegionBiomeService
import systems.diath.homeclaim.core.service.PolicyService
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.core.service.RoleService
import systems.diath.homeclaim.core.service.ZoneService
import systems.diath.homeclaim.core.service.WorldAreaService
import systems.diath.homeclaim.core.economy.EconService
import systems.diath.homeclaim.core.config.SensorRegistry
import systems.diath.homeclaim.core.event.LiftEventDispatcher
import javax.sql.DataSource

data class PlatformServices(
    val policyService: PolicyService,
    val regionService: RegionService,
    val componentService: ComponentService,
    val zoneService: ZoneService,
    val entryDenyService: EntryDenyService? = null,
    val adminService: RegionAdminService? = null,
    val flagProfileService: FlagProfileService? = null,
    val auditService: AuditService? = null,
    val dataSource: DataSource? = null,
    val eventDispatcher: EventDispatcher? = null,
    val econService: EconService? = null,
    val sensorRegistry: SensorRegistry? = null,
    val liftEventDispatcher: LiftEventDispatcher? = null,
    val worldAreaService: WorldAreaService? = null,
    val roleService: RoleService? = null,
    val auditReader: AuditReader? = null,
    val componentOperationService: ComponentOperationService? = null,
    val entryDenyOperationService: EntryDenyOperationService? = null,
    val regionHomeService: RegionHomeService? = null,
    val regionUnclaimService: RegionUnclaimService? = null,
    val regionBiomeService: RegionBiomeService? = null
)
