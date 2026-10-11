package systems.diath.homeclaim.platform.paper

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import systems.diath.homeclaim.api.PlotRestServer
import systems.diath.homeclaim.core.store.InMemoryRegionStore
import java.net.InetAddress
import java.net.ServerSocket
import java.io.IOException

class PlotRestServerStartupTest {
    @Test
    fun `port bind failure releases engine resources so a later start can succeed`() {
        val occupiedPort = ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"))
        val port = occupiedPort.localPort
        val server = PlotRestServer(
            regionService = InMemoryRegionStore(),
            zoneService = null,
            componentService = null,
            adminService = null,
            port = port,
            authToken = "runtime-startup-test"
        )

        assertThrows(Exception::class.java) { server.start() }
        occupiedPort.close()

        val engine = server.start()
        try {
            assertThrows(IOException::class.java) {
                ServerSocket(port, 50, InetAddress.getByName("0.0.0.0")).use { }
            }
        } finally {
            engine.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
        }
    }
}
