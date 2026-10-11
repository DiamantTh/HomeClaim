package systems.diath.homeclaim.core.model

import java.util.UUID

/** Compatibility encoding for an unclaimed region in the current UUID-backed owner schema. */
object RegionOwnership {
    val UNCLAIMED_OWNER_ID: PlayerId = UUID(0L, 0L)

    fun isUnclaimed(ownerId: PlayerId): Boolean = ownerId == UNCLAIMED_OWNER_ID
}
