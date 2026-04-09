package systems.diath.homeclaim.core.policy

import systems.diath.homeclaim.core.model.FlagKey
import systems.diath.homeclaim.core.model.FlagValue
import systems.diath.homeclaim.core.model.LimitKey
import systems.diath.homeclaim.core.model.LimitValue

data class FlagProfile(
    val name: String,
    val flags: Map<FlagKey, FlagValue> = emptyMap(),
    val limits: Map<LimitKey, LimitValue> = emptyMap()
)
