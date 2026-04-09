package systems.diath.homeclaim.platform.paper

import systems.diath.homeclaim.core.event.EventDispatcher
import systems.diath.homeclaim.core.service.AuditService
import systems.diath.homeclaim.core.service.ComponentService
import systems.diath.homeclaim.core.service.FlagProfileService
import systems.diath.homeclaim.core.service.PlotMemberService
import systems.diath.homeclaim.core.service.RegionAdminService
import systems.diath.homeclaim.core.service.PolicyService
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.core.service.ZoneService
import systems.diath.homeclaim.core.economy.EconService
import systems.diath.homeclaim.core.config.SensorRegistry
import systems.diath.homeclaim.core.ui.ComponentCreationService
import systems.diath.homeclaim.core.event.LiftEventDispatcher
import javax.sql.DataSource

data class PlatformServices(
    val policyService: PolicyService,
    val regionService: RegionService,
    val plotMemberService: PlotMemberService?,
    val componentService: ComponentService,
    val zoneService: ZoneService,
    val adminService: RegionAdminService? = null,
    val flagProfileService: FlagProfileService? = null,
    val auditService: AuditService? = null,
    val dataSource: DataSource? = null,
    val eventDispatcher: EventDispatcher? = null,
    val econService: EconService? = null,
    val sensorRegistry: SensorRegistry? = null,
    val componentCreationService: ComponentCreationService? = null,
    val liftEventDispatcher: LiftEventDispatcher? = null
)

