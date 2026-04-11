package systems.diath.homeclaim.platform.paper

import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle
import systems.diath.homeclaim.core.i18n.I18nProvider
import systems.diath.homeclaim.platform.paper.PoEntry

class I18n(
    baseName: String = "messages",
    localeTag: String? = null,
    loader: ClassLoader = I18n::class.java.classLoader
) : I18nProvider {
    private val locale: Locale = localeTag?.let { Locale.forLanguageTag(it) }?.takeIf { it.language.isNotBlank() } ?: Locale.ENGLISH
    private val poCatalog: PoCatalog = loadPo(baseName, locale, loader)
    private val bundle: ResourceBundle = runCatching { ResourceBundle.getBundle(baseName, locale, loader) }
        .getOrElse { ResourceBundle.getBundle(baseName, Locale.ENGLISH, loader) }

    override fun msg(key: String, vararg params: Any?): String = t(key, *params)

    fun t(msgid: String, vararg args: Any?): String {
        val pattern = poCatalog.get(msgid) ?: bundleOrDefault(msgid)
        return format(pattern, args)
    }

    fun tn(msgid: String, msgidPlural: String, n: Long, vararg args: Any?): String {
        val pattern = poCatalog.getPlural(msgid, n) ?: if (n == 1L) msgid else msgidPlural
        return format(pattern, args)
    }

    private fun bundleOrDefault(key: String): String =
        runCatching { bundle.getString(key) }.getOrNull() ?: key

    private fun format(pattern: String, args: Array<out Any?>): String =
        try { MessageFormat(pattern, locale).format(args) } catch (_: Exception) { "$pattern ${args.joinToString()}" }

    private class PoCatalog(
        private val entries: Map<String, PoEntry>,
        private val pluralRule: (Long) -> Int
    ) {
        fun get(msgid: String): String? =
            entries[msgid]?.translations?.firstOrNull()

        fun getPlural(msgid: String, n: Long): String? {
            val entry = entries[msgid] ?: return null
            if (entry.translations.isEmpty()) return null
            val idx = pluralRule(n).coerceIn(0, entry.translations.size - 1)
            return entry.translations.getOrNull(idx) ?: entry.translations.lastOrNull()
        }
    }

    private fun loadPo(baseName: String, locale: Locale, loader: ClassLoader): PoCatalog {
        val candidates = listOf(
            "${baseName}_${locale.toLanguageTag()}.po",
            "${baseName}_${locale.language}.po",
            "$baseName.po"
        )
        candidates.forEach { name ->
            val stream = loader.getResourceAsStream(name) ?: return@forEach
            val text = stream.bufferedReader().readLines()
            val parser = PoParser(text)
            val entries = parser.parseEntries()
            val rule = parser.pluralRule() ?: defaultPlural(locale)
            if (entries.isNotEmpty()) {
                return PoCatalog(entries, rule)
            }
        }
        return PoCatalog(emptyMap(), defaultPlural(locale))
    }

    private fun defaultPlural(locale: Locale): (Long) -> Int = { n ->
        when (locale.language) {
            "fr" -> if (n > 1) 1 else 0
            "ru", "uk", "pl" -> {
                when {
                    n % 10 == 1L && n % 100 != 11L -> 0
                    n % 10 in 2..4 && (n % 100 < 10 || n % 100 >= 20) -> 1
                    else -> 2
                }
            }
            else -> if (n == 1L) 0 else 1
        }
    }
}

private class PoParser(private val lines: List<String>) {
    private var pluralExpr: String? = null

    fun pluralRule(): ((Long) -> Int)? {
        val expr = pluralExpr ?: return null
        return when {
            expr.contains("n != 1") -> { n: Long -> if (n == 1L) 0 else 1 }
            expr.contains("n > 1") -> { n: Long -> if (n > 1) 1 else 0 }
            expr.contains("n%10==1") && expr.contains("n%100!=11") -> { n: Long ->
                when {
                    n % 10 == 1L && n % 100 != 11L -> 0
                    n % 10 in 2..4 && (n % 100 < 10 || n % 100 >= 20) -> 1
                    else -> 2
                }
            }
            else -> null
        }
    }

    fun parseEntries(): Map<String, PoEntry> {
        val entries = mutableMapOf<String, PoEntry>()
        var msgid: String? = null
        var msgidPlural: String? = null
        val msgstrs = mutableMapOf<Int, String>()
        val header = StringBuilder()
        fun flush() {
            if (msgid == null && header.isNotEmpty()) parseHeader(header.toString())
            val id = msgid ?: return
            val translations = msgstrs.toSortedMap().values.toList()
            entries[id] = PoEntry(id, msgidPlural, translations)
            msgid = null; msgidPlural = null; msgstrs.clear(); header.clear()
        }
        var current: String? = null
        lines.forEach { raw ->
            val line = raw.trimEnd()
            if (line.isEmpty()) { flush(); current = null; return@forEach }
            when {
                line.startsWith("msgid_plural") -> {
                    msgidPlural = extractQuoted(line)
                    current = "msgid_plural"
                }
                line.startsWith("msgid") -> {
                    msgid = extractQuoted(line)
                    current = "msgid"
                }
                line.startsWith("msgstr") -> {
                    val idx = line.substringAfter("msgstr", "").trim().removePrefix("[").takeWhile { it.isDigit() }.toIntOrNull() ?: 0
                    msgstrs[idx] = extractQuoted(line) ?: ""
                    current = "msgstr$idx"
                }
                line.startsWith("\"") -> {
                    val text = extractQuoted(line) ?: ""
                    when {
                        current?.startsWith("msgstr") == true -> {
                            val idx = current?.removePrefix("msgstr")?.toIntOrNull() ?: 0
                            msgstrs[idx] = (msgstrs[idx] ?: "") + text
                        }
                        current == "msgid" -> msgid = (msgid ?: "") + text
                        current == "msgid_plural" -> msgidPlural = (msgidPlural ?: "") + text
                        msgid == null -> header.appendLine(text)
                    }
                }
            }
        }
        flush()
        return entries
    }

    private fun parseHeader(h: String) {
        h.lineSequence().forEach { line ->
            val norm = line.trim()
            if (norm.startsWith("Plural-Forms")) {
                pluralExpr = norm.substringAfter("plural=", "").substringBefore(";").trim()
            }
        }
    }

    private fun extractQuoted(line: String): String? {
        val start = line.indexOf('"')
        val end = line.lastIndexOf('"')
        return if (start >= 0 && end > start) line.substring(start + 1, end) else null
    }
}
