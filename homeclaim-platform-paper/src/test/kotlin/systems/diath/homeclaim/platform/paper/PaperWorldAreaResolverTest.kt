package systems.diath.homeclaim.platform.paper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import systems.diath.homeclaim.core.model.AreaScope
import systems.diath.homeclaim.core.service.InMemoryWorldAreaService
import java.util.UUID

class PaperWorldAreaResolverTest {
    @Test
    fun bindsAnUnboundLegacyNameOnceAndUsesItsStableWorldId() {
        val service = InMemoryWorldAreaService()
        val legacyArea = service.ensureLegacyArea("plots", AreaScope.WholeWorld)
        val platformWorldId = UUID.randomUUID()

        val resolved = PaperWorldAreaResolver.resolveArea(
            service, platformWorldId, "plots", 10, 64, 10
        )
        val world = service.getWorldByPlatformId(platformWorldId)

        assertEquals(legacyArea.id, resolved?.id)
        assertEquals(legacyArea.worldId, world?.id)
        assertEquals(platformWorldId, world?.platformWorldId)
        assertSame(world, PaperWorldAreaResolver.resolveWorld(service, platformWorldId, "renamed"))
        assertEquals(1, service.listWorlds().size)
    }

    @Test
    fun aPlatformUuidBindingWinsOverTheCurrentWorldName() {
        val service = InMemoryWorldAreaService()
        val bound = service.createWorld("old-name", UUID.randomUUID())
        service.createWorld("current-name")

        val resolved = PaperWorldAreaResolver.resolveWorld(
            service,
            requireNotNull(bound.platformWorldId),
            "current-name"
        )

        assertEquals(bound.id, resolved?.id)
        assertEquals(2, service.listWorlds().size)
    }

    @Test
    fun aNameMatchCannotRebindAWorldAlreadyBoundElsewhere() {
        val service = InMemoryWorldAreaService()
        service.createWorld("plots", UUID.randomUUID())

        assertNull(PaperWorldAreaResolver.resolveWorld(service, UUID.randomUUID(), "plots"))
    }
}
