package systems.diath.homeclaim.core

import systems.diath.homeclaim.core.model.RegionRoles
import systems.diath.homeclaim.core.model.RegionRole
import systems.diath.homeclaim.core.model.PlayerId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class RegionRolesTest {
    private val owner: PlayerId = UUID.randomUUID()
    private val trusted: PlayerId = UUID.randomUUID()
    private val member: PlayerId = UUID.randomUUID()
    private val banned: PlayerId = UUID.randomUUID()
    private val visitor: PlayerId = UUID.randomUUID()

    @Test
    fun `resolve selects correct role order`() {
        val roles = RegionRoles(
            trusted = setOf(trusted),
            members = setOf(member),
            banned = setOf(banned)
        )

        assertEquals(RegionRole.OWNER, roles.resolve(owner, owner))
        assertEquals(RegionRole.BANNED, roles.resolve(banned, owner))
        assertEquals(RegionRole.TRUSTED, roles.resolve(trusted, owner))
        assertEquals(RegionRole.MEMBER, roles.resolve(member, owner))
        assertEquals(RegionRole.VISITOR, roles.resolve(visitor, owner))
    }
}
