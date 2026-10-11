package systems.diath.homeclaim.core.model

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComponentTypeRegistryTest {
    @AfterTest
    fun clearDescriptors() {
        ComponentTypeRegistry.clearCustomTypes()
    }

    @Test
    fun `only component enum built ins are marked as runtime supported`() {
        assertEquals(
            setOf("ELEVATOR_PAD", "TELEPORT_PAD"),
            ComponentType.entries.map { it.name }.toSet()
        )
        ComponentType.entries.forEach { type ->
            assertTrue(ComponentTypeRegistry.isRegistered(type.name))
            assertEquals(ComponentTypeRuntimeSupport.BUILTIN, ComponentTypeRegistry.runtimeSupport(type.name))
        }

        ComponentTypeRegistry.registerType(
            "EXAMPLE_DESCRIPTOR",
            ComponentTypeDescriptor("Example", "Metadata only")
        )
        assertTrue(ComponentTypeRegistry.isRegistered("EXAMPLE_DESCRIPTOR"))
        assertEquals(ComponentTypeRuntimeSupport.DESCRIPTOR_ONLY, ComponentTypeRegistry.runtimeSupport("EXAMPLE_DESCRIPTOR"))
        assertEquals(ComponentTypeRuntimeSupport.UNKNOWN, ComponentTypeRegistry.runtimeSupport("UNKNOWN"))
    }
}
