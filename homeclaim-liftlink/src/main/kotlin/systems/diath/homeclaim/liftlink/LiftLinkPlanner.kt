package systems.diath.homeclaim.liftlink

import systems.diath.homeclaim.core.model.Component
import systems.diath.homeclaim.core.model.ComponentState
import systems.diath.homeclaim.core.model.ComponentType
import systems.diath.homeclaim.core.model.ElevatorConfig
import systems.diath.homeclaim.core.model.ElevatorMode
import systems.diath.homeclaim.core.model.ElevatorSearchRule
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.service.ComponentService
import kotlin.math.abs

/**
 * Minimal reference logic for elevator pad discovery.
 * This keeps the search predictable and range-limited.
 */
class LiftLinkPlanner(
    private val componentService: ComponentService
) {
    fun findNearbyPads(regionId: RegionId, origin: Position, config: ElevatorConfig): List<Component> {
        val candidates = componentService.listComponents(regionId)
            .filter { it.type == ComponentType.ELEVATOR_PAD && it.state == ComponentState.ENABLED }
            .filter { withinRange(origin, it.position, config.rangeBlocks, config.mode) }

        return when (config.searchRule) {
            ElevatorSearchRule.NEAREST_PAD -> candidates.sortedBy { distance(origin, it.position) }
            ElevatorSearchRule.NAMED_FLOOR -> {
                if (config.floorName.isNullOrBlank()) {
                    // Fallback to nearest if no floor name specified
                    candidates.sortedBy { distance(origin, it.position) }
                } else {
                    // Find pads matching the floor name in metadata
                    val matching = candidates.filter { comp ->
                        val elevConfig = comp.config as? ElevatorConfig
                        elevConfig?.floorName == config.floorName
                    }
                    matching.sortedBy { distance(origin, it.position) }
                }
            }
        }
    }

    private fun withinRange(origin: Position, target: Position, range: Int, mode: ElevatorMode): Boolean {
        return when (mode) {
            ElevatorMode.VERTICAL -> origin.x == target.x && origin.z == target.z && abs(origin.y - target.y) <= range
            ElevatorMode.HORIZONTAL -> origin.y == target.y && maxOf(abs(origin.x - target.x), abs(origin.z - target.z)) <= range
            ElevatorMode.BOTH -> distance(origin, target) <= range
        }
    }

    private fun distance(origin: Position, target: Position): Int {
        return maxOf(abs(origin.x - target.x), abs(origin.y - target.y), abs(origin.z - target.z))
    }
}
