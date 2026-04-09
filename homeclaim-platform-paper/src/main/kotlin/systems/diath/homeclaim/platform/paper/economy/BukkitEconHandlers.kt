package systems.diath.homeclaim.platform.paper.economy

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import systems.diath.homeclaim.core.economy.EconHandler
import systems.diath.homeclaim.core.model.PlayerId
import java.util.UUID

/**
 * Vault-basierte Economy-Implementation für Bukkit/Paper
 * Nutzt Vault API für Integration mit Economy-Plugins (TNE, Essentials, etc.)
 */
class BukkitVaultEconHandler : EconHandler() {
    
    private var economy: Economy? = null
    
    override fun init(): Boolean {
        val vaultPlugin = Bukkit.getPluginManager().getPlugin("Vault")
        if (vaultPlugin == null || !vaultPlugin.isEnabled) return false
        
        val economyProvider = Bukkit.getServicesManager().getRegistration(Economy::class.java)
        if (economyProvider != null) {
            economy = economyProvider.provider
            return economy != null && economy!!.isEnabled
        }
        return false
    }
    
    override fun isSupported(): Boolean = economy != null && economy!!.isEnabled
    
    override fun getBalance(playerId: PlayerId): Double {
        val econ = economy ?: return 0.0
        val player = Bukkit.getOfflinePlayer(playerId)
        return econ.getBalance(player)
    }
    
    override fun withdrawMoney(playerId: PlayerId, amount: Double): Boolean {
        val econ = economy ?: return false
        if (!canAfford(playerId, amount)) return false
        val player = Bukkit.getOfflinePlayer(playerId)
        val result = econ.withdrawPlayer(player, amount)
        return result.transactionSuccess()
    }
    
    override fun depositMoney(playerId: PlayerId, amount: Double): Boolean {
        val econ = economy ?: return false
        val player = Bukkit.getOfflinePlayer(playerId)
        val result = econ.depositPlayer(player, amount)
        return result.transactionSuccess()
    }
    
    override fun format(amount: Double): String {
        val econ = economy ?: return amount.toString()
        return econ.format(amount)
    }
}

/**
 * EssentialsX Economy-Handler (direkte API ohne Vault)
 * Fallback wenn Vault nicht verfügbar
 */
class EssentialsXEconHandler : EconHandler() {
    
    private var essentials: Any? = null
    private var isModern = false // true = neue API (EconomyService), false = alte API
    
    override fun init(): Boolean {
        try {
            // Versuche neue EconomyService API (EssentialsX 2.19+)
            val economyServiceClass = Class.forName("net.essentialsx.api.v2.services.economy.EconomyService")
            val essentialsClass = Class.forName("com.earth2me.essentials.Essentials")
            val getInstanceMethod = essentialsClass.getMethod("getInstance")
            val essentialsInstance = getInstanceMethod.invoke(null)
            
            val getServiceMethod = essentialsClass.getMethod("getService", Class.forName("java.lang.Class"))
            essentials = getServiceMethod.invoke(essentialsInstance, economyServiceClass)
            isModern = true
            return essentials != null
        } catch (e: Exception) {
            // Fallback zu älteren Essentials API
            try {
                val essentialsClass = Class.forName("com.earth2me.essentials.Essentials")
                val getInstanceMethod = essentialsClass.getMethod("getEssentials")
                essentials = getInstanceMethod.invoke(null)
                isModern = false
                return essentials != null
            } catch (e2: Exception) {
                return false
            }
        }
    }
    
    override fun isSupported(): Boolean = essentials != null && Bukkit.getPluginManager().getPlugin("Essentials") != null
    
    override fun getBalance(playerId: PlayerId): Double {
        val ess = essentials ?: return 0.0
        return try {
            if (isModern) {
                // Modern API: economyService.getBalance(uuid)
                val method = ess.javaClass.getMethod("getBalance", UUID::class.java)
                (method.invoke(ess, playerId) as? Number)?.toDouble() ?: 0.0
            } else {
                // Old API: essentials.getUser(uuid).getMoney()
                val getUserMethod = ess.javaClass.getMethod("getUser", UUID::class.java)
                val user = getUserMethod.invoke(ess, playerId)
                val getMoneyMethod = user.javaClass.getMethod("getMoney")
                (getMoneyMethod.invoke(user) as? Number)?.toDouble() ?: 0.0
            }
        } catch (e: Exception) {
            0.0
        }
    }
    
    override fun withdrawMoney(playerId: PlayerId, amount: Double): Boolean {
        val ess = essentials ?: return false
        return try {
            if (isModern) {
                // Modern API: economyService.withdrawBalance(uuid, amount)
                val method = ess.javaClass.getMethod("withdrawBalance", UUID::class.java, Double::class.java)
                val result = method.invoke(ess, playerId, amount)
                // Returns EconomyResponse, check success
                result?.javaClass?.getMethod("hasSucceeded")?.invoke(result) as? Boolean ?: false
            } else {
                // Old API: essentials.getUser(uuid).takeMoney(amount)
                if (!canAfford(playerId, amount)) return false
                val getUserMethod = ess.javaClass.getMethod("getUser", UUID::class.java)
                val user = getUserMethod.invoke(ess, playerId)
                val takeMoneyMethod = user.javaClass.getMethod("takeMoney", Double::class.java)
                takeMoneyMethod.invoke(user, amount)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
    
    override fun depositMoney(playerId: PlayerId, amount: Double): Boolean {
        val ess = essentials ?: return false
        return try {
            if (isModern) {
                // Modern API: economyService.depositBalance(uuid, amount)
                val method = ess.javaClass.getMethod("depositBalance", UUID::class.java, Double::class.java)
                val result = method.invoke(ess, playerId, amount)
                result?.javaClass?.getMethod("hasSucceeded")?.invoke(result) as? Boolean ?: false
            } else {
                // Old API: essentials.getUser(uuid).giveMoney(amount)
                val getUserMethod = ess.javaClass.getMethod("getUser", UUID::class.java)
                val user = getUserMethod.invoke(ess, playerId)
                val giveMoneyMethod = user.javaClass.getMethod("giveMoney", Double::class.java)
                giveMoneyMethod.invoke(user, amount)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
    
    override fun format(amount: Double): String {
        return String.format("%.2f €", amount)
    }
}

/**
 * In-Memory/Mock-Implementation für Testing ohne Economy-Plugins
 */
class MockEconHandler : EconHandler() {
    
    private val balances = mutableMapOf<PlayerId, Double>()
    
    override fun init(): Boolean = true
    
    override fun isSupported(): Boolean = true
    
    override fun getBalance(playerId: PlayerId): Double {
        return balances.getOrDefault(playerId, 0.0)
    }
    
    override fun withdrawMoney(playerId: PlayerId, amount: Double): Boolean {
        if (!canAfford(playerId, amount)) return false
        balances[playerId] = getBalance(playerId) - amount
        return true
    }
    
    override fun depositMoney(playerId: PlayerId, amount: Double): Boolean {
        balances[playerId] = getBalance(playerId) + amount
        return true
    }
    
    override fun format(amount: Double): String = String.format("%.2f €", amount)
    
    // Test-Hilfen
    fun setBalance(playerId: PlayerId, amount: Double) {
        balances[playerId] = amount
    }
}
