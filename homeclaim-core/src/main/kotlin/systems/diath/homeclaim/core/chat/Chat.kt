package systems.diath.homeclaim.core.chat

/**
 * Platform-agnostic chat formatting.
 * Converts to platform-specific format at render time.
 */
object ChatFormat {
    // Color codes
    const val BLACK = "&0"
    const val DARK_BLUE = "&1"
    const val DARK_GREEN = "&2"
    const val DARK_AQUA = "&3"
    const val DARK_RED = "&4"
    const val DARK_PURPLE = "&5"
    const val GOLD = "&6"
    const val GRAY = "&7"
    const val DARK_GRAY = "&8"
    const val BLUE = "&9"
    const val GREEN = "&a"
    const val AQUA = "&b"
    const val RED = "&c"
    const val LIGHT_PURPLE = "&d"
    const val YELLOW = "&e"
    const val WHITE = "&f"
    
    // Styles
    const val BOLD = "&l"
    const val STRIKETHROUGH = "&m"
    const val UNDERLINE = "&n"
    const val ITALIC = "&o"
    const val RESET = "&r"
    
    /**
     * Convert to Bukkit format (§).
     */
    fun toBukkit(text: String): String {
        return text.replace(Regex("&([0-9a-fk-or])"), "§$1")
    }
    
    /**
     * Convert to Markdown format.
     */
    fun toMarkdown(text: String): String {
        return text
            .replace("&l", "**")  // Bold
            .replace("&o", "*")   // Italic
            .replace("&n", "__")  // Underline
            .replace(Regex("&[0-9a-f]"), "")  // Remove colors
            .replace(Regex("&[k-r]"), "")  // Remove styles
    }
    
    /**
     * Strip all formatting.
     */
    fun strip(text: String): String {
        return text.replace(Regex("&[0-9a-fk-or]"), "")
    }
}

/**
 * Text component builder for complex messages.
 */
class TextComponent(val text: String = "") {
    private val parts = mutableListOf<TextPart>()
    
    private data class TextPart(
        val text: String,
        val color: String? = null,
        val styles: Set<String> = emptySet(),
        val hoverText: String? = null,
        val clickAction: String? = null,
        val clickValue: String? = null
    )
    
    fun append(text: String, color: String? = null): TextComponent {
        parts.add(TextPart(text, color))
        return this
    }
    
    fun append(text: String, color: String, vararg styles: String): TextComponent {
        parts.add(TextPart(text, color, styles.toSet()))
        return this
    }
    
    fun appendClickable(text: String, action: String, value: String): TextComponent {
        parts.add(TextPart(text, clickAction = action, clickValue = value))
        return this
    }
    
    fun appendHoverable(text: String, hoverText: String): TextComponent {
        parts.add(TextPart(text, hoverText = hoverText))
        return this
    }
    
    fun toPlainText(): String {
        return parts.joinToString("") { it.text }
    }
    
    fun toBukkitFormat(): String {
        return parts.joinToString("") { part ->
            val format = StringBuilder()
            if (part.color != null) format.append(part.color)
            part.styles.forEach { format.append(it) }
            format.append(part.text)
            format.toString()
        }.let { ChatFormat.toBukkit(it) }
    }
}

/**
 * Message builder with common patterns.
 */
object Messages {
    fun success(text: String): String = ChatFormat.GREEN + "✓ " + text
    fun error(text: String): String = ChatFormat.RED + "✗ " + text
    fun warning(text: String): String = ChatFormat.YELLOW + "⚠ " + text
    fun info(text: String): String = ChatFormat.BLUE + "ℹ " + text
    fun status(text: String): String = ChatFormat.DARK_AQUA + "➜ " + text
    
    fun header(text: String): String {
        return "\n" + ChatFormat.GOLD + ChatFormat.BOLD + "═══ " + text + " ═══" + ChatFormat.RESET
    }
    
    fun footer(text: String): String {
        return ChatFormat.GOLD + "━ " + text + " ━" + ChatFormat.RESET
    }
}
