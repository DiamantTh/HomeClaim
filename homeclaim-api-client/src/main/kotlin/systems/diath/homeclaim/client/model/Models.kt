package systems.diath.homeclaim.client.model

import java.util.UUID

// ============================================================================
// ID Types (Value Classes)
// ============================================================================

@JvmInline
value class PlayerId(val value: UUID)

@JvmInline
value class RegionId(val value: UUID)

@JvmInline
value class WorldId(val value: String)

@JvmInline
value class MergeGroupId(val value: UUID)

@JvmInline
value class ComponentId(val value: UUID)

@JvmInline
value class ZoneId(val value: UUID)

// ============================================================================
// Flag/Limit Key Types
// ============================================================================

/**
 * Validated key for region flags.
 * Keys must be alphanumeric with underscores/hyphens, max 64 characters.
 */
@JvmInline
value class FlagKey private constructor(val value: String) {
    companion object {
        private val VALID_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9_\\-]{0,63}$")
        
        operator fun invoke(value: String): FlagKey {
            require(VALID_PATTERN.matches(value)) {
                "Invalid FlagKey '$value': must start with letter, contain only alphanumeric/underscore/hyphen, max 64 chars"
            }
            return FlagKey(value)
        }
        
        fun trusted(value: String): FlagKey = FlagKey(value)
        
        fun ofOrNull(value: String): FlagKey? = 
            if (VALID_PATTERN.matches(value)) FlagKey(value) else null
    }
}

/**
 * Validated key for region limits.
 * Keys must be alphanumeric with underscores/hyphens, max 64 characters.
 */
@JvmInline
value class LimitKey private constructor(val value: String) {
    companion object {
        private val VALID_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9_\\-]{0,63}$")
        
        operator fun invoke(value: String): LimitKey {
            require(VALID_PATTERN.matches(value)) {
                "Invalid LimitKey '$value': must start with letter, contain only alphanumeric/underscore/hyphen, max 64 chars"
            }
            return LimitKey(value)
        }
        
        fun trusted(value: String): LimitKey = LimitKey(value)
        
        fun ofOrNull(value: String): LimitKey? = 
            if (VALID_PATTERN.matches(value)) LimitKey(value) else null
    }
}

// ============================================================================
// Policy Value Types
// ============================================================================

sealed interface PolicyValue {
    data class Bool(val allowed: Boolean) : PolicyValue
    data class IntValue(val value: Int) : PolicyValue
    data class Text(val value: String) : PolicyValue {
        init {
            require(value.length <= 1024) { "PolicyValue.Text exceeds max length of 1024" }
        }
    }
}

typealias FlagValue = PolicyValue
typealias LimitValue = PolicyValue

// ============================================================================
// Role Types
// ============================================================================

enum class RegionRole {
    OWNER,      // Full control
    TRUSTED,    // Can build/manage flags
    MEMBER,     // Can use components
    VISITOR,    // Can interact (PvP, etc)
    BANNED      // Cannot enter
}

data class RegionRoles(
    val trusted: Set<PlayerId> = emptySet(),
    val members: Set<PlayerId> = emptySet(),
    val banned: Set<PlayerId> = emptySet()
)

// ============================================================================
// Shape Types
// ============================================================================

enum class RegionShape {
    CUBOID,      // Axis-aligned rectangular box
    PLOT_GRID,   // Grid-based plot system
    POLYGON      // Custom polygonal boundary
}

// ============================================================================
// Bounds
// ============================================================================

data class Bounds(
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int
)

// ============================================================================
// Region Data Model
// ============================================================================

data class Region(
    val id: RegionId,
    val world: WorldId,
    val shape: RegionShape,
    val bounds: Bounds,
    val owner: PlayerId,
    val roles: RegionRoles = RegionRoles(),
    val flags: Map<FlagKey, FlagValue> = emptyMap(),
    val limits: Map<LimitKey, LimitValue> = emptyMap(),
    val metadata: Map<String, String> = emptyMap(),
    val mergeGroupId: MergeGroupId? = null,
    val price: Double = 0.0  // Price for buying (0 = not for sale)
)
