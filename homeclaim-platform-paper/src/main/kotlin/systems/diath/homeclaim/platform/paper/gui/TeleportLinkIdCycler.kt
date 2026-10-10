package systems.diath.homeclaim.platform.paper.gui

import java.util.UUID

/** Cycles through link groups already used in a region, then offers a fresh group ID. */
internal object TeleportLinkIdCycler {
    fun next(current: UUID?, existingIds: Iterable<UUID>, createId: () -> UUID = UUID::randomUUID): UUID {
        val options = existingIds.distinct()
        if (current == null) return options.firstOrNull() ?: createId()

        val index = options.indexOf(current)
        return when {
            index == -1 -> options.firstOrNull() ?: createId()
            index < options.lastIndex -> options[index + 1]
            else -> createId()
        }
    }
}
