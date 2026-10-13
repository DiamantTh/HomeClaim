package systems.diath.homeclaim.platform.paper.protection

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.event.world.PortalCreateEvent
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
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.ActionEndpoint
import systems.diath.homeclaim.core.policy.ActionRequest
import systems.diath.homeclaim.core.policy.BulkActionRequest
import systems.diath.homeclaim.core.policy.BulkActionResult
import systems.diath.homeclaim.core.policy.Decision
import systems.diath.homeclaim.core.policy.DecisionContext
import systems.diath.homeclaim.core.policy.DecisionEffect
import systems.diath.homeclaim.core.policy.DecisionReason
import java.util.UUID

class PortalProtectionListenerTest {
    @Test
    fun `portal creation checks every resulting block as build and cancels when one is denied`() {
        val worldId = UUID.randomUUID()
        val world = mock(World::class.java)
        `when`(world.uid).thenReturn(worldId)
        `when`(world.name).thenReturn("plots")
        var checked: BulkActionRequest? = null
        val guard = mock(PolicyGuard::class.java, Answer { invocation ->
            when (invocation.method.name) {
                "resolveEndpointAt" -> ActionEndpoint(invocation.getArgument<Position>(0))
                "checkBulk" -> {
                    val request = invocation.getArgument<BulkActionRequest>(0)
                    checked = request
                    BulkActionResult(request.correlationId, request.targets.map { target ->
                        val action = request.action.copy(target = target)
                        decision(action, if (target.position?.x == 8) DecisionEffect.DENY else DecisionEffect.ALLOW)
                    })
                }
                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        })
        val event = PortalCreateEvent(
            listOf(blockState(world, 7), blockState(world, 8)),
            world,
            null,
            PortalCreateEvent.CreateReason.FIRE
        )

        PortalProtectionListener(PaperProtectionAdapter(guard, null)).onPortalCreate(event)

        assertTrue(event.isCancelled)
        val request = requireNotNull(checked)
        assertEquals(Action.REGION_BUILD.key, request.action.actionKey)
        assertEquals(ActionActor.System("portal-create:fire"), request.action.actor)
        assertEquals("FIRE", request.action.context.attributes["reason"])
        assertEquals(listOf(7, 8), request.targets.map { it.position?.x })
    }

    private fun blockState(world: World, x: Int): BlockState {
        val location = mock(Location::class.java)
        `when`(location.world).thenReturn(world)
        `when`(location.blockX).thenReturn(x)
        `when`(location.blockY).thenReturn(64)
        `when`(location.blockZ).thenReturn(2)
        val block = mock(Block::class.java)
        `when`(block.location).thenReturn(location)
        return mock(BlockState::class.java).also { `when`(it.block).thenReturn(block) }
    }

    private fun decision(request: ActionRequest, effect: DecisionEffect) = Decision(
        effect = effect,
        reason = if (effect == DecisionEffect.DENY) DecisionReason.FLAG_DENY else DecisionReason.ALLOWED,
        context = DecisionContext(null, null, Action.REGION_BUILD, actionRequest = request)
    )
}
