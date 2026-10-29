package koharia.connection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

// Flugel: document physical transport selection independently of Android's final/stubbed network classes.
class ConnectionNetworkRouteTest {
    private val wifi = ConnectionNetworkRoute.Network("wifi", wifi = true, interfaceName = "wlan0")
    private val mobile = ConnectionNetworkRoute.Network("mobile", cellular = true, interfaceName = "rmnet0")
    private val vpn = ConnectionNetworkRoute.Network("tailscale", vpn = true, interfaceName = "tun0")

    @Test
    fun `Wi-Fi without VPN enables LAN`() {
        assertEquals("wifi", route(wifi))
    }

    @Test
    fun `mobile data without VPN uses public`() {
        assertNull(route(mobile))
    }

    @Test
    fun `Wi-Fi plus VPN confirms inherited transport against physical LinkProperties`() {
        assertNotNull(route(vpn.copy(wifi = true)))
    }

    @Test
    fun `mobile data plus VPN uses public even when unrelated Wi-Fi is visible`() {
        assertNull(route(vpn.copy(cellular = true)))
    }

    @Test
    fun `legacy VPN Wi-Fi hint needs a physical LinkProperties interface`() {
        assertNotNull(route(vpn.copy(wifi = true)))
        assertNull(ConnectionNetworkRoute.wifiNetwork(vpn.copy(wifi = true), listOf(wifi.copy(interfaceName = null))))
    }

    @Test
    fun `legacy mobile VPN and unknown or mixed underlying routes use public`() {
        assertNull(route(vpn.copy(cellular = true)))
        assertNull(route(vpn))
        assertNull(route(vpn.copy(wifi = true, cellular = true)))
        assertNull(ConnectionNetworkRoute.wifiNetwork(vpn.copy(wifi = true), emptyList()))
        assertNull(
            ConnectionNetworkRoute.wifiNetwork(vpn.copy(wifi = true), listOf(wifi, wifi.copy(id = "other-wifi"))),
        )
    }

    @Test
    fun `Wi-Fi or VPN network changes invalidate the cached LAN verification`() {
        assertNotEquals(
            route(vpn.copy(wifi = true)),
            ConnectionNetworkRoute.wifiNetwork(vpn.copy(wifi = true), listOf(wifi.copy(id = "other-wifi"))),
        )
        assertNotEquals(route(vpn.copy(wifi = true)), route(vpn.copy(id = "other-vpn", wifi = true)))
    }

    @Test
    fun `offline and missing active networks use public`() {
        assertNull(route(null))
        assertNull(route(wifi.copy(internet = false)))
        assertNull(route(vpn.copy(wifi = true, internet = false)))
    }

    private fun route(active: ConnectionNetworkRoute.Network?) =
        ConnectionNetworkRoute.wifiNetwork(active, listOf(wifi, mobile))
}
