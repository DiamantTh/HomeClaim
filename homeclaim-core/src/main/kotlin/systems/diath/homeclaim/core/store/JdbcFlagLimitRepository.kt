package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.model.FlagKey
import systems.diath.homeclaim.core.model.FlagValue
import systems.diath.homeclaim.core.model.LimitKey
import systems.diath.homeclaim.core.model.LimitValue
import systems.diath.homeclaim.core.model.PolicyValue
import systems.diath.homeclaim.core.model.RegionId
import javax.sql.DataSource

class JdbcFlagLimitRepository(
    private val dataSource: DataSource
) {
    fun replaceFlags(regionId: RegionId, flags: Map<FlagKey, FlagValue>) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM region_flags WHERE region_id = ?").use {
                    it.setObject(1, regionId.value)
                    it.executeUpdate()
                }
                if (flags.isNotEmpty()) {
                    conn.prepareStatement("INSERT INTO region_flags (region_id, flag_key, flag_value) VALUES (?, ?, ?)").use { ps ->
                        flags.forEach { (key, value) ->
                            ps.setObject(1, regionId.value)
                            ps.setString(2, key.value)
                            ps.setString(3, Serialization.toJson(value))
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
                }
                conn.commit()
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun replaceLimits(regionId: RegionId, limits: Map<LimitKey, LimitValue>) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM region_limits WHERE region_id = ?").use {
                    it.setObject(1, regionId.value)
                    it.executeUpdate()
                }
                if (limits.isNotEmpty()) {
                    conn.prepareStatement("INSERT INTO region_limits (region_id, limit_key, limit_value) VALUES (?, ?, ?)").use { ps ->
                        limits.forEach { (key, value) ->
                            ps.setObject(1, regionId.value)
                            ps.setString(2, key.value)
                            ps.setString(3, Serialization.toJson(value))
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
                }
                conn.commit()
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun loadFlags(regionId: RegionId): Map<FlagKey, FlagValue> {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT flag_key, flag_value FROM region_flags WHERE region_id = ?").apply {
                setObject(1, regionId.value)
            }.executeQuery().use { rs ->
                val map = mutableMapOf<FlagKey, FlagValue>()
                while (rs.next()) {
                    val key = FlagKey(rs.getString("flag_key"))
                    val value: PolicyValue = Serialization.fromJson(rs.getString("flag_value"))
                    map[key] = value
                }
                return map
            }
        }
    }

    fun loadLimits(regionId: RegionId): Map<LimitKey, LimitValue> {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT limit_key, limit_value FROM region_limits WHERE region_id = ?").apply {
                setObject(1, regionId.value)
            }.executeQuery().use { rs ->
                val map = mutableMapOf<LimitKey, LimitValue>()
                while (rs.next()) {
                    val key = LimitKey(rs.getString("limit_key"))
                    val value: PolicyValue = Serialization.fromJson(rs.getString("limit_value"))
                    map[key] = value
                }
                return map
            }
        }
    }

    fun upsertFlag(regionId: RegionId, key: FlagKey, value: FlagValue) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM region_flags WHERE region_id = ? AND flag_key = ?").use {
                it.setObject(1, regionId.value)
                it.setString(2, key.value)
                it.executeUpdate()
            }
            conn.prepareStatement("INSERT INTO region_flags (region_id, flag_key, flag_value) VALUES (?, ?, ?)").use { ps ->
                ps.setObject(1, regionId.value)
                ps.setString(2, key.value)
                ps.setString(3, Serialization.toJson(value))
                ps.executeUpdate()
            }
        }
    }

    fun upsertLimit(regionId: RegionId, key: LimitKey, value: LimitValue) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM region_limits WHERE region_id = ? AND limit_key = ?").use {
                it.setObject(1, regionId.value)
                it.setString(2, key.value)
                it.executeUpdate()
            }
            conn.prepareStatement("INSERT INTO region_limits (region_id, limit_key, limit_value) VALUES (?, ?, ?)").use { ps ->
                ps.setObject(1, regionId.value)
                ps.setString(2, key.value)
                ps.setString(3, Serialization.toJson(value))
                ps.executeUpdate()
            }
        }
    }
}
