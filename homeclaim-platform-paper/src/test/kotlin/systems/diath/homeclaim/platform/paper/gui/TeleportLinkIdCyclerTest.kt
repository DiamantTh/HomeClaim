package systems.diath.homeclaim.platform.paper.gui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class TeleportLinkIdCyclerTest {
    @Test
    fun `new pad can join a link group then choose a separate new group`() {
        val existing = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val fresh = UUID.fromString("00000000-0000-0000-0000-000000000002")

        assertEquals(existing, TeleportLinkIdCycler.next(null, listOf(existing)) { fresh })
        assertEquals(fresh, TeleportLinkIdCycler.next(existing, listOf(existing)) { fresh })
    }

    @Test
    fun `cycling a fresh ID returns to existing group options`() {
        val existing = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val fresh = UUID.fromString("00000000-0000-0000-0000-000000000002")

        assertEquals(existing, TeleportLinkIdCycler.next(fresh, listOf(existing)) { UUID.randomUUID() })
    }
}
