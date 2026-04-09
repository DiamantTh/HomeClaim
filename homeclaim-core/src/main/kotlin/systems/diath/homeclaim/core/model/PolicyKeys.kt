package systems.diath.homeclaim.core.model

/**
 * Validated key for region flags.
 * Keys must be alphanumeric with underscores/hyphens, max 64 characters.
 */
@JvmInline
value class FlagKey private constructor(val value: String) {
    companion object {
        private val VALID_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9_\\-]{0,63}$")
        
        /**
         * Creates a validated FlagKey.
         * @throws IllegalArgumentException if the key is invalid
         */
        operator fun invoke(value: String): FlagKey {
            require(VALID_PATTERN.matches(value)) {
                "Invalid FlagKey '$value': must start with letter, contain only alphanumeric/underscore/hyphen, max 64 chars"
            }
            return FlagKey(value)
        }
        
        /**
         * Creates a FlagKey without validation (for trusted internal use only).
         * Use with caution - only for data loaded from database.
         */
        fun trusted(value: String): FlagKey = FlagKey(value)
        
        /**
         * Attempts to create a FlagKey, returning null if invalid.
         */
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
        
        /**
         * Creates a validated LimitKey.
         * @throws IllegalArgumentException if the key is invalid
         */
        operator fun invoke(value: String): LimitKey {
            require(VALID_PATTERN.matches(value)) {
                "Invalid LimitKey '$value': must start with letter, contain only alphanumeric/underscore/hyphen, max 64 chars"
            }
            return LimitKey(value)
        }
        
        /**
         * Creates a LimitKey without validation (for trusted internal use only).
         * Use with caution - only for data loaded from database.
         */
        fun trusted(value: String): LimitKey = LimitKey(value)
        
        /**
         * Attempts to create a LimitKey, returning null if invalid.
         */
        fun ofOrNull(value: String): LimitKey? = 
            if (VALID_PATTERN.matches(value)) LimitKey(value) else null
    }
}

sealed interface PolicyValue {
    data class Bool(val allowed: Boolean) : PolicyValue
    data class IntValue(val value: Int) : PolicyValue
    data class Text(val value: String) : PolicyValue {
        init {
            // Defense-in-depth: Limit text values to prevent excessively large payloads
            require(value.length <= 1024) { "PolicyValue.Text exceeds max length of 1024" }
        }
    }
}

typealias FlagValue = PolicyValue
typealias LimitValue = PolicyValue
