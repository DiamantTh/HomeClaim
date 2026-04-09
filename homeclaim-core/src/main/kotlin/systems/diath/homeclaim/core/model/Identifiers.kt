package systems.diath.homeclaim.core.model

import java.util.UUID

typealias PlayerId = UUID
typealias WorldId = String

@JvmInline
value class RegionId(val value: UUID)

@JvmInline
value class ComponentId(val value: UUID)

@JvmInline
value class ZoneId(val value: UUID)

@JvmInline
value class MergeGroupId(val value: UUID)
