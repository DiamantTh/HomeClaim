package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.policy.FlagProfile
import systems.diath.homeclaim.core.service.FlagProfileService
import systems.diath.homeclaim.core.model.FlagKey
import systems.diath.homeclaim.core.model.FlagValue
import systems.diath.homeclaim.core.model.LimitKey
import systems.diath.homeclaim.core.model.LimitValue
import java.sql.ResultSet
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

class JdbcFlagProfileService(
    private val dataSource: DataSource
) : FlagProfileService {
    /** Named profiles can be consulted by policy evaluation, so reads must stay off event hot paths. */
    private val runtimeProfiles = AtomicReference<Map<String, FlagProfile>>(emptyMap())

    init {
        reloadRuntimeProfiles()
    }

    override fun getProfile(name: String): FlagProfile? = runtimeProfiles.get()[name]

    fun upsert(profile: FlagProfile) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM flag_profiles WHERE name = ?").use { ps ->
                    ps.setString(1, profile.name)
                    ps.executeUpdate()
                }
                conn.prepareStatement("INSERT INTO flag_profiles (name, flags, limits) VALUES (?, ?, ?)").use { ps ->
                    ps.setString(1, profile.name)
                    ps.setJsonString(2, Serialization.toJson(profile.flags.mapKeys { it.key.value }))
                    ps.setJsonString(3, Serialization.toJson(profile.limits.mapKeys { it.key.value }))
                    ps.executeUpdate()
                }
                conn.commit()
            } catch (failure: Throwable) {
                conn.rollback()
                throw failure
            } finally {
                conn.autoCommit = true
            }
        }
        runtimeProfiles.updateAndGet { current -> current + (profile.name to profile) }
    }

    fun listProfiles(): List<FlagProfile> = runtimeProfiles.get().values.toList()

    /** Reload after an explicitly managed out-of-band database edit; no TTL is used. */
    fun reloadRuntimeProfiles() {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT name, flags, limits FROM flag_profiles").use { statement ->
                statement.executeQuery().use { rs ->
                    val profiles = buildMap {
                        while (rs.next()) {
                            val profile = rs.toFlagProfile()
                            put(profile.name, profile)
                        }
                    }
                    runtimeProfiles.set(profiles)
                }
            }
        }
    }

    private fun ResultSet.toFlagProfile(): FlagProfile {
        val name = getString("name")
        val flags: Map<String, FlagValue> = Serialization.fromJson(getString("flags"))
        val limits: Map<String, LimitValue> = Serialization.fromJson(getString("limits"))
        return FlagProfile(
            name = name,
            flags = flags.mapKeys { FlagKey.trusted(it.key) },
            limits = limits.mapKeys { LimitKey.trusted(it.key) }
        )
    }
}
