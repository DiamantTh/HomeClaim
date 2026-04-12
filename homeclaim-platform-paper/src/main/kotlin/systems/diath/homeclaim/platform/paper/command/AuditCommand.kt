package systems.diath.homeclaim.platform.paper.command

import systems.diath.homeclaim.core.command.AbstractCommand
import systems.diath.homeclaim.core.command.CommandSender
import systems.diath.homeclaim.core.command.PlayerCommandSender
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.service.AuditEntry
import systems.diath.homeclaim.core.service.AuditService
import systems.diath.homeclaim.core.service.AuditTaxonomy
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.platform.paper.I18n
import org.bukkit.Bukkit
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Audit log viewer command. Requires homeclaim.audit.view permission.
 * Shows recent actions logged by other players in region/plot context.
 *
 * Usage:
 * /homeclaim audit              # Show logs for current plot
 * /homeclaim audit <PlotID>     # Show logs for specific plot
 * /homeclaim audit <PlotID> 12  # Show logs for plot in last 12 hours
 *
 * Examples:
 * /homeclaim audit                              # Current plot, last 24 hours
 * /homeclaim audit abc123-def4-5678-90ab        # Specific plot logs
 * /homeclaim audit abc123-def4-5678-90ab 6      # Specific plot, last 6 hours
 */
class AuditCommand(
    private val auditService: AuditService?,
    private val regionService: RegionService,
    i18nImpl: I18n
) : AbstractCommand(
    name = "audit",
    permission = "homeclaim.audit.view",
    description = "View audit logs for plot activities",
    minArgs = 0,
    maxArgs = 2,
    i18n = i18nImpl
) {
    
    override fun onExecute(sender: CommandSender, args: Array<String>): Boolean {
        if (auditService == null) {
            sender.sendMessage(i18n.msg("audit.service.not.configured"))
            return true
        }
        
        // Determine region/plot ID
        val regionId: RegionId = when {
            args.isEmpty() -> {
                // No args: use current plot (player must be in world)
                if (sender !is PlayerCommandSender) {
                    sender.sendMessage(i18n.msg("audit.console.needs.plotid"))
                    return true
                }
                val player = Bukkit.getPlayer(sender.playerId)
                if (player == null) {
                    sender.sendMessage(i18n.msg("audit.player.not.found"))
                    return true
                }
                val loc = player.location
                val regionId = regionService.getRegionAt(loc.world?.name ?: "unknown", loc.blockX, loc.blockY, loc.blockZ)
                if (regionId == null) {
                    sender.sendMessage(i18n.msg("audit.not.in.plot"))
                    return true
                }
                regionId
            }
            else -> {
                // First arg: try parse as UUID (PlotID)
                try {
                    systems.diath.homeclaim.core.model.RegionId(UUID.fromString(args[0]))
                } catch (e: IllegalArgumentException) {
                    sender.sendMessage(i18n.msg("audit.invalid.plotid", arrayOf(args[0])))
                    return true
                }
            }
        }
        
        // Parse hours filter
        val hoursBack = args.getOrNull(1)?.toIntOrNull() ?: 24
        
        // Get entries for this region
        val entries = getAuditEntriesForRegion(auditService, regionId, hoursBack)
        
        if (entries.isEmpty()) {
            sender.sendMessage(i18n.msg("audit.no.logs.found", arrayOf(regionId.toString())))
            return true
        }
        
        // Get region info
        val region = regionService.getRegionById(regionId)
        val ownerName = if (region != null) {
            Bukkit.getPlayer(region.owner)?.name ?: region.owner.toString().take(8)
        } else {
            "Unknown"
        }
        
        // Format and send results
        sender.sendMessage(i18n.msg("audit.header.separator"))
        sender.sendMessage(i18n.msg("audit.header.title", arrayOf(hoursBack.toString())))
        sender.sendMessage(i18n.msg("audit.header.plotid", arrayOf(regionId.toString())))
        sender.sendMessage(i18n.msg("audit.header.owner", arrayOf(ownerName)))
        sender.sendMessage(i18n.msg("audit.header.separator"))
        
        for (entry in entries.take(50)) {
            val actorId = entry.actorId
            val playerName = if (actorId != null) {
                Bukkit.getPlayer(actorId)?.name ?: actorId.toString().take(8)
            } else "?"
            val timestamp = formatTime(entry.createdAt)
            
            val message = when (entry.action) {
                AuditTaxonomy.Action.PLACE_DENIED, AuditTaxonomy.Action.BREAK_DENIED -> {
                    val reason = entry.payload["reason"] ?: "unknown"
                    val block = entry.payload["block"] ?: "?"
                    i18n.msg("audit.denied", arrayOf(playerName, entry.action.replace("_DENIED", ""), block, reason, timestamp))
                }
                AuditTaxonomy.Action.PLACE_ALLOWED, AuditTaxonomy.Action.BREAK_ALLOWED -> {
                    val block = entry.payload["block"] ?: "?"
                    i18n.msg("audit.allowed", arrayOf(playerName, entry.action.replace("_ALLOWED", ""), block, timestamp))
                }
                AuditTaxonomy.Action.PVP_DENIED -> {
                    val targetId = entry.targetId
                    val targetName = if (targetId != null) {
                        Bukkit.getPlayer(targetId)?.name ?: targetId.toString().take(8)
                    } else "?"
                    i18n.msg("audit.pvp.denied", arrayOf(playerName, targetName, timestamp))
                }
                AuditTaxonomy.Action.ENTER_DENIED -> {
                    val vehicleType = entry.payload["vehicleType"] ?: "?"
                    i18n.msg("audit.enter.denied", arrayOf(playerName, vehicleType, timestamp))
                }
                else -> {
                    i18n.msg("audit.info", arrayOf(playerName, entry.action, timestamp))
                }
            }
            sender.sendMessage(message)
        }
        
        sender.sendMessage(i18n.msg("audit.header.separator"))
        sender.sendMessage(i18n.msg("audit.summary", arrayOf(entries.size.coerceAtMost(50).toString(), entries.size.toString())))
        
        return true
    }
    
    private fun getAuditEntriesForRegion(@Suppress("UNUSED_PARAMETER") auditService: AuditService, @Suppress("UNUSED_PARAMETER") regionId: RegionId, @Suppress("UNUSED_PARAMETER") hoursBack: Int): List<AuditEntry> {
        // TODO: Implement JDBC query method with region filter
        // For now, returning empty list as JDBC implementation is needed
        return emptyList()
    }
    
    private fun isPositionInRegion(entry: AuditEntry, regionId: RegionId): Boolean {
        val world = entry.payload["world"] as? String ?: return false
        val x = entry.payload["x"] as? Int ?: return false
        val y = entry.payload["y"] as? Int ?: return false
        val z = entry.payload["z"] as? Int ?: return false
        
        val foundRegionId = regionService.getRegionAt(world, x, y, z)
        return foundRegionId == regionId
    }
    
    private fun formatTime(instant: Instant): String {
        val now = Instant.now()
        val seconds = ChronoUnit.SECONDS.between(instant, now)
        
        return when {
            seconds < 60 -> i18n.msg("audit.time.seconds", seconds.toString())
            seconds < 3600 -> i18n.msg("audit.time.minutes", (seconds / 60).toString())
            seconds < 86400 -> i18n.msg("audit.time.hours", (seconds / 3600).toString())
            else -> i18n.msg("audit.time.days", (seconds / 86400).toString())
        }
    }
}
