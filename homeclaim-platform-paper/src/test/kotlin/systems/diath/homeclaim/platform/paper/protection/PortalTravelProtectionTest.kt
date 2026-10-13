package systems.diath.homeclaim.platform.paper.protection

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.platform.PolicyGuard
import systems.diath.homeclaim.core.policy.Action
import systems.diath.homeclaim.core.policy.ActionEndpoint
import systems.diath.homeclaim.core.policy.ActionRequest
import systems.diath.homeclaim.core.policy.Decision
import systems.diath.homeclaim.core.policy.DecisionContext
import systems.diath.homeclaim.core.policy.DecisionEffect
import systems.diath.homeclaim.core.policy.DecisionReason
import java.util.UUID

class PortalTravelProtectionTest {
    @Test
    fun `portal travel remains subject to the normal cross-world teleport entry policy`() {
        var checked: ActionRequest? = null
        val guard = mock(PolicyGuard::class.java, Answer { invocation ->
            when (invocation.method.name) {
                "resolveEndpointAt" -> ActionEndpoint(invocation.getArgument<Position>(0))
                "check" -> {
                    val request = invocation.getArgument<ActionRequest>(0)
                    checked = request
                    Decision(
                        DecisionEffect.DENY,
                        DecisionReason.ENTRY_DENY,
                        context = DecisionContext(null, null, Action.REGION_TELEPORT_ENTER, actionRequest = request)
                    )
                }
                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        })
        val fromWorld = world()
        val toWorld = world()
        val player = mock(Player::class.java)
        `when`(player.uniqueId).thenReturn(UUID.randomUUID())
        `when`(player.name).thenReturn("visitor")
        val event = PlayerTeleportEvent(
            player,
            Location(fromWorld, 0.0, 64.0, 0.0),
            Location(toWorld, 10.0, 64.0, 10.0),
            PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
        )

        MovementProtectionListener(PaperProtectionAdapter(guard, null)).onTeleport(event)

        assertTrue(event.isCancelled)
        val request = requireNotNull(checked)
        assertEquals(Action.REGION_TELEPORT_ENTER.key, request.actionKey)
        assertEquals(fromWorld.uid, request.source?.worldId?.value)
        assertEquals(toWorld.uid, request.target?.worldId?.value)
        assertEquals("NETHER_PORTAL", request.context.attributes["cause"])
    }

    private fun world(): World = mock(World::class.java).also { world ->
        val id = UUID.randomUUID()
        `when`(world.uid).thenReturn(id)
        `when`(world.name).thenReturn("world-$id")
    }
}
