package systems.diath.homeclaim.core.event

import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.RegionId
import java.util.UUID

/**
 * Base class für Region-Events
 */
abstract class RegionEvent(
    val regionId: RegionId,
    val initiatorId: PlayerId
) {
    var eventResult: EventResult? = null
    var cancelled: Boolean = false
        private set
    
    fun setCancelled(cancel: Boolean) {
        this.cancelled = cancel
    }
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
 * - Für Logging
 * - Nicht cancellable
 */
class PostRegionClaimEvent(
    regionId: RegionId,
    initiatorId: PlayerId,
    val finalCost: Double
) : RegionEvent(regionId, initiatorId)

/**
 * Event bei Besitzerwechsel
 * - Kann blockiert werden (z.B. bei Schulden/Pfandrecht)
 */
class RegionTransferEvent(
    regionId: RegionId,
    initiatorId: PlayerId,
    val oldOwnerId: PlayerId?,
    var newOwnerId: PlayerId
) : RegionEvent(regionId, initiatorId)

/**
 * Event nach Besitzerwechsel (Post-Event)
 * - Für Audit-Logging
 */
class PostRegionTransferEvent(
    regionId: RegionId,
    initiatorId: PlayerId,
    val oldOwnerId: PlayerId?,
    val newOwnerId: PlayerId
) : RegionEvent(regionId, initiatorId)

/**
 * Event vor Region-Erstellung (Pre-Event)
 * - Kann blockiert werden
 * - Region-Daten können validiert/modifiziert werden
 */
class RegionCreateEvent(
    regionId: RegionId,
    initiatorId: PlayerId,
    val world: String,
    val bounds: systems.diath.homeclaim.core.model.Bounds
) : RegionEvent(regionId, initiatorId)

/**
 * Event nach erfolgreicher Region-Erstellung (Post-Event)
 * - Für Logging und Third-Party-Integration
 */
class PostRegionCreateEvent(
    regionId: RegionId,
    initiatorId: PlayerId,
    val world: String
) : RegionEvent(regionId, initiatorId)

/**
 * Event vor Region-Update (Pre-Event)
 * - Kann blockiert werden
 */
class RegionUpdateEvent(
    regionId: RegionId,
    initiatorId: PlayerId,
    val changes: Map<String, Any>
) : RegionEvent(regionId, initiatorId)

/**
 * Event nach erfolgreicher Region-Aktualisierung (Post-Event)
 * - Für Change-Tracking
 */
class PostRegionUpdateEvent(
    regionId: RegionId,
    initiatorId: PlayerId,
    val changes: Map<String, Any>
) : RegionEvent(regionId, initiatorId)

/**
 * Event vor Region-Löschung (Pre-Event)
 * - Kann blockiert werden (z.B. wenn noch Komponenten vorhanden)
 */
class RegionDeleteEvent(
    regionId: RegionId,
    initiatorId: PlayerId,
    val world: String
) : RegionEvent(regionId, initiatorId)

/**
 * Event nach erfolgreicher Region-Löschung (Post-Event)
 * - Für Cleanup-Operationen
 */
class PostRegionDeleteEvent(
    regionId: RegionId,
    initiatorId: PlayerId,
    val world: String
) : RegionEvent(regionId, initiatorId)
