package systems.diath.homeclaim.platform.paper.plot

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import systems.diath.homeclaim.core.store.InMemoryRegionStore

class PlotWorldInitializerTest {

    @Test
    fun `initialize plots sync is idempotent for the same world`() {
        val regionStore = InMemoryRegionStore()
        val plugin = mock(Plugin::class.java)
        val initializer = PlotWorldInitializer(regionStore, plugin)
        val config = PlotWorldConfig(
            worldName = "plots",
            plotSize = 16,
            roadWidth = 4,
            plotsPerSide = 2,
            minGenHeight = 0,
            plotHeight = 64
        )

        val firstCreated = initializer.initializePlotsSync("plots", config)
        val secondCreated = initializer.initializePlotsSync("plots", config)

        assertEquals(4, firstCreated)
        assertEquals(0, secondCreated)
        assertEquals(4, regionStore.listAllRegions().size)
    }
}
