package systems.diath.homeclaim.core.service

import systems.diath.homeclaim.core.policy.FlagProfile

interface FlagProfileService {
    fun getProfile(name: String): FlagProfile?
}
