package systems.diath.homeclaim.core.event

import kotlin.reflect.KClass

/**
 * Listener-Interface für Events
 */
interface EventListener {
    /**
     * Event-Handler - wird automatisch als onEventType erkannt
     * z.B. onRegionBuyEvent(event: RegionBuyEvent)
     */
}

/**
 * Zentrale Event-Registry und -Dispatcher
 */
class EventDispatcher {
    private val listeners = mutableListOf<EventListener>()
    
    /**
     * Listener registrieren
     */
    fun registerListener(listener: EventListener) {
        listeners.add(listener)
    }
    
    /**
     * Listener entfernen
     */
    fun unregisterListener(listener: EventListener) {
        listeners.remove(listener)
    }
    
    /**
     * Event dispatchen - ruft alle registrierten Listener auf
     */
    fun <T : RegionEvent> dispatch(event: T) {
        val eventClass = event::class
        val eventTypeName = eventClass.simpleName ?: return
        val methodName = "on$eventTypeName"
        
        for (listener in listeners) {
            // Vereinfachter Fallback: Reflection per Methodenname
            try {
                val javaClass = listener.javaClass
                val handleMethod = javaClass.declaredMethods.firstOrNull { m ->
                    m.name == methodName && 
                    m.parameterCount == 1 &&
                    m.parameterTypes[0].isAssignableFrom(eventClass.java)
                } ?: continue
                
                handleMethod.isAccessible = true
                handleMethod.invoke(listener, event)
            } catch (e: Exception) {
                // Methode existiert nicht oder Fehler beim Aufrufen
                // Silently ignore
            }
        }
    }
    
    /**
     * Dispache Pre-Event und gebe result zurück
     */
    fun <T : RegionEvent> dispatchCancellable(event: T): T {
        dispatch(event)
        return event
    }
}
