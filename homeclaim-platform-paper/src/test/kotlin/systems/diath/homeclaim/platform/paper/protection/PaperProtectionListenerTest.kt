package systems.diath.homeclaim.platform.paper.protection

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.event.block.BlockPistonExtendEvent
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
import systems.diath.homeclaim.core.policy.ActionRequest
import systems.diath.homeclaim.core.policy.Decision
import systems.diath.homeclaim.core.policy.DecisionContext
import systems.diath.homeclaim.core.policy.DecisionEffect
import systems.diath.homeclaim.core.policy.DecisionReason
import java.util.UUID

class PaperProtectionListenerTest {
    @Test
    fun `piston denies when a moved block crosses a protected source-target boundary`() {
        val platformWorldId = UUID.randomUUID()
        val world = mock(World::class.java)
        `when`(world.uid).thenReturn(platformWorldId)
        `when`(world.name).thenReturn("plots")

        val piston = blockAt(world, Material.STICKY_PISTON, 1)
        val moved = blockAt(world, Material.STONE, 2)
        val destination = blockAt(world, Material.AIR, 3)
        `when`(moved.getRelative(BlockFace.EAST)).thenReturn(destination)
        val pistonData = mock(BlockData::class.java)
        `when`(piston.blockData).thenReturn(pistonData)
        `when`(pistonData.material).thenReturn(Material.STICKY_PISTON)

        var crossBoundaryRequest: ActionRequest? = null
        val guard = mock(PolicyGuard::class.java, Answer { invocation ->
            when (invocation.method.name) {
                "resolveEndpointAt" -> systems.diath.homeclaim.core.policy.ActionEndpoint(
                    invocation.getArgument<Position>(0)
                )
                "checkAcross" -> {
                    val request = invocation.getArgument<ActionRequest>(0)
                    crossBoundaryRequest = request
                    decision(request, DecisionEffect.DENY)
                }
                "check" -> decision(invocation.getArgument(0), DecisionEffect.ALLOW)
                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        })

        val event = BlockPistonExtendEvent(piston, listOf(moved), BlockFace.EAST)
        AutomationProtectionListener(PaperProtectionAdapter(guard, auditService = null)).onPistonExtend(event)

        assertTrue(event.isCancelled)
        val request = requireNotNull(crossBoundaryRequest)
        assertEquals(Action.PISTON_MOVE.key, request.actionKey)
        assertEquals(2, request.source?.position?.x)
        assertEquals(3, request.target?.position?.x)
        assertEquals(platformWorldId, request.source?.worldId?.value)
        assertEquals(platformWorldId, request.target?.worldId?.value)
        assertEquals(ActionActor.Automation("piston:${piston.location.toHomeClaimPosition()}", "minecraft:piston"), request.actor)
    }

    private fun decision(request: ActionRequest, effect: DecisionEffect) = Decision(
        effect = effect,
        reason = if (effect == DecisionEffect.DENY) DecisionReason.FLAG_DENY else DecisionReason.ALLOWED,
        context = DecisionContext(
            playerId = UUID(0L, 0L),
            regionId = null,
            action = Action.PISTON_MOVE,
            world = request.worldId,
            actionRequest = request
        )
    )

    private fun blockAt(world: World, material: Material, x: Int): Block {
        val location = mock(Location::class.java)
        `when`(location.world).thenReturn(world)
        `when`(location.blockX).thenReturn(x)
        `when`(location.blockY).thenReturn(64)
        `when`(location.blockZ).thenReturn(2)
        val block = mock(Block::class.java)
        `when`(block.location).thenReturn(location)
        `when`(block.type).thenReturn(material)
        return block
    }
}
