package systems.diath.homeclaim.core.economy

import systems.diath.homeclaim.core.model.PlayerId

/**
 * Economy-Service - zentrale Schnittstelle für alle Economy-Operationen
 */
interface EconService {
    
    /**
     * Hole den aktuellen EconHandler
     */
    fun getHandler(): EconHandler
    
    /**
     * Setze einen neuen EconHandler (z.B. bei Plugin-Wechsel)
     */
    fun setHandler(h: EconHandler)
    
    /**
     * Prüfe ob Economy aktiviert ist
     */
    fun isEnabled(): Boolean
    
    /**
     * Hole Kontostand eines Spielers
     */
    fun getBalance(playerId: PlayerId): Double
    
    /**
     * Ziehe Betrag ab mit Log
     */
    fun charge(playerId: PlayerId, amount: Double, reason: String): Boolean
    
    /**
     * Zahle Betrag ein mit Log
     */
    fun reward(playerId: PlayerId, amount: Double, reason: String): Boolean
    
    /**
     * Transferiere Geld zwischen Spielern
     */
    fun transfer(from: PlayerId, to: PlayerId, amount: Double, reason: String): Boolean
    
    /**
     * Formatiere für Anzeige
     */
    fun format(amount: Double): String
}

/**
 * Default-Implementation des EconService
 */
class SimpleEconService(
    private var handler: EconHandler = NoOpEconHandler()
) : EconService {
    
    override fun getHandler(): EconHandler = handler
    
    override fun setHandler(h: EconHandler) {
        if (h.init()) {
            this.handler = h
        }
    }
    
    override fun isEnabled(): Boolean = handler.isSupported()
    
    override fun getBalance(playerId: PlayerId): Double {
        return if (isEnabled()) handler.getBalance(playerId) else 0.0
    }
    
    override fun charge(playerId: PlayerId, amount: Double, reason: String): Boolean {
        if (!isEnabled()) return false
        val success = handler.withdrawMoney(playerId, amount)
        if (success) {
            // TODO: Log transaction
        }
        return success
    }
    
    override fun reward(playerId: PlayerId, amount: Double, reason: String): Boolean {
        if (!isEnabled()) return false
        val success = handler.depositMoney(playerId, amount)
        if (success) {
            // TODO: Log transaction
        }
        return success
    }
    
    override fun transfer(from: PlayerId, to: PlayerId, amount: Double, reason: String): Boolean {
        if (!isEnabled()) return false
        val success = handler.transfer(from, to, amount)
        if (success) {
            // TODO: Log transaction
        }
        return success
    }
    
    override fun format(amount: Double): String = handler.format(amount)
}
