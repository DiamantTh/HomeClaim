package systems.diath.homeclaim.platform.paper.command

import org.bukkit.command.CommandSender as BukkitCommandSender
import org.bukkit.entity.Player as BukkitPlayer
import systems.diath.homeclaim.core.command.CommandSender
import systems.diath.homeclaim.core.command.PlayerCommandSender

/**
 * Adapter: Convert Bukkit CommandSender to core CommandSender.
 */
class BukkitCommandSenderAdapter(private val sender: BukkitCommandSender) : CommandSender {
    override val name: String = sender.name
    override val isConsole: Boolean = sender !is BukkitPlayer
    
    override fun sendMessage(message: String) {
        sender.sendMessage(message)
    }
    
    override fun hasPermission(permission: String): Boolean {
        return sender.hasPermission(permission)
    }
}

/** Adapter for the audit command's existing core command contract. */
class BukkitPlayerCommandSenderAdapter(private val player: BukkitPlayer) : PlayerCommandSender {
    override val name: String = player.name
    override val playerId: java.util.UUID = player.uniqueId

    override fun sendMessage(message: String) = player.sendMessage(message)
    override fun hasPermission(permission: String): Boolean = player.hasPermission(permission)
}
