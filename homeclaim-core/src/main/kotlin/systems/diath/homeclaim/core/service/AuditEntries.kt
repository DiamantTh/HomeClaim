package systems.diath.homeclaim.core.service

import java.util.UUID
import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.Position
import systems.diath.homeclaim.core.policy.Decision

/**
 * Factory helpers for canonical audit entries.
 */
object AuditEntries {
    fun denied(
        actorId: PlayerId?,
        targetId: UUID? = null,
        category: String,
        action: String,
        position: Position,
        decision: Decision,
        extra: Map<String, Any?> = emptyMap()
    ): AuditEntry {
        return AuditEntry(
            actorId = actorId,
            targetId = targetId,
            category = category,
            action = action,
            payload = AuditPayloads.deniedPolicyPayload(position, decision, extra)
        )
    }

    fun regionCreated(actorId: PlayerId?, targetId: UUID, world: String, bounds: String, shape: String): AuditEntry {
        return AuditEntry(
            actorId = actorId,
            targetId = targetId,
            category = AuditTaxonomy.Category.REGION,
            action = AuditTaxonomy.Action.CREATED,
            payload = AuditPayloads.worldPayload(world, extra = mapOf("bounds" to bounds, "shape" to shape))
        )
    }

    fun regionUpdated(actorId: PlayerId?, targetId: UUID, world: String, owner: String, ownerChanged: Boolean? = null): AuditEntry {
        val extra = mutableMapOf<String, Any?>("owner" to owner)
        if (ownerChanged != null) extra["ownerChanged"] = ownerChanged
        return AuditEntry(
            actorId = actorId,
            targetId = targetId,
            category = AuditTaxonomy.Category.REGION,
            action = AuditTaxonomy.Action.UPDATED,
            payload = AuditPayloads.worldPayload(world, extra = extra)
        )
    }

    fun regionDeleted(actorId: PlayerId?, targetId: UUID, world: String, owner: String): AuditEntry {
        return AuditEntry(
            actorId = actorId,
            targetId = targetId,
            category = AuditTaxonomy.Category.REGION,
            action = AuditTaxonomy.Action.DELETED,
            payload = AuditPayloads.worldPayload(world, extra = mapOf("owner" to owner))
        )
    }

    fun profileApplied(targetId: UUID, profileName: String, flagCount: Int, limitCount: Int): AuditEntry {
        return AuditEntry(
            actorId = null,
            targetId = targetId,
            category = AuditTaxonomy.Category.PROFILE,
            action = AuditTaxonomy.Action.APPLIED,
            payload = mapOf("profileName" to profileName, "flagCount" to flagCount, "limitCount" to limitCount)
        )
    }

    fun flagUpsert(targetId: UUID, key: String, value: String): AuditEntry {
        return AuditEntry(
            actorId = null,
            targetId = targetId,
            category = AuditTaxonomy.Category.FLAG,
            action = AuditTaxonomy.Action.UPSERT,
            payload = mapOf("key" to key, "value" to value)
        )
    }

    fun limitUpsert(targetId: UUID, key: String, value: String): AuditEntry {
        return AuditEntry(
            actorId = null,
            targetId = targetId,
            category = AuditTaxonomy.Category.LIMIT,
            action = AuditTaxonomy.Action.UPSERT,
            payload = mapOf("key" to key, "value" to value)
        )
    }

    fun componentUsed(
        actorId: PlayerId?,
        targetId: UUID,
        action: String,
        position: Position,
        platform: String,
        extra: Map<String, Any?> = emptyMap()
    ): AuditEntry {
        return AuditEntry(
            actorId = actorId,
            targetId = targetId,
            category = AuditTaxonomy.Category.COMPONENT,
            action = action,
            payload = AuditPayloads.actionPayload(position, platform, extra)
        )
    }

    fun plotImported(
        actorId: PlayerId?,
        targetId: UUID,
        world: String,
        platform: String,
        source: String,
        originalId: String
    ): AuditEntry {
        return AuditEntry(
            actorId = actorId,
            targetId = targetId,
            category = AuditTaxonomy.Category.IMPORT,
            action = AuditTaxonomy.Action.PLOT_IMPORTED,
            payload = AuditPayloads.worldPayload(
                world = world,
                platform = platform,
                extra = mapOf("source" to source, "originalId" to originalId)
            )
        )
    }
}
