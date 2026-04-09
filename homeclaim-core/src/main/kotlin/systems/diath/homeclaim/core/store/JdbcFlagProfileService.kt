package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.policy.FlagProfile
import systems.diath.homeclaim.core.service.FlagProfileService
import systems.diath.homeclaim.core.model.FlagKey
import systems.diath.homeclaim.core.model.LimitKey
import javax.sql.DataSource

class JdbcFlagProfileService(
    private val dataSource: DataSource
) : FlagProfileService {

    override fun getProfile(name: String): FlagProfile? {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT flags, limits FROM flag_profiles WHERE name = ?").apply {
                setString(1, name)
            }.executeQuery().use { rs ->
                if (!rs.next()) return null
                val flags: Map<String, Any?> = Serialization.fromJson(rs.getString("flags"))
                val limits: Map<String, Any?> = Serialization.fromJson(rs.getString("limits"))
                return FlagProfile(
                    name = name,
                    flags = flags.mapKeys { FlagKey.trusted(it.key) }.mapValues { Serialization.fromJson(it.value.toString()) },
                    limits = limits.mapKeys { LimitKey.trusted(it.key) }.mapValues { Serialization.fromJson(it.value.toString()) }
                )
            }
        }
    }

    fun upsert(profile: FlagProfile) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM flag_profiles WHERE name = ?").apply {
                setString(1, profile.name)
            }.executeUpdate()
                conn.prepareStatement("INSERT INTO flag_profiles (name, flags, limits) VALUES (?, ?, ?)").use { ps ->
                ps.setString(1, profile.name)
                ps.setString(2, Serialization.toJson(profile.flags.mapKeys { it.key.value }))
                ps.setString(3, Serialization.toJson(profile.limits.mapKeys { it.key.value }))
                ps.executeUpdate()
            }
        }
    }

    fun listProfiles(): List<FlagProfile> {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT name, flags, limits FROM flag_profiles").executeQuery().use { rs ->
                val profiles = mutableListOf<FlagProfile>()
                while (rs.next()) {
                    val name = rs.getString("name")
                    val flags: Map<String, Any?> = Serialization.fromJson(rs.getString("flags"))
                    val limits: Map<String, Any?> = Serialization.fromJson(rs.getString("limits"))
                    profiles += FlagProfile(
                        name = name,
                        flags = flags.mapKeys { FlagKey.trusted(it.key) }.mapValues { Serialization.fromJson(it.value.toString()) },
                        limits = limits.mapKeys { LimitKey.trusted(it.key) }.mapValues { Serialization.fromJson(it.value.toString()) }
                    )
                }
                return profiles
            }
        }
    }
}
