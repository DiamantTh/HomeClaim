package systems.diath.homeclaim.platform.paper.migration

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlotSquaredImportAdapterTest {

    @Test
    fun extractsPlotsFromPlotApiLikeObject() {
        val owner = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val plot = FakePlot(owner = owner)

        val results = PlotSquaredImportAdapter.extractFromApi(FakePlotApi(setOf(plot)))

        assertEquals(1, results.size)
        val extracted = results.single()
        assertEquals(owner, extracted.owner)
        assertEquals("world_plots", extracted.world)
        assertEquals(0, extracted.minX)
        assertEquals(0, extracted.minZ)
        assertEquals(31, extracted.maxX)
        assertEquals(31, extracted.maxZ)
        assertTrue(extracted.members.contains(UUID.fromString("22222222-2222-2222-2222-222222222222")))
        assertTrue(extracted.flags.containsKey("pvp"))
    }

    @Test
    fun supportsLargestRegionFallbackWhenGetRegionIsMissing() {
        val owner = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val plot = FakeLargestRegionOnlyPlot(owner)

        val results = PlotSquaredImportAdapter.extractFromApi(FakePlotApi(setOf(plot)))

        assertEquals(1, results.size)
        assertEquals(owner, results.single().owner)
        assertEquals("legacy_plots", results.single().world)
    }

    private class FakePlotApi(private val plots: Set<Any>) {
        fun getAllPlots(): Set<Any> = plots
    }

    private open class FakePlot(private val owner: UUID) {
        fun getOwner(): UUID = owner
        fun getMembers(): Set<UUID> = setOf(UUID.fromString("22222222-2222-2222-2222-222222222222"))
        fun getTrusted(): Set<UUID> = setOf(UUID.fromString("44444444-4444-4444-4444-444444444444"))
        fun getDenied(): Set<UUID> = emptySet()
        fun getWorldName(): String = "world_plots"
        fun getRegion(): FakeRegion = FakeRegion(0, 0, 31, 31)
        fun getFlagContainer(): FakeFlagContainer = FakeFlagContainer(mapOf("pvp" to false, "break" to true))
        fun getId(): String = "1;1"
    }

    private class FakeLargestRegionOnlyPlot(private val owner: UUID) {
        fun getOwner(): UUID = owner
        fun getMembers(): Set<UUID> = emptySet()
        fun getTrusted(): Set<UUID> = emptySet()
        fun getDenied(): Set<UUID> = emptySet()
        fun getArea(): FakeArea = FakeArea("legacy_plots")
        fun getLargestRegion(): FakeRegion = FakeRegion(32, 32, 63, 63)
        fun getFlagContainer(): FakeFlagContainer = FakeFlagContainer(emptyMap())
        fun getId(): String = "2;2"
    }

    private class FakeArea(private val worldName: String) {
        fun getWorldName(): String = worldName
    }

    private class FakeFlagContainer(private val flagMap: Map<String, Any>) {
        fun getFlagMap(): Map<String, Any> = flagMap
    }

    private class FakeRegion(minX: Int, minZ: Int, maxX: Int, maxZ: Int) {
        private val minimumPoint = FakePoint(minX, minZ)
        private val maximumPoint = FakePoint(maxX, maxZ)

        fun getMinimumPoint(): FakePoint = minimumPoint
        fun getMaximumPoint(): FakePoint = maximumPoint
    }

    private class FakePoint(private val x: Int, private val z: Int) {
        fun getX(): Int = x
        fun getZ(): Int = z
    }
}
