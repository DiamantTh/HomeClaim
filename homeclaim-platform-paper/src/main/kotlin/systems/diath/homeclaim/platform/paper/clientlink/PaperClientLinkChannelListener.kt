package systems.diath.homeclaim.platform.paper.clientlink

import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener
import org.bukkit.plugin.java.JavaPlugin

/**
 * Inactive-by-default clientlink channel listener.
 *
 * This listener only logs incoming packets when traffic logging is enabled.
 * No gameplay or admin actions are executed here yet.
 */
class PaperClientLinkChannelListener(
    private val plugin: JavaPlugin,
    private val logTraffic: Boolean
) : PluginMessageListener {

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (!logTraffic) return
        plugin.logger.info("ClientLink RX channel=$channel player=${player.name} bytes=${message.size}")
    }
}
