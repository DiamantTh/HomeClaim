package systems.diath.homeclaim.core.i18n

/**
 * Platform-agnostic interface for internationalization.
 * Implementations should be provided by platform-specific modules (e.g., Paper).
 */
interface I18nProvider {
    /**
     * Get translated message by key.
     * @param key Message key (e.g., "command.no_permission")
     * @param vararg params Optional parameters for message formatting
     * @return Translated message or key if translation not found
     */
    fun msg(key: String, vararg params: Any?): String
}

/**
 * Default no-op implementation for testing/headless environments.
 */
class DefaultI18nProvider : I18nProvider {
    override fun msg(key: String, vararg params: Any?): String = key
}
