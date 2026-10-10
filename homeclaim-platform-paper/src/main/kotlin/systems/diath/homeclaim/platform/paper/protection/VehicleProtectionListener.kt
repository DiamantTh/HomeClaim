package systems.diath.homeclaim.platform.paper.protection

import io.papermc.paper.event.entity.EntityMoveEvent
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Vehicle
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityMountEvent
import org.bukkit.event.vehicle.VehicleDamageEvent
import org.bukkit.event.vehicle.VehicleDestroyEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.event.vehicle.VehicleMoveEvent
import systems.diath.homeclaim.core.policy.Action
import systems.diath.homeclaim.core.policy.ActionActor
import systems.diath.homeclaim.core.policy.DecisionEffect

internal class VehicleProtectionListener(private val adapter: PaperProtectionAdapter) : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onEntityMount(event: EntityMountEvent) = protectionEvent(event, "controllable-entity-mount") {
        if (event.mount is Vehicle) return@protectionEvent
        val rider = event.entity
        val actor = rider.toActionActor()
        val request = adapter.request(
            actor = actor,
            action = Action.REGION_VEHICLE_USE.key,
            source = rider.location.toHomeClaimPosition(),
            target = event.mount.location.toHomeClaimPosition(),
            event = "EntityMountEvent",
            attributes = mapOf("mountType" to event.mount.type.name)
        )
        reject(event, request, adapter.check(request), rider as? Player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onEnter(event: VehicleEnterEvent) = protectionEvent(event, "vehicle-enter") {
        val player = event.entered as? Player ?: return@protectionEvent
        val request = adapter.request(
            actor = ActionActor.Player(player.uniqueId),
            action = Action.REGION_VEHICLE_USE.key,
            source = player.location.toHomeClaimPosition(),
            target = event.vehicle.location.toHomeClaimPosition(),
            event = "VehicleEnterEvent",
            attributes = mapOf("vehicleType" to event.vehicle.type.name)
        )
        reject(event, request, adapter.check(request), player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onDamage(event: VehicleDamageEvent) = protectionEvent(event, "vehicle-damage") {
        val attacker = event.attacker ?: return@protectionEvent
        val request = adapter.request(
            actor = attacker.toActionActor(),
            action = Action.REGION_VEHICLE_USE.key,
            source = attacker.location.toHomeClaimPosition(),
            target = event.vehicle.location.toHomeClaimPosition(),
            event = "VehicleDamageEvent",
            attributes = mapOf("vehicleType" to event.vehicle.type.name, "operation" to "damage")
        )
        reject(event, request, adapter.check(request), attacker as? Player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onDestroy(event: VehicleDestroyEvent) = protectionEvent(event, "vehicle-destroy") {
        val attacker = event.attacker
        val request = adapter.request(
            actor = attacker?.toActionActor() ?: ActionActor.System("vehicle-destruction"),
            action = Action.REGION_VEHICLE_USE.key,
            source = attacker?.location?.toHomeClaimPosition(),
            target = event.vehicle.location.toHomeClaimPosition(),
            event = "VehicleDestroyEvent",
            attributes = mapOf("vehicleType" to event.vehicle.type.name)
        )
        reject(event, request, adapter.check(request), attacker as? Player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onMove(event: VehicleMoveEvent) = protectionEvent(event, "vehicle-move") {
        val from = event.from.toHomeClaimPosition()
        val to = event.to.toHomeClaimPosition()
        if (event.from.world == event.to.world && event.from.blockX == event.to.blockX &&
            event.from.blockY == event.to.blockY && event.from.blockZ == event.to.blockZ
        ) return@protectionEvent
        val sourceEndpoint = adapter.resolveEndpoint(from)
        val targetEndpoint = adapter.resolveEndpoint(to)
        if (sourceEndpoint.worldId == targetEndpoint.worldId && sourceEndpoint.areaId == targetEndpoint.areaId &&
            sourceEndpoint.resource == targetEndpoint.resource
        ) return@protectionEvent

        val vehicle = event.vehicle
        val controllingPlayer = vehicle.passengers.firstOrNull { it is Player } as? Player
        val actor = controllingPlayer?.let { ActionActor.Player(it.uniqueId) }
            ?: ActionActor.Automation("vehicle:${vehicle.uniqueId}", "minecraft:vehicle")
        val request = adapter.request(
            actor = actor,
            action = Action.VEHICLE_MOVE.key,
            source = from,
            target = to,
            event = "VehicleMoveEvent",
            attributes = mapOf("vehicleType" to vehicle.type.name)
        ).copy(source = sourceEndpoint, target = targetEndpoint)
        val decision = adapter.check(request)
        if (decision.effect == DecisionEffect.DENY) {
            vehicle.teleport(event.from)
            controllingPlayer?.let { adapter.deny(it, decision) }
            adapter.auditDenied(request, decision)
        }
    }

    /** Paper's entity movement event covers controllable mounts outside Bukkit's Vehicle API (horse, pig, strider). */
    @EventHandler(ignoreCancelled = true)
    fun onControllableMountMove(event: EntityMoveEvent) = protectionEvent(event, "controllable-mount-move") {
        val mount = event.entity
        if (mount is Vehicle || !event.hasChangedBlock()) return@protectionEvent
        val controllingPlayer = mount.passengers.firstOrNull { it is Player } as? Player ?: return@protectionEvent
        val from = event.from.toHomeClaimPosition()
        val to = event.to.toHomeClaimPosition()
        val sourceEndpoint = adapter.resolveEndpoint(from)
        val targetEndpoint = adapter.resolveEndpoint(to)
        if (sourceEndpoint.worldId == targetEndpoint.worldId && sourceEndpoint.areaId == targetEndpoint.areaId &&
            sourceEndpoint.resource == targetEndpoint.resource
        ) return@protectionEvent

        val request = adapter.request(
            actor = ActionActor.Player(controllingPlayer.uniqueId),
            action = Action.VEHICLE_MOVE.key,
            source = from,
            target = to,
            event = "EntityMoveEvent:controllable-mount",
            attributes = mapOf("vehicleType" to mount.type.name)
        ).copy(source = sourceEndpoint, target = targetEndpoint)
        val decision = adapter.check(request)
        if (decision.effect == DecisionEffect.DENY) {
            event.isCancelled = true
            adapter.deny(controllingPlayer, decision)
            adapter.auditDenied(request, decision)
        }
    }

    private fun reject(
        event: org.bukkit.event.Cancellable,
        request: systems.diath.homeclaim.core.policy.ActionRequest,
        decision: systems.diath.homeclaim.core.policy.Decision,
        player: Player?
    ) {
        if (decision.effect != DecisionEffect.DENY) return
        event.isCancelled = true
        player?.let { adapter.deny(it, decision) }
        adapter.auditDenied(request, decision)
    }
}
