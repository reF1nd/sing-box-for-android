package io.nekohasekai.sfa.bg

import java.net.Inet6Address
import java.net.InetAddress

internal class VPNRouteCompat(
    routeBypass: Boolean,
    allowBypass: Boolean,
    hasExcludeRoutes: Boolean,
) {
    // Android isolates fully routed, non-bypassable VPNs to their incoming
    // interface. Root-managed route sets need replies from physical interfaces.
    // Equivalent /1 routes avoid that /0 check without allowing apps to select
    // another network. Explicit excludeRoute entries already disable isolation;
    // splitting includes then could change their longest-prefix precedence.
    private val splitDefaultRoutes = routeBypass && !allowBypass && !hasExcludeRoutes

    fun addRoute(address: String, prefix: Int, addRoute: (String, Int) -> Unit) {
        if (!splitDefaultRoutes || prefix != 0) {
            addRoute(address, prefix)
        } else if (InetAddress.getByName(address) is Inet6Address) {
            addRoute("::", 1)
            addRoute("8000::", 1)
        } else {
            addRoute("0.0.0.0", 1)
            addRoute("128.0.0.0", 1)
        }
    }
}
