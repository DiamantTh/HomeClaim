package systems.diath.homeclaim.platform.paper.util

/**
 * Input validation and sanitization utilities.
 * 
 * Prevents:
 * - Command injection
 * - Path traversal
 * - XSS in names/messages
 * - Invalid characters in identifiers
 */
object InputValidator {
    
    // Allowed characters for player-provided names (plots, zones, etc.)
    private val NAME_PATTERN = Regex("^[a-zA-Z0-9_\\-äöüÄÖÜß ]{1,32}$")
    
    // Allowed characters for identifiers (no spaces)
    private val IDENTIFIER_PATTERN = Regex("^[a-zA-Z0-9_\\-]{1,64}$")
    
    // Dangerous patterns that could indicate injection attempts
    private val INJECTION_PATTERNS = listOf(
        Regex("\\.\\.[\\\\/]"),           // Path traversal
        Regex("[;&|`\$]"),                 // Shell injection
        Regex("<[^>]*script", RegexOption.IGNORE_CASE),  // XSS
        Regex("\\x00"),                    // Null byte
        Regex("[\r\n]"),                   // CRLF injection
    )
    
    /**
     * Validation result with optional error message.
     */
    data class ValidationResult(
        val valid: Boolean,
        val sanitized: String? = null,
        val errorKey: String? = null,
        val errorDetail: String? = null
    ) {
        companion object {
            fun ok(sanitized: String) = ValidationResult(true, sanitized)
            fun error(key: String, detail: String? = null) = ValidationResult(false, errorKey = key, errorDetail = detail)
        }
    }
    
    /**
     * Validate a user-provided name (plot name, zone name, etc.).
     * 
     * @param input The raw user input
     * @param maxLength Maximum allowed length (default 32)
     * @return ValidationResult with sanitized value or error
     */
    fun validateName(input: String?, maxLength: Int = 32): ValidationResult {
        if (input.isNullOrBlank()) {
            return ValidationResult.error("validation.name.empty")
        }
        
        val trimmed = input.trim()
        
        if (trimmed.length > maxLength) {
            return ValidationResult.error("validation.name.too_long", "max $maxLength")
        }
        
        if (containsInjection(trimmed)) {
            return ValidationResult.error("validation.name.invalid_chars")
        }
        
        if (!NAME_PATTERN.matches(trimmed)) {
            return ValidationResult.error("validation.name.invalid_format")
        }
        
        return ValidationResult.ok(sanitizeForDisplay(trimmed))
    }
    
    /**
     * Validate an identifier (region ID, component ID, etc.).
     */
    fun validateIdentifier(input: String?): ValidationResult {
        if (input.isNullOrBlank()) {
            return ValidationResult.error("validation.id.empty")
        }
        
        val trimmed = input.trim().lowercase()
        
        if (trimmed.length > 64) {
            return ValidationResult.error("validation.id.too_long")
        }
        
        if (!IDENTIFIER_PATTERN.matches(trimmed)) {
            return ValidationResult.error("validation.id.invalid_format")
        }
        
        return ValidationResult.ok(trimmed)
    }
    
    /**
     * Validate a player name.
     */
    fun validatePlayerName(input: String?): ValidationResult {
        if (input.isNullOrBlank()) {
            return ValidationResult.error("validation.player.empty")
        }
        
        val trimmed = input.trim()
        
        // Minecraft usernames: 3-16 chars, alphanumeric + underscore
        if (!Regex("^[a-zA-Z0-9_]{3,16}$").matches(trimmed)) {
            return ValidationResult.error("validation.player.invalid_format")
        }
        
        return ValidationResult.ok(trimmed)
    }
    
    /**
     * Validate a numeric input.
     */
    fun validateNumber(input: String?, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE): ValidationResult {
        if (input.isNullOrBlank()) {
            return ValidationResult.error("validation.number.empty")
        }
        
        val number = input.trim().toIntOrNull()
            ?: return ValidationResult.error("validation.number.invalid")
        
        if (number < min || number > max) {
            return ValidationResult.error("validation.number.out_of_range", "$min-$max")
        }
        
        return ValidationResult.ok(number.toString())
    }
    
    /**
     * Validate a double/decimal input.
     */
    fun validateDecimal(input: String?, min: Double = Double.MIN_VALUE, max: Double = Double.MAX_VALUE): ValidationResult {
        if (input.isNullOrBlank()) {
            return ValidationResult.error("validation.decimal.empty")
        }
        
        val number = input.trim().toDoubleOrNull()
            ?: return ValidationResult.error("validation.decimal.invalid")
        
        if (number < min || number > max) {
            return ValidationResult.error("validation.decimal.out_of_range", "$min-$max")
        }
        
        if (number.isNaN() || number.isInfinite()) {
            return ValidationResult.error("validation.decimal.invalid")
        }
        
        return ValidationResult.ok(number.toString())
    }
    
    /**
     * Check if input contains potential injection patterns.
     */
    fun containsInjection(input: String): Boolean {
        return INJECTION_PATTERNS.any { it.containsMatchIn(input) }
    }
    
    /**
     * Sanitize a string for safe display (removes control characters).
     */
    fun sanitizeForDisplay(input: String): String {
        return input
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "") // Control chars
            .replace("§", "") // Minecraft color codes (if not allowed)
            .trim()
    }
    
    /**
     * Sanitize for logging (escape special characters).
     */
    fun sanitizeForLog(input: String): String {
        return input
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .take(200) // Limit length for logs
    }
    
    /**
     * Validate coordinates (x, y, z).
     */
    fun validateCoordinate(input: String?, axis: String, worldMin: Int = -30_000_000, worldMax: Int = 30_000_000): ValidationResult {
        if (input.isNullOrBlank()) {
            return ValidationResult.error("validation.coord.$axis.empty")
        }
        
        val coord = input.trim().toIntOrNull()
            ?: return ValidationResult.error("validation.coord.$axis.invalid")
        
        if (coord < worldMin || coord > worldMax) {
            return ValidationResult.error("validation.coord.$axis.out_of_bounds")
        }
        
        return ValidationResult.ok(coord.toString())
    }
}
