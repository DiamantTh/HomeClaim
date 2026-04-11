package systems.diath.homeclaim.platform.paper.util

import java.util.UUID

/**
 * Classifies players by platform using Floodgate when available.
 * Falls back to unknown/Java defaults if Floodgate is absent.
 */
object PlayerPlatformClassifier {
    data class PlatformInfo(
        val isJava: Boolean,
        val isBedrock: Boolean
    )

    fun classify(uuid: UUID): PlatformInfo {
        val isBedrock = isFloodgatePlayer(uuid)
        return PlatformInfo(
            isJava = !isBedrock,
            isBedrock = isBedrock
        )
    }

    private fun isFloodgatePlayer(uuid: UUID): Boolean {
        return runCatching {
            val apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi")
            val getInstanceMethod = apiClass.getMethod("getInstance")
            val api = getInstanceMethod.invoke(null) ?: return false
            val checkMethod = apiClass.getMethod("isFloodgatePlayer", UUID::class.java)
            (checkMethod.invoke(api, uuid) as? Boolean) == true
        }.getOrDefault(false)
    }
}
