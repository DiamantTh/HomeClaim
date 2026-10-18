package systems.diath.homeclaim.api.http

/** Server preference order for the two HTTP response classes HomeClaim serves. */
enum class ContentEncodingPolicy(val preference: List<String>) {
    STATIC(listOf("br", "zstd", "identity")),
    DYNAMIC(listOf("zstd", "br", "identity"))
}

/**
 * A small RFC-style Accept-Encoding parser shared by the REST and WebUX servers.
 * Missing headers intentionally select identity for HomeClaim's documented policy.
 */
class AcceptEncodingNegotiation private constructor(
    private val qualities: Map<String, Double>,
    private val wildcardQuality: Double?,
    private val headerPresent: Boolean
) {
    fun quality(encoding: String): Double = when (encoding.lowercase()) {
        "identity" -> qualities["identity"] ?: if (wildcardQuality == 0.0) 0.0 else 1.0
        else -> qualities[encoding.lowercase()] ?: wildcardQuality ?: 0.0
    }

    fun select(policy: ContentEncodingPolicy, available: Set<String> = setOf("zstd", "br", "identity")): String? {
        if (!headerPresent) return "identity"
        return policy.preference
            .filter { it in available && quality(it) > 0.0 }
            .maxWithOrNull(compareBy<String>({ quality(it) }, { -policy.preference.indexOf(it) }))
    }

    fun isAcceptable(policy: ContentEncodingPolicy, available: Set<String> = setOf("zstd", "br", "identity")): Boolean =
        select(policy, available) != null

    companion object {
        private val qValue = Regex("^(?:0(?:\\.\\d{0,3})?|1(?:\\.0{0,3})?)$")

        fun parse(headerValues: List<String>?): AcceptEncodingNegotiation {
            if (headerValues.isNullOrEmpty()) return AcceptEncodingNegotiation(emptyMap(), null, false)
            val values = mutableMapOf<String, Double>()
            var wildcard: Double? = null
            for (raw in headerValues.flatMap { it.split(',') }) {
                val parts = raw.split(';')
                val coding = parts.firstOrNull()?.trim()?.lowercase().orEmpty()
                if (coding.isEmpty()) continue
                var quality = 1.0
                var invalidQuality = false
                for (parameter in parts.drop(1)) {
                    val pair = parameter.trim().split('=', limit = 2)
                    if (pair.size == 2 && pair[0].trim().equals("q", ignoreCase = true)) {
                        val text = pair[1].trim()
                        if (!qValue.matches(text)) {
                            invalidQuality = true
                        } else {
                            quality = text.toDoubleOrNull() ?: 0.0
                        }
                    }
                }
                if (invalidQuality) quality = 0.0
                if (coding == "*") wildcard = maxOf(wildcard ?: 0.0, quality)
                else values[coding] = maxOf(values[coding] ?: 0.0, quality)
            }
            return AcceptEncodingNegotiation(values, wildcard, true)
        }
    }
}
