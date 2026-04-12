package systems.diath.homeclaim.platform.paper.clientlink

import java.nio.charset.StandardCharsets
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener
import org.bukkit.plugin.java.JavaPlugin
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.platform.paper.util.Permissions

/**
 * Inactive-by-default clientlink channel listener.
 *
 * The first active message type is read-only and moderator-focused:
 * - region_snapshot: returns region details around the player's location.
 */
class PaperClientLinkChannelListener(
    private val plugin: JavaPlugin,
    private val regionService: RegionService,
    private val logTraffic: Boolean
) : PluginMessageListener {

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (logTraffic) {
            plugin.logger.info("ClientLink RX channel=$channel player=${player.name} bytes=${message.size}")
        }

        val request = decodeRequest(message)
        if (request == null) {
            sendJson(channel, player, "{\"type\":\"error\",\"ok\":false,\"reason\":\"invalid_request\"}")
            return
        }

        when (request) {
            "region_snapshot" -> handleRegionSnapshot(channel, player)
            else -> sendJson(channel, player, "{\"type\":\"error\",\"ok\":false,\"reason\":\"unsupported_request\"}")
        }
    }

    private fun handleRegionSnapshot(channel: String, player: Player) {
        if (!Permissions.check(player, Permissions.MOD_INSPECT)) {
            sendJson(channel, player, "{\"type\":\"region_snapshot\",\"ok\":false,\"reason\":\"permission_denied\"}")
            return
        }

        val loc = player.location
        val worldId = loc.world.name
        val regionId = regionService.getRegionAt2D(worldId, loc.blockX, loc.blockZ)
        if (regionId == null) {
            sendJson(channel, player, "{\"type\":\"region_snapshot\",\"ok\":false,\"reason\":\"not_on_plot\"}")
            return
        }

        val region = regionService.getRegionById(regionId)
        if (region == null) {
            sendJson(channel, player, "{\"type\":\"region_snapshot\",\"ok\":false,\"reason\":\"region_not_found\"}")
            return
        }

        val role = region.roles.resolve(player.uniqueId, region.owner).name
        val canSeeOwnerName = player.hasPermission(Permissions.PLOT_INFO_OWNER_NAME) || player.isOp
        val ownerName = if (region.owner == UNCLAIMED_UUID) {
            "(unclaimed)"
        } else if (canSeeOwnerName) {
            Bukkit.getOfflinePlayer(region.owner).name ?: region.owner.toString().take(8)
        } else {
            "[redacted]"
        }

        val alias = region.metadata["alias"] ?: ""
        val description = region.metadata["description"] ?: ""
        val width = region.bounds.maxX - region.bounds.minX
        val depth = region.bounds.maxZ - region.bounds.minZ

        val json = """
            {"type":"region_snapshot","ok":true,"region":{
                "id":"${jsonEscape(region.id.value.toString())}",
                "world":"${jsonEscape(region.world)}",
                "ownerUuid":"${jsonEscape(region.owner.toString())}",
                "ownerName":"${jsonEscape(ownerName)}",
                "role":"${jsonEscape(role)}",
                "alias":"${jsonEscape(alias)}",
                "description":"${jsonEscape(description)}",
                "width":$width,
                "depth":$depth,
                "trustedCount":${region.roles.trusted.size},
                "memberCount":${region.roles.members.size},
                "bannedCount":${region.roles.banned.size},
                "forSale":${region.price > 0},
                "price":${region.price}
            }}
        """.trimIndent().replace("\n", "")

        sendJson(channel, player, json)
    }

    private fun decodeRequest(message: ByteArray): String? {
        val text = message.toString(StandardCharsets.UTF_8).trim().lowercase()
        return if (text.isEmpty()) null else text
    }

    private fun sendJson(channel: String, player: Player, json: String) {
        player.sendPluginMessage(plugin, channel, json.toByteArray(StandardCharsets.UTF_8))
        if (logTraffic) {
            plugin.logger.info("ClientLink TX channel=$channel player=${player.name} bytes=${json.toByteArray(StandardCharsets.UTF_8).size}")
        }
    }

    private fun jsonEscape(input: String): String {
        return input
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    companion object {
        private val UNCLAIMED_UUID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000000")
    }
}
