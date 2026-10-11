package systems.diath.homeclaim.platform.paper

import org.bukkit.Bukkit
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.service.RegionMutationAuthorizer
import systems.diath.homeclaim.core.service.RegionMutationCapability
import systems.diath.homeclaim.core.policy.ActionActor

/** Paper capability/ownership adapter for the actor-aware core mutation services. */
class PaperRegionMutationAuthorizer : RegionMutationAuthorizer {
    override fun isAuthorized(
        actor: ActionActor,
        region: Region?,
        capability: RegionMutationCapability
    ): Boolean {
        if (actor is ActionActor.System) return true
        val playerId = (actor as? ActionActor.Player)?.playerId ?: return false
        val player = Bukkit.getPlayer(playerId) ?: return false
        val adminNode = when (capability) {
            RegionMutationCapability.COMPONENT_CHANGE -> "homeclaim.admin.components"
            else -> "homeclaim.admin"
        }
        if (player.hasPermission(adminNode) || player.hasPermission("homeclaim.admin.*")) return true
        if (capability == RegionMutationCapability.GLOBAL_PROFILE_CHANGE) return false
        val target = region ?: return false
        if (target.owner == playerId) return true
        return capability in setOf(
            RegionMutationCapability.COMPONENT_CHANGE,
            RegionMutationCapability.METADATA_CHANGE
        ) && playerId in target.roles.trusted
    }
}
