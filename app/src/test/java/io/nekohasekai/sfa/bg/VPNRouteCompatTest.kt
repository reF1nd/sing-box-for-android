package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.net.InetAddress

class VPNRouteCompatTest {
    private data class Route(val address: String, val prefix: Int) {
        fun contains(target: String): Boolean {
            val network = InetAddress.getByName(address).address
            val candidate = InetAddress.getByName(target).address
            if (network.size != candidate.size) return false
            val shift = network.size * 8 - prefix
            return BigInteger(1, network).shiftRight(shift) == BigInteger(1, candidate).shiftRight(shift)
        }
    }

    private fun routes(
        input: List<Route>,
        routeBypass: Boolean = true,
        allowBypass: Boolean = false,
        hasExcludeRoutes: Boolean = false,
    ): List<Route> {
        val compat = VPNRouteCompat(routeBypass, allowBypass, hasExcludeRoutes)
        return buildList {
            input.forEach { route ->
                compat.addRoute(route.address, route.prefix) { address, prefix -> add(Route(address, prefix)) }
            }
        }
    }

    @Test
    fun defaultRoutesRetainFullDualStackCoverage() {
        val result = routes(listOf(Route("0.0.0.0", 0), Route("::", 0)))
        assertEquals(
            listOf(Route("0.0.0.0", 1), Route("128.0.0.0", 1), Route("::", 1), Route("8000::", 1)),
            result,
        )
        for (firstByte in 0..255) {
            for (address in listOf("$firstByte.0.0.0", "$firstByte.255.255.255", "${firstByte.toString(16)}00::", "${firstByte.toString(16)}ff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")) {
                assertEquals(address, 1, result.count { it.contains(address) })
            }
        }
    }

    @Test
    fun singleStackDoesNotEnableTheOtherFamily() {
        val ipv4 = routes(listOf(Route("0.0.0.0", 0)))
        val ipv6 = routes(listOf(Route("::", 0)))
        assertEquals(2, ipv4.size)
        assertEquals(2, ipv6.size)
        assertFalse(ipv4.any { it.contains("2001:db8::1") })
        assertFalse(ipv6.any { it.contains("192.0.2.1") })
        assertTrue(routes(emptyList()).isEmpty())
    }

    @Test
    fun explicitRouteAddressOnlySplitsDefaultEntries() {
        val input = listOf(Route("192.0.2.1", 24), Route("::", 0), Route("2001:db8::1", 48))
        assertEquals(
            listOf(input[0], Route("::", 1), Route("8000::", 1), input[2]),
            routes(input),
        )
        val partial = listOf(Route("10.0.0.0", 8), Route("2000::", 3))
        assertEquals(partial, routes(partial))
    }

    @Test
    fun defaultRoutesUseTheSameAddressFamilyAsVpnService() {
        // Java treats mapped IPv6 addresses as IPv4, including in IpPrefix.
        assertEquals(
            listOf(Route("0.0.0.0", 1), Route("128.0.0.0", 1)),
            routes(listOf(Route("::ffff:192.0.2.1", 0))),
        )
    }

    @Test
    fun ordinaryVpnAndAllowBypassKeepOriginalRoutes() {
        val input = listOf(Route("0.0.0.0", 0), Route("::", 0))
        assertEquals(input, routes(input, routeBypass = false))
        assertEquals(input, routes(input, allowBypass = true))
    }

    @Test
    fun modernExcludeRoutesKeepLongestPrefixPrecedence() {
        val include = listOf(Route("0.0.0.0", 0), Route("10.0.0.0", 8), Route("::", 0), Route("2001:db8::", 32))
        val result = routes(include, hasExcludeRoutes = true)
        assertEquals(include, result)
        // /0 and /1 exclusions would lose to newly introduced /1 includes.
        for (exclude in listOf(Route("0.0.0.0", 0), Route("0.0.0.0", 1), Route("::", 0), Route("::", 1))) {
            for (target in listOf("1.1.1.1", "10.1.2.3", "2001:db8::1", "2001:4860::1")) {
                fun included(routes: List<Route>): Boolean {
                    val longest = routes.filter { it.contains(target) }.maxOfOrNull { it.prefix } ?: -1
                    return longest >= 0 && (!exclude.contains(target) || longest > exclude.prefix)
                }
                assertEquals("$exclude $target", included(include), included(result))
            }
        }
    }

    @Test
    fun legacyComputedRouteRangesKeepExcludedAddressesOut() {
        // Example of the final ranges after sing-tun subtracts an IPv4 /2.
        // The remaining IPv6 /0 still needs splitting even with a configured
        // exclusion: older Android versions do not receive excludeRoute entries.
        val input = listOf(Route("64.0.0.0", 2), Route("128.0.0.0", 1), Route("::", 0))
        val result = routes(input)
        assertEquals(listOf(input[0], input[1], Route("::", 1), Route("8000::", 1)), result)
        assertFalse(result.any { it.contains("1.1.1.1") })
        assertTrue(result.any { it.contains("64.0.0.1") })
        assertTrue(result.any { it.contains("128.0.0.1") })
        assertTrue(result.any { it.contains("2001:db8::1") })
    }
}
