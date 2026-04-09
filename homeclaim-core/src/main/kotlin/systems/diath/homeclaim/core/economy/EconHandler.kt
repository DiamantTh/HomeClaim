package systems.diath.homeclaim.core.economy

import systems.diath.homeclaim.core.model.PlayerId

/**
 * Abstrakte Wirtschafts-API für verschiedene Economy-Plugins
 * (Vault, TNE, Custom Implementation, etc.)
 */
abstract class EconHandler {
    
    /**
     * Initialisierung - prüft ob Economy verfügbar ist
     */
    abstract fun init(): Boolean
    
    /**
     * Prüfe ob Economy aktiviert/verfügbar ist
     */
    abstract fun isSupported(): Boolean
    
    /**
     * Hole Kontostand eines Spielers
     */
    abstract fun getBalance(playerId: PlayerId): Double
    
    /**
     * Ziehe Betrag vom Spieler ab
     */
    abstract fun withdrawMoney(playerId: PlayerId, amount: Double): Boolean
    
    /**
     * Zahle Betrag dem Spieler ein
     */
    abstract fun depositMoney(playerId: PlayerId, amount: Double): Boolean
    
    /**
     * Formatiere Betrag für Anzeige (z.B. "1000.00 €")
     */
    abstract fun format(amount: Double): String
    
    /**
     * Prüfe ob Spieler sich Betrag leisten kann
     */
    fun canAfford(playerId: PlayerId, amount: Double): Boolean {
        return getBalance(playerId) >= amount
    }
    
    /**
     * Transferiere Geld von Spieler zu Spieler
     */
    fun transfer(fromPlayerId: PlayerId, toPlayerId: PlayerId, amount: Double): Boolean {
        if (!canAfford(fromPlayerId, amount)) return false
        if (!withdrawMoney(fromPlayerId, amount)) return false
        return depositMoney(toPlayerId, amount)
    }
}

/**
 * No-Op Implementation für wenn keine Economy aktiviert ist
 */
class NoOpEconHandler : EconHandler() {
    override fun init(): Boolean = false
    override fun isSupported(): Boolean = false
    override fun getBalance(playerId: PlayerId): Double = 0.0
    override fun withdrawMoney(playerId: PlayerId, amount: Double): Boolean = false
    override fun depositMoney(playerId: PlayerId, amount: Double): Boolean = false
    override fun format(amount: Double): String = amount.toString()
}
