package systems.diath.homeclaim.platform.paper.protection

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.platform.PolicyGuard
import systems.diath.homeclaim.core.policy.Action
import systems.diath.homeclaim.core.policy.ActionEndpoint
import systems.diath.homeclaim.core.policy.ActionRequest
import systems.diath.homeclaim.core.policy.Decision
import systems.diath.homeclaim.core.policy.DecisionContext
import systems.diath.homeclaim.core.policy.DecisionEffect
import systems.diath.homeclaim.core.policy.DecisionReason
import java.util.UUID

class InventoryTransferProtectionTest {
    @Test
    fun `two positioned inventories retain source and destination for cross-boundary policy`() {
        val checked = checkTransfer(inventoryAt(4), inventoryAt(9))

        assertFalse(checked.cancelled)
        assertEquals(4, checked.request?.source?.position?.x)
        assertEquals(9, checked.request?.target?.position?.x)
        assertEquals("true", checked.request?.context?.attributes?.get("sourceSpatial"))
        assertEquals("true", checked.request?.context?.attributes?.get("destinationSpatial"))
    }

    @Test
    fun `single positioned endpoint is checked without inventing the missing inventory position`() {
        val sourceOnly = checkTransfer(inventoryAt(4), mock(Inventory::class.java))
        assertEquals(null, sourceOnly.request?.source)
        assertEquals(4, sourceOnly.request?.target?.position?.x)
        assertEquals("destination", sourceOnly.request?.context?.attributes?.get("positionlessEndpoint"))

        val destinationOnly = checkTransfer(mock(Inventory::class.java), inventoryAt(9))
        assertEquals(null, destinationOnly.request?.source)
        assertEquals(9, destinationOnly.request?.target?.position?.x)
        assertEquals("source", destinationOnly.request?.context?.attributes?.get("positionlessEndpoint"))
    }

    @Test
    fun `two positionless inventories remain outside spatial HomeClaim policy`() {
        val checked = checkTransfer(mock(Inventory::class.java), mock(Inventory::class.java))

        assertNull(checked.request)
        assertFalse(checked.cancelled)
    }

    private data class CheckedTransfer(val request: ActionRequest?, val cancelled: Boolean)

    private fun checkTransfer(source: Inventory, destination: Inventory): CheckedTransfer {
        var request: ActionRequest? = null
        val guard = mock(PolicyGuard::class.java, Answer { invocation ->
            when (invocation.method.name) {
                "resolveEndpointAt" -> ActionEndpoint(invocation.getArgument<Position>(0))
                "check", "checkAcross" -> {
                    val checkedRequest = invocation.getArgument<ActionRequest>(0)
                    request = checkedRequest
                    Decision(
                        DecisionEffect.ALLOW,
                        DecisionReason.ALLOWED,
                        context = DecisionContext(null, null, Action.HOPPER_TRANSFER, actionRequest = checkedRequest)
                    )
                }
                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        })
        val event = InventoryMoveItemEvent(source, mock(ItemStack::class.java), destination, true)
        AutomationProtectionListener(PaperProtectionAdapter(guard, null)).onInventoryMove(event)
        return CheckedTransfer(request, event.isCancelled)
    }

    private fun inventoryAt(x: Int): Inventory {
        val world = mock(World::class.java)
        val worldId = UUID.randomUUID()
        `when`(world.uid).thenReturn(worldId)
        `when`(world.name).thenReturn("plots-$x")
        val location = mock(Location::class.java)
        `when`(location.world).thenReturn(world)
        `when`(location.blockX).thenReturn(x)
        `when`(location.blockY).thenReturn(64)
        `when`(location.blockZ).thenReturn(2)
        val inventory = mock(Inventory::class.java)
        `when`(inventory.location).thenReturn(location)
        return inventory
    }
}
