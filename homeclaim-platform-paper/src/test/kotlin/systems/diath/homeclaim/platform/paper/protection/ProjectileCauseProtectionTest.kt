package systems.diath.homeclaim.platform.paper.protection

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.block.BlockIgniteEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.platform.PolicyGuard
import systems.diath.homeclaim.core.policy.Action
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.ActionEndpoint
import systems.diath.homeclaim.core.policy.ActionRequest
import systems.diath.homeclaim.core.policy.Decision
import systems.diath.homeclaim.core.policy.DecisionContext
import systems.diath.homeclaim.core.policy.DecisionEffect
import systems.diath.homeclaim.core.policy.DecisionReason
import java.util.UUID

class ProjectileCauseProtectionTest {
    @Test
    fun `projectile ignition is evaluated as its player shooter at the shooter source`() {
        val world = mock(World::class.java)
        val worldId = UUID.randomUUID()
        `when`(world.uid).thenReturn(worldId)
        `when`(world.name).thenReturn("plots")
        val shooterLocation = location(world, 2)
        val shooter = mock(Player::class.java)
        `when`(shooter.uniqueId).thenReturn(UUID.randomUUID())
        `when`(shooter.location).thenReturn(shooterLocation)
        val projectile = mock(Projectile::class.java)
        `when`(projectile.shooter).thenReturn(shooter)
        val projectileLocation = location(world, 5)
        `when`(projectile.location).thenReturn(projectileLocation)
        val target = mock(Block::class.java)
        val targetLocation = location(world, 8)
        `when`(target.location).thenReturn(targetLocation)
        var checked: ActionRequest? = null
        val guard = mock(PolicyGuard::class.java, Answer { invocation ->
            when (invocation.method.name) {
                "resolveEndpointAt" -> ActionEndpoint(invocation.getArgument<Position>(0))
                "checkAcross" -> {
                    val request = invocation.getArgument<ActionRequest>(0)
                    checked = request
                    Decision(
                        DecisionEffect.ALLOW,
                        DecisionReason.ALLOWED,
                        context = DecisionContext(null, null, Action.REGION_FIRE, actionRequest = request)
                    )
                }
                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        })
        val event = BlockIgniteEvent(target, BlockIgniteEvent.IgniteCause.ARROW, projectile)

        AutomationProtectionListener(PaperProtectionAdapter(guard, null)).onIgnite(event)

        assertFalse(event.isCancelled)
        assertEquals(Action.REGION_FIRE.key, checked?.actionKey)
        assertEquals(ActionActor.Player(shooter.uniqueId), checked?.actor)
        assertEquals(2, checked?.source?.position?.x)
        assertEquals(8, checked?.target?.position?.x)
        assertEquals("ARROW", checked?.context?.attributes?.get("cause"))
    }

    private fun location(world: World, x: Int): Location = mock(Location::class.java).also {
        `when`(it.world).thenReturn(world)
        `when`(it.blockX).thenReturn(x)
        `when`(it.blockY).thenReturn(64)
        `when`(it.blockZ).thenReturn(2)
    }
}
