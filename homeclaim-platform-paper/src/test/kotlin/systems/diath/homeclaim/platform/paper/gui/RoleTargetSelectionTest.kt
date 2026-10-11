package systems.diath.homeclaim.platform.paper.gui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import systems.diath.homeclaim.core.model.RegionRole
import java.util.UUID

class RoleTargetSelectionTest {
    @Test
    fun `role target selection excludes owner and self ban with stable name order`() {
        val owner = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val alex = UUID.randomUUID()
        val zoe = UUID.randomUUID()
        val selected = RoleTargetSelection.eligible(
            listOf(
                RoleTargetCandidate(zoe, "zoe"),
                RoleTargetCandidate(owner, "Owner"),
                RoleTargetCandidate(actor, "Actor"),
                RoleTargetCandidate(alex, "Alex")
            ),
            owner,
            actor,
            RegionRole.BANNED
        )

        assertEquals(listOf(alex, zoe), selected.map(RoleTargetCandidate::playerId))
        assertFalse(selected.any { it.playerId == owner || it.playerId == actor })
    }

    @Test
    fun `role target selection permits self for non-ban assignment`() {
        val owner = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val selected = RoleTargetSelection.eligible(
            listOf(RoleTargetCandidate(actor, "Actor")),
            owner,
            actor,
            RegionRole.TRUSTED
        )

        assertEquals(listOf(actor), selected.map(RoleTargetCandidate::playerId))
    }
}
