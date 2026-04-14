package systems.diath.homeclaim.platform.paper.command

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.contains
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionRoles
import systems.diath.homeclaim.core.model.RegionShape
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.core.store.InMemoryRegionStore
import systems.diath.homeclaim.platform.paper.gui.GuiManager
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationService
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotResetService
import systems.diath.homeclaim.platform.paper.util.CommandRateLimiter
import java.util.UUID

class PlotCommandTest {

    @BeforeEach
    fun resetLimiter() {
        CommandRateLimiter.resetAll()
    }

    @Test
    fun `claim still works when no economy service is wired`() {
        val regionService = InMemoryRegionStore()
        val guiManager = mock(GuiManager::class.java)
        val player = mock(Player::class.java)
        val world = mock(World::class.java)
        val command = mock(Command::class.java)

        val playerId = UUID.randomUUID()
        val regionId = RegionId(UUID.randomUUID())
        val location = Location(world, 10.0, 65.0, 10.0)
        val region = Region(
            id = regionId,
            world = "plots",
            shape = RegionShape.CUBOID,
            bounds = Bounds(0, 15, 0, 255, 0, 15),
            owner = UUID.fromString("00000000-0000-0000-0000-000000000000"),
            roles = RegionRoles(),
            flags = emptyMap(),
            limits = emptyMap(),
            metadata = emptyMap(),
            mergeGroupId = null,
            price = 0.0
        )
        regionService.createRegion(region, region.bounds)

        doReturn(false).`when`(player).isOp
        doReturn(playerId).`when`(player).uniqueId
        doReturn(location).`when`(player).location
        doReturn(true).`when`(player).hasPermission(anyString())
        doReturn("plots").`when`(world).name

        val plotCommand = PlotCommand(regionService, guiManager, econService = null)

        plotCommand.onCommand(player, command, "plot", arrayOf("claim"))

        assertEquals(playerId, regionService.getRegionById(regionId)?.owner)
        verify(player, never()).sendMessage(contains("economy"))
    }

    @Test
    fun `jobs status shows queued mutation and reset entries`() {
        val regionService = mock(RegionService::class.java)
        val guiManager = mock(GuiManager::class.java)
        val plotResetService = mock(PlotResetService::class.java)
        val plotMutationService = mock(PlotMutationService::class.java)
        val player = mock(Player::class.java)
        val command = mock(Command::class.java)

        doReturn(false).`when`(player).isOp
        doReturn(UUID.randomUUID()).`when`(player).uniqueId
        doReturn(true).`when`(player).hasPermission(anyString())
        doReturn(listOf("mutation:plots:mut-1")).`when`(plotMutationService).activeJobDiagnostics("plots")
        doReturn(listOf("reset:plots:reset-1")).`when`(plotResetService).activeJobDiagnostics("plots")

        val plotCommand = PlotCommand(
            regionService = regionService,
            guiManager = guiManager,
            econService = null,
            plotResetService = plotResetService,
            plotMutationService = plotMutationService
        )

        plotCommand.onCommand(player, command, "plot", arrayOf("jobs", "status", "plots"))

        verify(player, atLeastOnce()).sendMessage(contains("mut-1"))
        verify(player, atLeastOnce()).sendMessage(contains("reset-1"))
    }
}
