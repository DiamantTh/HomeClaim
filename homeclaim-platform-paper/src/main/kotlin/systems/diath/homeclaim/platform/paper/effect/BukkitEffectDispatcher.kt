package systems.diath.homeclaim.platform.paper.effect

import org.bukkit.Bukkit
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Player as BukkitPlayer
import systems.diath.homeclaim.core.effect.EffectDispatcher
import systems.diath.homeclaim.core.effect.ParticleEffect
import systems.diath.homeclaim.core.effect.SoundEffect
import systems.diath.homeclaim.core.platform.PlatformProvider
import java.util.UUID

/**
 * Bukkit implementation of EffectDispatcher.
 */
class BukkitEffectDispatcher(
    private val platformProvider: PlatformProvider
) : EffectDispatcher {
    
    override fun playParticle(effect: ParticleEffect) {
        try {
            val particle = Particle.valueOf(effect.name.uppercase())
            val world = Bukkit.getWorld(effect.location.world) ?: return
            val location = org.bukkit.Location(
                world,
                effect.location.x.toDouble() + 0.5,
                effect.location.y.toDouble(),
                effect.location.z.toDouble() + 0.5
            )
            
            val spawnTask = {
                world.spawnParticle(
                    particle,
                    location,
                    effect.count,
                    effect.offsetX.toDouble(),
                    effect.offsetY.toDouble(),
                    effect.offsetZ.toDouble(),
                    effect.speed.toDouble()
                )
            }
            
            spawnTask()
        } catch (e: Exception) {
            // Particle not found or other error
        }
    }
    
    override fun playSound(effect: SoundEffect) {
        try {
            @Suppress("DEPRECATION")
            val sound = Sound.valueOf(effect.name.uppercase())
            val world = Bukkit.getWorld(effect.location.world) ?: return
            val location = org.bukkit.Location(
                world,
                effect.location.x.toDouble() + 0.5,
                effect.location.y.toDouble(),
                effect.location.z.toDouble() + 0.5
            )
            
            val playTask = {
                world.playSound(
                    location,
                    sound,
                    effect.volume,
                    effect.pitch
                )
            }
            
            playTask()
        } catch (e: Exception) {
            // Sound not found or other error
        }
    }
    
    override fun playParticleToPlayer(playerId: UUID, effect: ParticleEffect) {
        val player = Bukkit.getPlayer(playerId) ?: return
        
        try {
            val particle = Particle.valueOf(effect.name.uppercase())
            val world = player.world
            
            world.spawnParticle(
                particle,
                effect.location.x.toDouble() + 0.5,
                effect.location.y.toDouble(),
                effect.location.z.toDouble() + 0.5,
                effect.count,
                effect.offsetX.toDouble(),
                effect.offsetY.toDouble(),
                effect.offsetZ.toDouble(),
                effect.speed.toDouble(),
                player
            )
        } catch (e: Exception) {
            // Particle not found or other error
        }
    }
    
    override fun playSoundToPlayer(playerId: UUID, effect: SoundEffect) {
        val player = Bukkit.getPlayer(playerId) ?: return
        
        try {
            @Suppress("DEPRECATION")
            val sound = Sound.valueOf(effect.name.uppercase())
            
            player.playSound(
                org.bukkit.Location(
                    player.world,
                    effect.location.x.toDouble() + 0.5,
                    effect.location.y.toDouble(),
                    effect.location.z.toDouble() + 0.5
                ),
                sound,
                effect.volume,
                effect.pitch
            )
        } catch (e: Exception) {
            // Sound not found or other error
        }
    }
    
    override fun playParticleInRadius(effect: ParticleEffect, radiusBlocks: Int) {
        try {
            val particle = Particle.valueOf(effect.name.uppercase())
            val world = Bukkit.getWorld(effect.location.world) ?: return
            
            world.getNearbyPlayers(
                org.bukkit.Location(
                    world,
                    effect.location.x.toDouble(),
                    effect.location.y.toDouble(),
                    effect.location.z.toDouble()
                ),
                radiusBlocks.toDouble()
            ).forEach { player ->
                world.spawnParticle(
                    particle,
                    effect.location.x.toDouble() + 0.5,
                    effect.location.y.toDouble(),
                    effect.location.z.toDouble() + 0.5,
                    effect.count,
                    effect.offsetX.toDouble(),
                    effect.offsetY.toDouble(),
                    effect.offsetZ.toDouble(),
                    effect.speed.toDouble(),
                    player
                )
            }
        } catch (e: Exception) {
            // Particle not found or other error
        }
    }
    
    override fun playSoundInRadius(effect: SoundEffect, radiusBlocks: Int) {
        try {
            @Suppress("DEPRECATION")
            val sound = Sound.valueOf(effect.name.uppercase())
            val world = Bukkit.getWorld(effect.location.world) ?: return
            
            world.playSound(
                org.bukkit.Location(
                    world,
                    effect.location.x.toDouble() + 0.5,
                    effect.location.y.toDouble(),
                    effect.location.z.toDouble() + 0.5
                ),
                sound,
                SoundCategory.MASTER,
                effect.volume,
                effect.pitch
            )
        } catch (e: Exception) {
            // Sound not found or other error
        }
    }
}
