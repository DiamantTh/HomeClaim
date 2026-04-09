package systems.diath.homeclaim.platform.paper.command

import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender as BukkitCommandSender
import org.bukkit.command.Command as BukkitCommand
import org.bukkit.entity.Player as BukkitPlayer
import systems.diath.homeclaim.core.command.CommandSender
import systems.diath.homeclaim.core.command.PlayerCommandSender
import systems.diath.homeclaim.core.command.Command
import systems.diath.homeclaim.core.command.CommandRegistry

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

/**
 * Adapter: Convert Bukkit Player CommandSender to core PlayerCommandSender.
 */
class BukkitPlayerCommandSenderAdapter(private val player: BukkitPlayer) : PlayerCommandSender {
    override val name: String = player.name
    override val playerId: java.util.UUID = player.uniqueId
    
    override fun sendMessage(message: String) {
        player.sendMessage(message)
    }
    
    override fun hasPermission(permission: String): Boolean {
        return player.hasPermission(permission)
    }
}

/**
 * Bridge: Bukkit CommandExecutor → Core CommandRegistry.
 */
class BukkitCommandExecutorBridge(
    private val registry: CommandRegistry
) : CommandExecutor {
    
    override fun onCommand(
        sender: BukkitCommandSender,
        command: BukkitCommand,
        label: String,
        args: Array<String>
    ): Boolean {
        val coreSender = if (sender is BukkitPlayer) {
            BukkitPlayerCommandSenderAdapter(sender)
        } else {
            BukkitCommandSenderAdapter(sender)
        }
        
        return registry.execute(coreSender, label, args)
    }
}
