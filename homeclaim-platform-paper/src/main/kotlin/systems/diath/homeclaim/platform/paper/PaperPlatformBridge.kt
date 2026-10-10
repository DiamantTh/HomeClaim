package systems.diath.homeclaim.platform.paper

import systems.diath.homeclaim.core.platform.ComponentTriggerHandler
import systems.diath.homeclaim.core.platform.PolicyGuard
import systems.diath.homeclaim.liftlink.LiftLinkPlanner
import org.bukkit.plugin.java.JavaPlugin
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.platform.paper.protection.AutomationProtectionListener
import systems.diath.homeclaim.platform.paper.protection.BlockProtectionListener
import systems.diath.homeclaim.platform.paper.protection.EntityProtectionListener
import systems.diath.homeclaim.platform.paper.protection.ExplosionProtectionListener
import systems.diath.homeclaim.platform.paper.protection.MovementProtectionListener
import systems.diath.homeclaim.platform.paper.protection.PaperProtectionAdapter
import systems.diath.homeclaim.platform.paper.protection.VehicleProtectionListener

class PaperPlatformBridge(
    private val plugin: JavaPlugin,
    private val services: PlatformServices
) {
    private val policyGuard = PolicyGuard(services.policyService, services.regionService, services.worldAreaService)
    private val componentHandler = ComponentTriggerHandler(services.componentService, services.policyService, policyGuard)
    private val liftLinkPlanner = LiftLinkPlanner(services.componentService)

    fun registerListeners() {
        val protectionAdapter = PaperProtectionAdapter(policyGuard, services.auditService)
        listOf(
            BlockProtectionListener(protectionAdapter),
            MovementProtectionListener(protectionAdapter),
            ExplosionProtectionListener(protectionAdapter),
            AutomationProtectionListener(protectionAdapter),
            EntityProtectionListener(protectionAdapter),
            VehicleProtectionListener(protectionAdapter)
        ).forEach { listener -> plugin.server.pluginManager.registerEvents(listener, plugin) }
        plugin.server.pluginManager.registerEvents(
            PaperLiftLinkListener(
                componentHandler,
                services.componentService, 
                services.regionService, 
                liftLinkPlanner,
                services.auditService
            ),
            plugin
        )
    }
}
