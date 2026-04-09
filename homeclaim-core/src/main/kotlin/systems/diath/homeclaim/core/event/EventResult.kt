package systems.diath.homeclaim.core.event

/**
 * Result-Typ für Ereignisse - bestimmt ob/wie eine Aktion ausgeführt wird.
 */
enum class EventResult {
    /**
     * Aktion blockieren/abbrechen
     */
    DENY,
    
    /**
     * Normales Verhalten (Standard)
     */
    ACCEPT,
    
    /**
     * Aktion erzwingen (z.B. kostenlos)
     */
    FORCE
}
