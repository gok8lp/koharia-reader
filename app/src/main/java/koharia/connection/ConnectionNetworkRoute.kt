package koharia.connection

// Flugel: keep Android network interpretation pure so VPN/transport combinations can be tested on the JVM.
internal object ConnectionNetworkRoute {
    data class Network(
        val id: Any,
        val wifi: Boolean = false,
        val cellular: Boolean = false,
        val vpn: Boolean = false,
        val internet: Boolean = true,
        val interfaceName: String? = null,
    )

    private data class Route(val active: Any, val underlying: List<Any>, val interfaces: List<String?>)

    fun wifiNetwork(active: Network?, visible: List<Network>): Any? {
        active ?: return null
        if (!active.internet) return null
        if (!active.vpn) return active.takeIf { it.isWifi() }?.id
        // Public Android APIs expose inherited transports, not the VPN's hidden underlying-network
        // list. Never infer Wi-Fi merely because an unrelated Wi-Fi network is connected.
        if (!active.wifi || active.cellular) return null
        val physical = visible.singleOrNull { it.isWifi() && !it.interfaceName.isNullOrBlank() } ?: return null
        return Route(active.id, listOf(physical.id), listOf(physical.interfaceName))
    }

    private fun Network.isWifi() = internet && wifi && !cellular && !vpn
}
