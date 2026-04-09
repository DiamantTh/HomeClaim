package systems.diath.homeclaim.core.model

import java.util.UUID

sealed interface ComponentConfig {
    val triggerBlocks: Set<String>?
}

enum class ElevatorMode {
    VERTICAL,
    HORIZONTAL,
    BOTH
}

enum class ElevatorSearchRule {
    NEAREST_PAD,
    NAMED_FLOOR
}

data class ElevatorConfig(
    val mode: ElevatorMode = ElevatorMode.VERTICAL,
    val rangeBlocks: Int = 12,
    val searchRule: ElevatorSearchRule = ElevatorSearchRule.NEAREST_PAD,
    val floorName: String? = null,
    override val triggerBlocks: Set<String>? = null
) : ComponentConfig

enum class TeleportLinkMode {
    PAIR,
    HUB
}

enum class TeleportScope {
    REGION_ONLY,
    MERGE_GROUP
}

data class TeleportConfig(
    val linkId: UUID,
    val linkMode: TeleportLinkMode = TeleportLinkMode.PAIR,
    val targets: List<ComponentId> = emptyList(),
    val withinScope: TeleportScope = TeleportScope.REGION_ONLY,
    val combatBlock: Boolean? = null,
    override val triggerBlocks: Set<String>? = null
) : ComponentConfig
