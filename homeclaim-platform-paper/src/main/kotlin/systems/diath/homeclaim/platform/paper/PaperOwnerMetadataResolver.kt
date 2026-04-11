package systems.diath.homeclaim.platform.paper

import org.bukkit.Bukkit
import systems.diath.homeclaim.api.OwnerMetadata
import systems.diath.homeclaim.api.OwnerMetadataResolver
import systems.diath.homeclaim.platform.paper.util.PlayerPlatformClassifier
import java.util.UUID

class PaperOwnerMetadataResolver : OwnerMetadataResolver {
    override fun resolve(owner: UUID): OwnerMetadata {
        val offlinePlayer = Bukkit.getOfflinePlayer(owner)
        val platform = PlayerPlatformClassifier.classify(owner)
        return OwnerMetadata(
            uuid = owner.toString(),
            name = offlinePlayer.name,
            isJava = platform.isJava,
            isBedrock = platform.isBedrock
        )
    }
}
