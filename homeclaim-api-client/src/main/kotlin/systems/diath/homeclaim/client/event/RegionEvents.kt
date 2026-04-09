package systems.diath.homeclaim.client.event

import systems.diath.homeclaim.client.model.PlayerId
import systems.diath.homeclaim.client.model.RegionId

/**
 * Base class für Region-Events
 */
abstract class RegionEvent(
    val regionId: RegionId,
    val initiatorId: PlayerId
) : HomeclaimEvent() {
    var eventResult: EventResult? = null
}

/**
 * Event vor Region-Kauf (Pre-Event)
 * - Preis kann modifiziert werden
 * - Event kann abgebrochen werden
 * - Zusätzliche Gebühren können hinzugefügt werden
 */
class RegionBuyEvent(
    regionId: RegionId,
    initiatorId: PlayerId,
    val sellerId: PlayerId,
    var price: Double
) : RegionEvent(regionId, initiatorId)

/**
 * Event nach erfolgreichem Region-Kauf (Post-Event)
 * - Für Logging und Benachrichtigungen
 * - Nicht cancellable
 */
class PostRegionBuyEvent(
    regionId: RegionId,
    initiatorId: PlayerId,
    val sellerId: PlayerId,
    val finalPrice: Double
) : RegionEvent(regionId, initiatorId)

/**
 * Event vor Region-Claim (Pre-Event)
 * - Claim-Kosten können modifiziert werden
 * - Event kann abgebrochen werden
 */
class RegionClaimEvent(
    regionId: RegionId,
    initiatorId: PlayerId,
    var cost: Double = 0.0
) : RegionEvent(regionId, initiatorId)

/**
 * Event nach erfolgreichem Region-Claim (Post-Event)
 * - Für Logging und Benachrichtigungen
 * - Nicht cancellable
 */
class PostRegionClaimEvent(
    regionId: RegionId,
    initiatorId: PlayerId,
    val finalCost: Double
) : RegionEvent(regionId, initiatorId)

/**
 * Event vor Region-Löschen
 * - Kann abgebrochen werden
 */
class RegionDeleteEvent(
    regionId: RegionId,
    initiatorId: PlayerId
) : RegionEvent(regionId, initiatorId)

/**
 * Event nach Region-Löschen
 * - Nicht cancellable
 */
class PostRegionDeleteEvent(
    regionId: RegionId,
    initiatorId: PlayerId
) : RegionEvent(regionId, initiatorId)
