package systems.diath.homeclaim.platform.paper.command

import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import java.util.UUID

internal sealed interface OwnedRegionSelection {
    data class Selected(val region: Region) : OwnedRegionSelection
    data class SelectionRequired(val regions: List<Region>) : OwnedRegionSelection
    data object NoRegions : OwnedRegionSelection
    data object InvalidSelector : OwnedRegionSelection
}

/** Deterministic owner-region selection shared by home, visit, and list output. */
internal object OwnedRegionSelector {
    fun order(regions: Collection<Region>): List<Region> =
        regions.sortedBy { it.id.value.toString() }

    fun select(regions: Collection<Region>, selector: String?): OwnedRegionSelection {
        val ordered = order(regions)
        if (ordered.isEmpty()) return OwnedRegionSelection.NoRegions
        if (selector.isNullOrBlank()) {
            return if (ordered.size == 1) {
                OwnedRegionSelection.Selected(ordered.single())
            } else {
                OwnedRegionSelection.SelectionRequired(ordered)
            }
        }

        selector.toIntOrNull()?.let { index ->
            return ordered.getOrNull(index - 1)
                ?.let(OwnedRegionSelection::Selected)
                ?: OwnedRegionSelection.InvalidSelector
        }

        val id = runCatching { RegionId(UUID.fromString(selector)) }.getOrNull()
            ?: return OwnedRegionSelection.InvalidSelector
        return ordered.firstOrNull { it.id == id }
            ?.let(OwnedRegionSelection::Selected)
            ?: OwnedRegionSelection.InvalidSelector
    }
}
