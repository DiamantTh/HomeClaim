package systems.diath.homeclaim.platform.paper

import kotlin.test.Test
import kotlin.test.assertEquals

class I18nTest {
    @Test
    fun loadsDefaultEnglishMessages() {
        val i18n = I18n(localeTag = "en")
        assertEquals("Config reloaded.", i18n.msg("storage.reload.ok"))
    }

    @Test
    fun fallsBackToKeyWhenMissing() {
        val i18n = I18n(localeTag = "en")
        assertEquals("unknown.key", i18n.msg("unknown.key"))
    }

    @Test
    fun pluralFallbackUsesProvidedStrings() {
        val i18n = I18n(localeTag = "en")
        assertEquals("item", i18n.tn("item", "items", 1))
        assertEquals("items", i18n.tn("item", "items", 2))
    }
}
