package systems.diath.homeclaim.platform.paper.gui

import systems.diath.homeclaim.core.model.RegionRole
import java.util.UUID

/** Immutable candidate data captured while a role-selection menu is rendered. */
internal data class RoleTargetCandidate(val playerId: UUID, val displayName: String)

/**
 * Keeps target eligibility independent from Bukkit inventory rendering so that the
 * same deterministic selection can be exercised without a connected client.
 */
internal object RoleTargetSelection {
    fun eligible(
        online: Collection<RoleTargetCandidate>,
        ownerId: UUID,
        actorId: UUID,
        role: RegionRole
    ): List<RoleTargetCandidate> = online.asSequence()
        .filter { it.playerId != ownerId }
        .filter { role != RegionRole.BANNED || it.playerId != actorId }
        .sortedWith(compareBy<RoleTargetCandidate> { it.displayName.lowercase() }.thenBy { it.playerId })
        .toList()
}
