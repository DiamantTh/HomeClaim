package systems.diath.homeclaim.platform.paper.plot.mutation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

class PlotJobRegistryTest {

    @Test
    fun `runIfIdle executes action when the key is free`() {
        val registry = PlotJobRegistry()
        val action = mock(Runnable::class.java)

        val started = registry.runIfIdle("plot:1", action)

        assertTrue(started)
        verify(action, times(1)).run()
        assertFalse(registry.isActive("plot:1"))
    }

    @Test
    fun `runIfIdle skips duplicate work while a handle is active`() {
        val registry = PlotJobRegistry()
        val handle = registry.tryAcquire("plot:1")
        val action = mock(Runnable::class.java)

        val started = registry.runIfIdle("plot:1", action)

        assertFalse(started)
        verify(action, never()).run()
        handle?.close()
        assertFalse(registry.isActive("plot:1"))
    }

    @Test
    fun `runIfIdle releases key after failure`() {
        val registry = PlotJobRegistry()
        val action = mock(Runnable::class.java)
        doThrow(IllegalStateException("boom")).`when`(action).run()

        assertThrows(IllegalStateException::class.java) {
            registry.runIfIdle("plot:1", action)
        }
        assertFalse(registry.isActive("plot:1"))
    }
}
