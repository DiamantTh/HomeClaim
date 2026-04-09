package systems.diath.homeclaim.core.store

import systems.diath.homeclaim.core.model.PlayerId
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionRole
import systems.diath.homeclaim.core.service.RoleService
import javax.sql.DataSource

class JdbcRoleRepository(
    private val dataSource: DataSource
) : RoleService {
    override fun setRole(regionId: RegionId, playerId: PlayerId, role: RegionRole) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "DELETE FROM region_roles WHERE region_id = ? AND player_id = ?"
            ).apply {
                setObject(1, regionId.value)
                setObject(2, playerId)
            }.executeUpdate()

            conn.prepareStatement(
                "INSERT INTO region_roles (region_id, player_id, role) VALUES (?, ?, ?)"
            ).apply {
                setObject(1, regionId.value)
                setObject(2, playerId)
                setString(3, role.name)
            }.executeUpdate()
        }
    }

    override fun removeRole(regionId: RegionId, playerId: PlayerId, role: RegionRole) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "DELETE FROM region_roles WHERE region_id = ? AND player_id = ? AND role = ?"
            ).apply {
                setObject(1, regionId.value)
                setObject(2, playerId)
                setString(3, role.name)
            }.executeUpdate()
        }
    }
}
