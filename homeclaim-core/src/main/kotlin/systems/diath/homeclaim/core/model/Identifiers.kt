package systems.diath.homeclaim.core.model

import java.util.UUID

typealias PlayerId = UUID

@JvmInline
value class WorldId(val value: UUID) {
    companion object {
        /** Deterministic identity used only while upgrading legacy name-keyed records. */
        fun fromLegacyName(name: String): WorldId = WorldId(
            UUID.nameUUIDFromBytes("homeclaim:legacy-world:$name".toByteArray(Charsets.UTF_8))
        )

        fun newId(): WorldId = WorldId(UUID.randomUUID())
    }
}

@JvmInline
value class AreaId(val value: UUID) {
    companion object {
        fun newId(): AreaId = AreaId(UUID.randomUUID())

        /** Stable default area id for a legacy world until an operator creates named areas. */
        fun legacyDefault(worldId: WorldId): AreaId = AreaId(
            UUID.nameUUIDFromBytes("homeclaim:legacy-default-area:${worldId.value}".toByteArray(Charsets.UTF_8))
        )
    }
}

data class HomeClaimWorld(
    val id: WorldId,
    val platformWorldId: UUID? = null,
    val name: String,
    val metadata: Map<String, String> = emptyMap()
) {
    fun renamed(newName: String): HomeClaimWorld = copy(name = newName)

    fun bindPlatformWorld(platformId: UUID): HomeClaimWorld {
        require(platformWorldId == null || platformWorldId == platformId) {
            "HomeClaim world ${id.value} is already bound to another platform world"
        }
        return if (platformWorldId == platformId) this else copy(platformWorldId = platformId)
    }
}

@JvmInline
value class RegionId(val value: UUID)

@JvmInline
value class ComponentId(val value: UUID)

@JvmInline
value class ZoneId(val value: UUID)

@JvmInline
value class MergeGroupId(val value: UUID)
