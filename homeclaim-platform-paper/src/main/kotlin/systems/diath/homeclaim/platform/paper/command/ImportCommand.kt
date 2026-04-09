package systems.diath.homeclaim.platform.paper.command

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import systems.diath.homeclaim.platform.paper.migration.PlotImportService
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.core.service.AuditService
import systems.diath.homeclaim.platform.paper.I18n

/**
 * Command: /homeclaim import
 * 
 * Verwalte externe Plot-Importe (WorldGuard, PlotSquared)
 * 
 * Syntax:
 * - /homeclaim import worldguard [world]  - Importiere WorldGuard Regionen
 * - /homeclaim import plotsquared          - Importiere PlotSquared Plots
 * - /homeclaim import status [importId]    - Zeige Import-Status
 * - /homeclaim import list                 - Liste aktive Imports
 * - /homeclaim import cancel <importId>    - Cancelle einen Import
 */
class ImportCommand(
    private val regionService: RegionService,
    private val auditService: AuditService? = null,
    private val i18n: I18n = I18n()
) : CommandExecutor, TabCompleter {
    
    private val importService = PlotImportService(regionService, auditService)
    
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("homeclaim.admin.import")) {
            sender.sendMessage(i18n.msg("import.no.permission"))
            return true
        }
        
        if (args.isEmpty()) {
            showHelp(sender)
            return true
        }
        
        return when (args[0].lowercase()) {
            "worldguard" -> handleWorldGuardImport(sender, args)
            "plotsquared", "ps" -> handlePlotSquaredImport(sender)
            "status" -> handleStatus(sender, args)
            "list" -> handleList(sender)
            "cancel" -> handleCancel(sender, args)
            else -> {
                sender.sendMessage(i18n.msg("import.unknown.command", args[0]))
                showHelp(sender)
                true
            }
        }
    }
    
    private fun handleWorldGuardImport(sender: CommandSender, @Suppress("UNUSED_PARAMETER") _args: Array<out String>): Boolean {
        sender.sendMessage(i18n.msg("import.worldguard.starting"))
        
        val plots = PlotImportService.WorldGuardSource.extract()
        if (plots.isEmpty()) {
            sender.sendMessage(i18n.msg("import.worldguard.none.found"))
            return true
        }
        
        val importId = importService.startImport(plots) { progress ->
            if (progress.status == PlotImportService.ImportStatus.COMPLETED) {
                sender.sendMessage(i18n.msg("import.completed"))
                sender.sendMessage(i18n.msg("import.successful.details", progress.importedPlots.toString(), progress.totalPlots.toString()))
                if (progress.failedPlots > 0) {
                    sender.sendMessage(i18n.msg("import.errors.details", progress.failedPlots.toString()))
                }
            }
        }
        
        sender.sendMessage(i18n.msg("import.started", importId))
        sender.sendMessage(i18n.msg("import.use.status", importId))
        return true
    }
    
    private fun handlePlotSquaredImport(sender: CommandSender): Boolean {
        sender.sendMessage(i18n.msg("import.plotsquared.starting"))
        
        val plots = PlotImportService.PlotSquaredSource.extract()
        if (plots.isEmpty()) {
            sender.sendMessage(i18n.msg("import.plotsquared.none.found"))
            return true
        }
        
        val importId = importService.startImport(plots) { progress ->
            if (progress.status == PlotImportService.ImportStatus.COMPLETED) {
                sender.sendMessage(i18n.msg("import.completed"))
                sender.sendMessage(i18n.msg("import.successful.details", progress.importedPlots.toString(), progress.totalPlots.toString()))
                if (progress.failedPlots > 0) {
                    sender.sendMessage(i18n.msg("import.errors.details", progress.failedPlots.toString()))
                }
            }
        }
        
        sender.sendMessage(i18n.msg("import.started", importId))
        sender.sendMessage(i18n.msg("import.use.status", importId))
        return true
    }
    
    private fun handleStatus(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.size < 2) {
            sender.sendMessage(i18n.msg("import.status.syntax"))
            return true
        }
        
        val importId = args[1]
        val progress = importService.getProgress(importId)
        if (progress == null) {
            sender.sendMessage(i18n.msg("import.not.found", importId))
            return true
        }
        
        sender.sendMessage(i18n.msg("import.status.title", importId))
        sender.sendMessage(i18n.msg("import.status.details", progress.status.name))
        sender.sendMessage(i18n.msg("import.status.successful", progress.importedPlots.toString(), progress.totalPlots.toString()))
        if (progress.failedPlots > 0) {
            sender.sendMessage(i18n.msg("import.status.errors", progress.failedPlots.toString()))
        }
        
        val elapsed = (progress.endTime ?: System.currentTimeMillis()) - progress.startTime
        sender.sendMessage(i18n.msg("import.status.elapsed", (elapsed / 1000).toString()))
        
        if (progress.errors.isNotEmpty()) {
            sender.sendMessage(i18n.msg("import.status.errors.label"))
            progress.errors.take(5).forEach { sender.sendMessage(i18n.msg("import.status.error.item", it)) }
            if (progress.errors.size > 5) {
                sender.sendMessage(i18n.msg("import.status.more.errors", (progress.errors.size - 5).toString()))
            }
        }
        
        return true
    }
    
    private fun handleList(sender: CommandSender): Boolean {
        val active = importService.listActiveImports()
        if (active.isEmpty()) {
            sender.sendMessage(i18n.msg("import.none.active"))
            return true
        }
        
        sender.sendMessage(i18n.msg("import.active.list"))
        active.forEach { progress ->
            val pct = (progress.importedPlots * 100 / progress.totalPlots).coerceIn(0, 100)
            sender.sendMessage(i18n.msg("import.list.item", progress.id, pct.toString(), progress.importedPlots.toString(), progress.totalPlots.toString()))
        }
        
        return true
    }
    
    private fun handleCancel(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.size < 2) {
            sender.sendMessage(i18n.msg("import.cancel.syntax"))
            return true
        }
        
        val importId = args[1]
        if (importService.cancelImport(importId)) {
            sender.sendMessage(i18n.msg("import.cancelled", importId))
        } else {
            sender.sendMessage(i18n.msg("import.cancel.failed", importId))
        }
        
        return true
    }
    
    private fun showHelp(sender: CommandSender) {
        sender.sendMessage(i18n.msg("import.help.title"))
        sender.sendMessage(i18n.msg("import.help.worldguard"))
        sender.sendMessage(i18n.msg("import.help.plotsquared"))
        sender.sendMessage(i18n.msg("import.help.status"))
        sender.sendMessage(i18n.msg("import.help.list"))
        sender.sendMessage(i18n.msg("import.help.cancel"))
    }
    
    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("homeclaim.admin.import")) return emptyList()
        
        return when {
            args.size == 1 -> listOf("worldguard", "plotsquared", "status", "list", "cancel")
                .filter { it.startsWith(args[0], ignoreCase = true) }
            args.size == 2 && args[0].lowercase() == "status" -> {
                importService.listActiveImports().map { it.id }
                    .filter { it.startsWith(args[1], ignoreCase = true) }
            }
            args.size == 2 && args[0].lowercase() == "cancel" -> {
                importService.listActiveImports().map { it.id }
                    .filter { it.startsWith(args[1], ignoreCase = true) }
            }
            else -> emptyList()
        }
    }
}
