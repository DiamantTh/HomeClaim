package systems.diath.homeclaim.core.policy

import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.ComponentId
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.model.ZoneId
import java.util.UUID

/** Stable wire identity. Keys are lowercase and explicitly namespaced. */
@JvmInline
value class ActionKey(val value: String) {
    init {
        require(KEY_PATTERN.matches(value)) { "Action keys must use the '<namespace>:<key>' form" }
    }

    override fun toString(): String = value

    companion object {
        private val KEY_PATTERN = Regex("[a-z0-9][a-z0-9._-]*:[a-z0-9][a-z0-9._/-]*")
        private val BUILTIN_DEFINITIONS_BY_KEY by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Action.entries.associateBy(Action::key)
        }

        fun fromLegacy(action: Action): ActionKey = action.key

        fun parse(value: String): ActionKey = ActionKey(value)

        /** Returns HomeClaim's built-in policy metadata, if this key has a definition. */
        fun definitionOrNull(key: ActionKey): Action? = BUILTIN_DEFINITIONS_BY_KEY[key]

        /** Compatibility name for callers that still refer to built-in actions as legacy values. */
        @Deprecated("Use definitionOrNull; ActionKey is the canonical identity")
        fun toLegacy(key: ActionKey): Action? = definitionOrNull(key)
    }
}

/** Stable names for administrative operations that are audited but not policy flags. */
object HomeClaimOperationActions {
    val REGION_CLAIM = ActionKey("homeclaim:region_claim")
    val REGION_MERGE = ActionKey("homeclaim:region_merge")
    val REGION_UNLINK = ActionKey("homeclaim:region_unlink")
    val REGION_RESET = ActionKey("homeclaim:region_reset")
    val REGION_HOME_SET = ActionKey("homeclaim:region_home_set")
    val OWNERSHIP_TRANSFER = ActionKey("homeclaim:ownership_transfer")
    val ROLE_CHANGE = ActionKey("homeclaim:role_change")
    val FLAG_CHANGE = ActionKey("homeclaim:flag_change")
    val LIMIT_CHANGE = ActionKey("homeclaim:limit_change")
    val PROFILE_CHANGE = ActionKey("homeclaim:profile_change")
    val COMPONENT_CHANGE = ActionKey("homeclaim:component_change")
    val ENTRY_DENY_CHANGE = ActionKey("homeclaim:entry_deny_change")
    val PRICE_CHANGE = ActionKey("homeclaim:price_change")
    val METADATA_CHANGE = ActionKey("homeclaim:metadata_change")
}

@JvmInline
value class CorrelationId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun newId(): CorrelationId = CorrelationId(UUID.randomUUID())
    }
}

/** Actors stay independent of Bukkit objects and do not pretend automation is a player. */
sealed interface ActionActor {
    val kind: String
    val stableId: String?

    data class Player(val playerId: PlayerId) : ActionActor {
        override val kind: String = "PLAYER"
        override val stableId: String = playerId.toString()
    }

    data class Entity(val entityId: UUID, val entityType: String) : ActionActor {
        override val kind: String = "ENTITY"
        override val stableId: String = entityId.toString()
    }

    data class Automation(val id: String, val source: String? = null) : ActionActor {
        override val kind: String = "AUTOMATION"
        override val stableId: String = id
    }

    data class System(val id: String = "homeclaim") : ActionActor {
        override val kind: String = "SYSTEM"
        override val stableId: String = id
    }
}

/** Typed references to resources which may be the source or target of an action. */
sealed interface ActionResourceRef {
    val type: String
    val id: String

    data class World(val worldId: WorldId) : ActionResourceRef {
        override val type = "world"
        override val id = worldId.value.toString()
    }

    data class Area(val areaId: AreaId) : ActionResourceRef {
        override val type = "area"
        override val id = areaId.value.toString()
    }

    data class Region(val regionId: RegionId) : ActionResourceRef {
        override val type = "region"
        override val id = regionId.value.toString()
    }

    data class Zone(val zoneId: ZoneId) : ActionResourceRef {
        override val type = "zone"
        override val id = zoneId.value.toString()
    }

    data class Component(val componentId: ComponentId) : ActionResourceRef {
        override val type = "component"
        override val id = componentId.value.toString()
    }
}

data class ActionEndpoint(
    val position: Position? = null,
    val resource: ActionResourceRef? = null,
    val areaId: AreaId? = (resource as? ActionResourceRef.Area)?.areaId
) {
    init {
        require(position != null || resource != null) { "An action endpoint needs a position or resource" }
    }

    val worldId: WorldId? get() = position?.worldId ?: (resource as? ActionResourceRef.World)?.worldId
}

/** Small typed context for common policy inputs; legacy values are an adapter path only. */
data class ActionContext(
    val platform: String = "unknown",
    val eventName: String? = null,
    val playerName: String? = null,
    val entryBypass: Boolean = false,
    val componentId: ComponentId? = null,
    val attributes: Map<String, String> = emptyMap(),
    val legacyValues: Map<String, Any?> = emptyMap(),
    /** Explicit adapter declaration that a native process may be evaluated by resource policy. */
    val modPolicyDefaults: ModPolicyDefaults? = null
) {
    internal fun toLegacyMap(): Map<String, Any?> = buildMap {
        putAll(legacyValues)
        put("platform", platform)
        eventName?.let { put("event", it) }
        playerName?.let { put("playerName", it) }
        put("entryBypass", entryBypass)
        componentId?.let { put("componentId", it) }
        modPolicyDefaults?.let { put(ModPolicyDefaults.EXTRA_KEY, it) }
        attributes.forEach { (key, value) -> put(key, value) }
    }

    companion object {
        fun fromLegacy(values: Map<String, Any?>): ActionContext = ActionContext(
            platform = values["platform"]?.toString() ?: "unknown",
            eventName = values["event"]?.toString(),
            playerName = values["playerName"]?.toString(),
            entryBypass = values["entryBypass"] == true,
            componentId = values["componentId"] as? ComponentId,
            legacyValues = values
        )
    }
}

/** One logical domain action, independent of its Paper event or other adapter. */
data class ActionRequest(
    val actor: ActionActor,
    val actionKey: ActionKey,
    val source: ActionEndpoint? = null,
    val target: ActionEndpoint? = null,
    /** Domain region when the target is a subresource such as a component. */
    val regionId: RegionId? = null,
    /** Resolved HomeClaim Area; adapters may leave it unset for the scope resolver. */
    val areaId: AreaId? = null,
    val context: ActionContext = ActionContext(),
    val correlationId: CorrelationId = CorrelationId.newId(),
    val parentCorrelationId: CorrelationId? = null
) {
    val worldId: WorldId? get() = target?.worldId ?: source?.worldId

    /** Returns a validation message when a known built-in action lacks its required target. */
    fun validationError(): String? {
        val action = ActionKey.definitionOrNull(actionKey) ?: return null
        return if (action.requiresTarget && target == null) "Action ${actionKey.value} requires a target" else null
    }

    fun withTarget(endpoint: ActionEndpoint): ActionRequest = copy(target = endpoint)
}

/** Bulk requests preserve one logical correlation while retaining every target for evaluation. */
data class BulkActionRequest(
    val action: ActionRequest,
    val targets: List<ActionEndpoint>
) {
    init {
        require(targets.isNotEmpty()) { "A bulk action needs at least one target" }
        val definition = ActionKey.definitionOrNull(action.actionKey)
        require(definition?.bulkCapable == true) {
            "Action ${action.actionKey.value} is not bulk-capable"
        }
    }

    val correlationId: CorrelationId get() = action.correlationId
}

data class BulkActionResult(
    val correlationId: CorrelationId,
    val decisions: List<Decision>
) {
    val targetCount: Int get() = decisions.size
    val deniedCount: Int get() = decisions.count { it.effect == DecisionEffect.DENY }
    val allowedCount: Int get() = decisions.count { it.effect == DecisionEffect.ALLOW }
    val notApplicableCount: Int get() = decisions.count { it.effect == DecisionEffect.NOT_APPLICABLE }
    val allowed: Boolean get() = deniedCount == 0
}
