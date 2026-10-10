package systems.diath.homeclaim.platform.paper.permission

import org.bukkit.Bukkit
import systems.diath.homeclaim.core.permission.CapabilityPermissionProvider
import systems.diath.homeclaim.core.permission.PermissionContext
import systems.diath.homeclaim.core.permission.PermissionResult
import systems.diath.homeclaim.core.policy.ActorKind
import systems.diath.homeclaim.core.policy.PolicyActorContext
import java.util.UUID

/** Paper edge adapter. Offline actors have no live Bukkit permission result. */
class PaperCapabilityPermissionProvider : CapabilityPermissionProvider {
    override fun check(
        actor: PolicyActorContext,
        permission: String,
        context: PermissionContext
    ): PermissionResult {
        if (actor.kind != ActorKind.PLAYER) return PermissionResult.UNDEFINED
        val playerId = actor.actorId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return PermissionResult.UNDEFINED
        val player = Bukkit.getPlayer(playerId) ?: return PermissionResult.UNDEFINED
        val granted = player.hasPermission(permission) ||
            (permission == systems.diath.homeclaim.core.permission.PolicyPermissions.ENTRY_BYPASS &&
                player.hasPermission("homeclaim.admin.entry.denied"))
        return if (granted) PermissionResult.GRANTED else PermissionResult.DENIED
    }
}
