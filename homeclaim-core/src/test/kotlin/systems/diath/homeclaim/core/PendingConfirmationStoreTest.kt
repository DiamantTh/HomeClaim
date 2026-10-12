package systems.diath.homeclaim.core

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.ActionKey
import systems.diath.homeclaim.core.service.PendingConfirmationStore
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class PendingConfirmationStoreTest {
    @Test fun `confirmation is actor operation target and state bound`() {
        val store = PendingConfirmationStore(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))
        val actor = ActionActor.Player(UUID.randomUUID())
        store.create(actor, ActionKey("homeclaim:region_delete"), "region:1", "v1")
        assertNull(store.consume(actor, ActionKey("homeclaim:region_reset"), "region:1", "v1"))
        store.create(actor, ActionKey("homeclaim:region_delete"), "region:1", "v1")
        assertNull(store.consume(actor, ActionKey("homeclaim:region_delete"), "region:2", "v1"))
        store.create(actor, ActionKey("homeclaim:region_delete"), "region:1", "v1")
        assertNotNull(store.consume(actor, ActionKey("homeclaim:region_delete"), "region:1", "v1"))
    }

    @Test fun `expired confirmation is rejected`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val actor = ActionActor.Player(UUID.randomUUID())
        val store = PendingConfirmationStore(clock)
        store.create(actor, ActionKey("homeclaim:region_delete"), "region:1", "v1", Duration.ofSeconds(1))
        clock.now = clock.now.plusSeconds(2)
        assertNull(store.consume(actor, ActionKey("homeclaim:region_delete"), "region:1", "v1"))
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
        override fun instant(): Instant = now
    }
}
