package systems.diath.homeclaim.platform.paper.protection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import systems.diath.homeclaim.core.policy.ActionEndpoint
import systems.diath.homeclaim.core.policy.ActionRequest
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.platform.PolicyGuard
import systems.diath.homeclaim.core.policy.Action
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.CorrelationId
import systems.diath.homeclaim.core.policy.ModPolicyDefaults
import systems.diath.homeclaim.core.service.PolicyService
import systems.diath.homeclaim.core.service.RegionService
import java.util.UUID

class PaperProtectionAdapterTest {
    private val adapter = PaperProtectionAdapter(
        PolicyGuard(mock(PolicyService::class.java), mock(RegionService::class.java)),
        auditService = null
    )

    @Test
    fun `Paper event adaptation retains stable world ids source target and correlation`() {
        val platformWorldId = UUID.randomUUID()
        val world = WorldId(platformWorldId)
        val correlation = CorrelationId.newId()
        val source = Position(world, 1, 64, 2, "plots")
        val target = Position(world, 17, 64, 2, "plots")

        val request = adapter.request(
            actor = ActionActor.Automation("piston", "minecraft:piston"),
            action = Action.PISTON_MOVE.key,
            source = source,
            target = target,
            event = "BlockPistonExtendEvent",
            correlationId = correlation
        )

        assertEquals(Action.PISTON_MOVE.key, request.actionKey)
        assertEquals(correlation, request.correlationId)
        assertEquals(platformWorldId, request.source?.worldId?.value)
        assertEquals(platformWorldId, request.target?.worldId?.value)
        assertEquals(source, request.source?.position)
        assertEquals(target, request.target?.position)
        assertEquals("BlockPistonExtendEvent", request.context.eventName)
        assertEquals(ModPolicyDefaults(allowAutomation = true), request.context.modPolicyDefaults)
    }

    @Test
    fun `only explicitly native automation and system actors receive native policy defaults`() {
        val position = Position(WorldId(UUID.randomUUID()), 0, 64, 0, "plots")

        val nativeAutomation = adapter.request(
            ActionActor.Automation("hopper", "minecraft:hopper"), Action.HOPPER_TRANSFER.key,
            target = position, event = "InventoryMoveItemEvent"
        )
        val foreignAutomation = adapter.request(
            ActionActor.Automation("addon", "example:addon"), Action.HOPPER_TRANSFER.key,
            target = position, event = "InventoryMoveItemEvent"
        )
        val system = adapter.request(
            ActionActor.System("natural-growth"), Action.BLOCK_GROW.key,
            target = position, event = "BlockGrowEvent"
        )
        val entity = adapter.request(
            ActionActor.Entity(UUID.randomUUID(), "CREEPER"), Action.REGION_ENTITY_DAMAGE.key,
            target = position, event = "EntityDamageEvent"
        )

        assertEquals(ModPolicyDefaults(allowAutomation = true), nativeAutomation.context.modPolicyDefaults)
        assertNull(foreignAutomation.context.modPolicyDefaults)
        assertEquals(ModPolicyDefaults(allowServerTasks = true), system.context.modPolicyDefaults)
        assertNull(entity.context.modPolicyDefaults)
        assertNotNull(nativeAutomation.target)
    }

    @Test
    fun `source-target requests use the central cross-boundary decision path by default`() {
        val guard = mock(PolicyGuard::class.java)
        val adapter = PaperProtectionAdapter(guard, auditService = null)
        val world = WorldId(UUID.randomUUID())
        val request = ActionRequest(
            actor = ActionActor.Automation("piston", "minecraft:piston"),
            actionKey = Action.PISTON_MOVE.key,
            source = ActionEndpoint(Position(world, 1, 64, 2)),
            target = ActionEndpoint(Position(world, 17, 64, 2))
        )

        adapter.check(request)

        verify(guard).checkAcross(request)
        verify(guard, never()).check(request)
    }
}
