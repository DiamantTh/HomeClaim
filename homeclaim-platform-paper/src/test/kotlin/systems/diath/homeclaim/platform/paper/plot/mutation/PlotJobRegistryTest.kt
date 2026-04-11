package systems.diath.homeclaim.platform.paper.plot.mutation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
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

    @Test
    fun `limits concurrent jobs per world and job kind`() {
        val registry = PlotJobRegistry()
        val first = registry.tryAcquire(
            key = "plot:1",
            world = "plots",
            kind = PlotJobRegistry.JobKind.RESET,
            maxConcurrentPerWorld = 1,
            timeoutMillis = 60_000L
        )

        val second = registry.tryAcquire(
            key = "plot:2",
            world = "plots",
            kind = PlotJobRegistry.JobKind.RESET,
            maxConcurrentPerWorld = 1,
            timeoutMillis = 60_000L
        )

        assertNotNull(first)
        assertNull(second)
        assertEquals(1, registry.activeJobs(world = "plots", kind = PlotJobRegistry.JobKind.RESET))
        first?.close()
    }

    @Test
    fun `expires stale jobs when timeout is exceeded`() {
        var now = 1_000L
        val registry = PlotJobRegistry { now }

        val first = registry.tryAcquire(
            key = "plot:1",
            world = "plots",
            kind = PlotJobRegistry.JobKind.MUTATION,
            maxConcurrentPerWorld = 1,
            timeoutMillis = 100L
        )
        assertNotNull(first)

        now = 1_200L

        val second = registry.tryAcquire(
            key = "plot:2",
            world = "plots",
            kind = PlotJobRegistry.JobKind.MUTATION,
            maxConcurrentPerWorld = 1,
            timeoutMillis = 100L
        )

        assertNotNull(second)
        assertEquals(1, registry.activeJobs(world = "plots", kind = PlotJobRegistry.JobKind.MUTATION, timeoutMillis = 100L))
        second?.close()
    }
}
