package systems.diath.homeclaim.platform.paper

import systems.diath.homeclaim.core.model.Area
import systems.diath.homeclaim.core.model.HomeClaimWorld
import systems.diath.homeclaim.core.service.WorldAreaService
import java.util.UUID

/** Paper boundary lookup that prefers the stable platform UUID and binds only legacy rows. */
internal object PaperWorldAreaResolver {
    fun resolveWorld(
        worldAreaService: WorldAreaService,
        platformWorldId: UUID,
        currentName: String
    ): HomeClaimWorld? {
        worldAreaService.getWorldByPlatformId(platformWorldId)?.let { return it }

        val unboundLegacyWorld = worldAreaService.getWorldByName(currentName)
            ?.takeIf { it.platformWorldId == null }
            ?: return null

        return worldAreaService.bindPlatformWorld(
            id = unboundLegacyWorld.id,
            platformWorldId = platformWorldId,
            currentName = currentName
        )
    }

    fun resolveArea(
        worldAreaService: WorldAreaService,
        platformWorldId: UUID,
        currentName: String,
        x: Int,
        y: Int,
        z: Int
    ): Area? = resolveWorld(worldAreaService, platformWorldId, currentName)
        ?.let { worldAreaService.resolveArea(it.id, x, y, z) }
}
