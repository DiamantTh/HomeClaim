/**
 * HomeClaim API Client
 * 
 * This module provides the public API for third-party plugins to extend HomeClaim.
 * It contains only interfaces and models, no implementation details.
 * 
 * Third-party plugins should:
 * 1. Add dependency: implementation("systems.diath.homeclaim:homeclaim-api-client:VERSION")
 * 2. Access registries: ComponentTypeRegistry, ActionTypeRegistry
 * 3. Listen to events: RegionEvent, LiftEvent subclasses
 * 4. Implement custom components/actions and register them
 * 
 * Example usage:
 * ```kotlin
 * ComponentTypeRegistry.registerType("CUSTOM_PAD", ComponentTypeDescriptor(
 *     displayName = "Custom Pad",
 *     description = "A custom component type"
 * ))
 * ```
 */
package systems.diath.homeclaim.client
