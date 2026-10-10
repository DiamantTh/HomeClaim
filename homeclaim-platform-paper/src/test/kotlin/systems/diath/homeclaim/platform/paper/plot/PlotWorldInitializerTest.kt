package systems.diath.homeclaim.platform.paper.plot

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import systems.diath.homeclaim.core.model.Area
import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.AreaScope
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.store.InMemoryRegionStore
import systems.diath.homeclaim.core.service.InMemoryWorldAreaService

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

    @Test
    fun `initializer persists plots under the supplied area and finite area leaves outside unmanaged`() {
        val regionStore = InMemoryRegionStore()
        val worldAreaService = InMemoryWorldAreaService()
        val world = worldAreaService.createWorld("plots")
        val area = worldAreaService.createArea(
            Area(
                AreaId.newId(), world.id, "plots",
                AreaScope.Finite(Bounds(-20, 20, -64, 319, -20, 20))
            )
        )
        val initializer = PlotWorldInitializer(regionStore, mock(Plugin::class.java), worldAreaService)
        val config = PlotWorldConfig(
            worldName = "plots",
            plotSize = 16,
            roadWidth = 4,
            plotsPerSide = 2,
            minGenHeight = 0,
            plotHeight = 64
        )

        assertEquals(4, initializer.initializePlotsSync(area.id, "plots", config))
        assertEquals(4, regionStore.listAllRegions().count { it.areaId == area.id })
        assertNull(worldAreaService.resolveArea(world.id, 1000, 64, 1000))
    }
}
