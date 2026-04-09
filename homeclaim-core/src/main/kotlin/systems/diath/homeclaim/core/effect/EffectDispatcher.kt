package systems.diath.homeclaim.core.effect

import systems.diath.homeclaim.core.platform.Location
import java.util.UUID

/**
 * Platform-agnostic particle effect.
 */
data class ParticleEffect(
    val name: String,  // "FLAME", "SMOKE", etc.
    val location: Location,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val offsetZ: Float = 0f,
    val speed: Float = 0f,
    val count: Int = 1,
    val data: Map<String, Any> = emptyMap()
)

/**
 * Platform-agnostic sound effect.
 */
data class SoundEffect(
    val name: String,  // "BLOCK_NOTE_BLOCK_PLING", etc.
    val location: Location,
    val volume: Float = 1f,
    val pitch: Float = 1f,
    val data: Map<String, Any> = emptyMap()
)

/**
 * Effect dispatcher for particles and sounds.
 */
interface EffectDispatcher {
    
    /**
     * Play particle effect.
     */
    fun playParticle(effect: ParticleEffect)
    
    /**
     * Play sound effect.
     */
    fun playSound(effect: SoundEffect)
    
    /**
     * Play effect to specific player.
     */
    fun playParticleToPlayer(playerId: UUID, effect: ParticleEffect)
    
    /**
     * Play sound to specific player.
     */
    fun playSoundToPlayer(playerId: UUID, effect: SoundEffect)
    
    /**
     * Play effect in radius around location.
     */
    fun playParticleInRadius(effect: ParticleEffect, radiusBlocks: Int)
    
    /**
     * Play sound in radius around location.
     */
    fun playSoundInRadius(effect: SoundEffect, radiusBlocks: Int)
}

/**
 * Null effect dispatcher (for testing/headless).
 */
class NullEffectDispatcher : EffectDispatcher {
    override fun playParticle(effect: ParticleEffect) {}
    override fun playSound(effect: SoundEffect) {}
    override fun playParticleToPlayer(playerId: UUID, effect: ParticleEffect) {}
    override fun playSoundToPlayer(playerId: UUID, effect: SoundEffect) {}
    override fun playParticleInRadius(effect: ParticleEffect, radiusBlocks: Int) {}
    override fun playSoundInRadius(effect: SoundEffect, radiusBlocks: Int) {}
}

/**
 * Common particle effects.
 */
object CommonParticles {
    const val FLAME = "FLAME"
    const val SMOKE = "SMOKE"
    const val EXPLOSION = "EXPLOSION"
    const val CLOUD = "CLOUD"
    const val ENCHANT = "ENCHANT"
    const val SPELL = "SPELL"
    const val HAPPY_VILLAGER = "HAPPY_VILLAGER"
    const val ANGRY_VILLAGER = "ANGRY_VILLAGER"
    const val NOTE = "NOTE"
    const val PORTAL = "PORTAL"
}

/**
 * Common sound effects.
 */
object CommonSounds {
    const val BLOCK_NOTE_PLING = "BLOCK_NOTE_BLOCK_PLING"
    const val ENTITY_PLAYER_LEVELUP = "ENTITY_PLAYER_LEVELUP"
    const val ENTITY_EXPERIENCE_ORB_PICKUP = "ENTITY_EXPERIENCE_ORB_PICKUP"
    const val UI_BUTTON_CLICK = "UI_BUTTON_CLICK"
    const val BLOCK_ANVIL_PLACE = "BLOCK_ANVIL_PLACE"
    const val ENTITY_VILLAGER_YES = "ENTITY_VILLAGER_YES"
    const val ENTITY_VILLAGER_NO = "ENTITY_VILLAGER_NO"
    const val BLOCK_GLASS_BREAK = "BLOCK_GLASS_BREAK"
}
