package systems.diath.homeclaim.client.event

import systems.diath.homeclaim.client.model.ComponentId
import systems.diath.homeclaim.client.model.PlayerId

/**
 * Base class for lift/component events (elevator pads, teleport pads)
 */
abstract class LiftEvent(
    val componentId: ComponentId,
    val playerId: PlayerId
) : HomeclaimEvent()

/**
 * Event before elevator use
 */
class ElevatorUseEvent(
    componentId: ComponentId,
    playerId: PlayerId,
    val targetFloor: String
) : LiftEvent(componentId, playerId)

/**
 * Event after successful elevator use
 */
class PostElevatorUseEvent(
    componentId: ComponentId,
    playerId: PlayerId,
    val targetFloor: String
) : LiftEvent(componentId, playerId)

/**
 * Event before teleport pad use
 */
class TeleporterUseEvent(
    componentId: ComponentId,
    playerId: PlayerId,
    val targetComponentId: ComponentId
) : LiftEvent(componentId, playerId)

/**
 * Event after successful teleporter use
 */
class PostTeleporterUseEvent(
    componentId: ComponentId,
    playerId: PlayerId,
    val targetComponentId: ComponentId
) : LiftEvent(componentId, playerId)

/**
 * Event before component creation
 */
class ComponentCreateEvent(
    componentId: ComponentId,
    playerId: PlayerId,
    val componentType: String
) : LiftEvent(componentId, playerId)

/**
 * Event after component creation
 */
class PostComponentCreateEvent(
    componentId: ComponentId,
    playerId: PlayerId,
    val componentType: String
) : LiftEvent(componentId, playerId)

/**
 * Event before component deletion
 */
class ComponentDeleteEvent(
    componentId: ComponentId,
    playerId: PlayerId
) : LiftEvent(componentId, playerId)

/**
 * Event after component deletion
 */
class PostComponentDeleteEvent(
    componentId: ComponentId,
    playerId: PlayerId
) : LiftEvent(componentId, playerId)
