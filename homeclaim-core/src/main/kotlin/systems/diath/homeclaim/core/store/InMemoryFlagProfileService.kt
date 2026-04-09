package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.policy.FlagProfile
import systems.diath.homeclaim.core.service.FlagProfileService

class InMemoryFlagProfileService(
    profiles: Collection<FlagProfile> = emptyList()
) : FlagProfileService {
    private val byName = profiles.associateBy { it.name.lowercase() }.toMutableMap()

    override fun getProfile(name: String): FlagProfile? = byName[name.lowercase()]

    fun register(profile: FlagProfile) {
        byName[profile.name.lowercase()] = profile
    }

    fun getAll(): List<FlagProfile> = byName.values.toList()

    fun replaceAll(profiles: Collection<FlagProfile>) {
        byName.clear()
        profiles.forEach { byName[it.name.lowercase()] = it }
    }
}
