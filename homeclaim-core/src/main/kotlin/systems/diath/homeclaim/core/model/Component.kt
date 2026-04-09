package systems.diath.homeclaim.core.model

import java.time.Instant

enum class ComponentType {
    ELEVATOR_PAD,
    TELEPORT_PAD
}

enum class ComponentState {
    ENABLED,
    DISABLED
}

enum class AccessPolicy {
    PUBLIC,
    MEMBERS,
    OWNER
}

data class ComponentPolicy(
    val access: AccessPolicy = AccessPolicy.MEMBERS,
    val cooldownMs: Long = 0
)

data class Component(
    val id: ComponentId,
    val regionId: RegionId,
    val type: ComponentType,
    val position: Position,
    val state: ComponentState = ComponentState.ENABLED,
    val policy: ComponentPolicy = ComponentPolicy(),
    val config: ComponentConfig,
    val createdBy: PlayerId,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
