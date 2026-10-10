package systems.diath.homeclaim.platform.paper

import io.papermc.paper.plugin.bootstrap.BootstrapContext
import io.papermc.paper.plugin.bootstrap.PluginBootstrap
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import systems.diath.homeclaim.platform.paper.command.PaperCommandTrees

/** Registers reload-aware Brigadier command trees before the runtime services are created. */
class HomeClaimPaperPluginBootstrap : PluginBootstrap {
    override fun bootstrap(context: BootstrapContext) {
        context.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val route: (CommandSender, String, List<String>) -> Int = { sender, command, args ->
                val plugin = Bukkit.getPluginManager().getPlugin("HomeClaim") as? HomeClaimPaperPlugin
                if (plugin == null || !plugin.isEnabled) {
                    sender.sendMessage("HomeClaim is not ready yet.")
                    0
                } else {
                    plugin.dispatchPaperCommand(command, sender, args)
                }
            }
            val registrar = event.registrar()
            registrar.register(PaperCommandTrees.homeClaim(route), "HomeClaim administration", listOf("hc"))
            registrar.register(PaperCommandTrees.plot(route), "Manage plots", listOf("p"))
            registrar.register(PaperCommandTrees.zone(route), "Inspect zones", listOf("z"))
        }
    }
}
