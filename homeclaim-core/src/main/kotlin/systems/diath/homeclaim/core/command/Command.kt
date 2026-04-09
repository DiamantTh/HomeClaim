package systems.diath.homeclaim.core.command

import systems.diath.homeclaim.core.i18n.I18nProvider
import systems.diath.homeclaim.core.i18n.DefaultI18nProvider
import java.util.UUID

/**
 * Platform-agnostic command abstraction.
 */
interface CommandSender {
    val name: String
    val isConsole: Boolean
    
    fun sendMessage(message: String)
    fun hasPermission(permission: String): Boolean
}

/**
 * Platform-agnostic player command sender.
 */
interface PlayerCommandSender : CommandSender {
    val playerId: UUID
    override val isConsole: Boolean
        get() = false
}

/**
 * Console command sender.
 */
interface ConsoleCommandSender : CommandSender {
    override val isConsole: Boolean
        get() = true
}

/**
 * Command abstraction - platform-independent.
 */
interface Command {
    val name: String
    val aliases: List<String>
    val description: String
    val permission: String?
    val minArgs: Int
    val maxArgs: Int
    
    /**
     * Execute command.
     * @return true if command was handled successfully
     */
    fun execute(sender: CommandSender, args: Array<String>): Boolean
    
    /**
     * Get tab completions.
     */
    fun getTabCompletions(sender: CommandSender, args: Array<String>): List<String> {
        return emptyList()
    }
}

/**
 * Base implementation for commands.
 */
abstract class AbstractCommand(
    override val name: String,
    override val description: String = "",
    override val permission: String? = null,
    override val aliases: List<String> = emptyList(),
    override val minArgs: Int = 0,
    override val maxArgs: Int = Int.MAX_VALUE,
    protected val i18n: I18nProvider = DefaultI18nProvider()
) : Command {
    
    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        // Check permission
        val perm = permission
        if (perm != null && !sender.hasPermission(perm)) {
            sender.sendMessage(i18n.msg("command.no_permission"))
            return true
        }
        
        // Check arg count
        if (args.size < minArgs || args.size > maxArgs) {
            sender.sendMessage(i18n.msg("command.usage", name, getUsage()))
            return true
        }
        
        return onExecute(sender, args)
    }
    
    /**
     * Override to implement command logic.
     */
    abstract fun onExecute(sender: CommandSender, args: Array<String>): Boolean
    
    /**
     * Get usage string (override for custom).
     */
    open fun getUsage(): String = "<args>"
}

/**
 * Player-only command.
 */
abstract class PlayerCommand(
    name: String,
    description: String = "",
    permission: String? = null,
    aliases: List<String> = emptyList(),
    minArgs: Int = 0,
    maxArgs: Int = Int.MAX_VALUE
) : AbstractCommand(name, description, permission, aliases, minArgs, maxArgs) {
    
    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (sender !is PlayerCommandSender) {
            sender.sendMessage(i18n.msg("command.player_only"))
            return true
        }
        
        return super.execute(sender, args)
    }
    
    override fun onExecute(sender: CommandSender, args: Array<String>): Boolean {
        return onExecutePlayer(sender as PlayerCommandSender, args)
    }
    
    abstract fun onExecutePlayer(sender: PlayerCommandSender, args: Array<String>): Boolean
}

/**
 * Command registry.
 */
interface CommandRegistry {
    
    /**
     * Register command.
     */
    fun register(command: Command)
    
    /**
     * Unregister command.
     */
    fun unregister(name: String)
    
    /**
     * Get command by name or alias.
     */
    fun getCommand(name: String): Command?
    
    /**
     * Execute command.
     */
    fun execute(sender: CommandSender, label: String, args: Array<String>): Boolean {
        val command = getCommand(label.lowercase()) ?: return false
        return command.execute(sender, args)
    }
    
    /**
     * Get tab completions.
     */
    fun getTabCompletions(sender: CommandSender, label: String, args: Array<String>): List<String> {
        val command = getCommand(label.lowercase()) ?: return emptyList()
        return command.getTabCompletions(sender, args)
    }
}

/**
 * In-memory command registry.
 */
class SimpleCommandRegistry : CommandRegistry {
    private val commands = mutableMapOf<String, Command>()
    
    override fun register(command: Command) {
        commands[command.name.lowercase()] = command
        command.aliases.forEach { alias ->
            commands[alias.lowercase()] = command
        }
    }
    
    override fun unregister(name: String) {
        commands.remove(name.lowercase())
    }
    
    override fun getCommand(name: String): Command? {
        return commands[name.lowercase()]
    }
}
