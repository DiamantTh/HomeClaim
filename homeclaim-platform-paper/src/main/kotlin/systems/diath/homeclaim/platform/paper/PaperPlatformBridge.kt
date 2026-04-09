package systems.diath.homeclaim.platform.paper

import systems.diath.homeclaim.core.platform.ComponentTriggerHandler
import systems.diath.homeclaim.core.platform.PolicyGuard
import systems.diath.homeclaim.liftlink.LiftLinkPlanner
import org.bukkit.plugin.java.JavaPlugin
import systems.diath.homeclaim.core.service.RegionService

class PaperPlatformBridge(
    private val plugin: JavaPlugin,
    private val services: PlatformServices
) {
    private val policyGuard = PolicyGuard(services.policyService, services.regionService)
    private val componentHandler = ComponentTriggerHandler(services.componentService, services.policyService)
    private val liftLinkPlanner = LiftLinkPlanner(services.componentService)

    fun registerListeners() {
        val policyListener = PaperPolicyListener(policyGuard, componentHandler, services.auditService)
        plugin.server.pluginManager.registerEvents(policyListener, plugin)
        plugin.server.pluginManager.registerEvents(
            PaperLiftLinkListener(
                policyGuard, 
                services.componentService, 
                services.regionService, 
                liftLinkPlanner,
                services.auditService
            ),
            plugin
        )
    }
}
