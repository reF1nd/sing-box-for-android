package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultNetworkListenerStateTest {
    private data class Event(
        val network: String?,
        val linkProperties: String?,
        val capabilities: String?,
    )

    private val wifiUnmetered =
        DefaultNetworkCapabilityFlags.maskOf(
            hasWifi = true,
            hasCellular = false,
            hasEthernet = false,
            hasInternet = true,
            isMetered = false,
        )

    private val wifiMetered =
        DefaultNetworkCapabilityFlags.maskOf(
            hasWifi = true,
            hasCellular = false,
            hasEthernet = false,
            hasInternet = true,
            isMetered = true,
        )

    @Test
    fun standardCallbackOrderRefreshesOnceAndSuppressesIdenticalCapabilities() {
        val state = DefaultNetworkListenerState<String, String, String>()
        val events = mutableListOf<Event>()

        assertTrue(state.start("monitor") { net, lp, nc -> events += Event(net, lp, nc) })
        assertTrue(state.onAvailable("net-wifi"))
        state.onCapabilitiesChanged("net-wifi", "nc-rssi-55", wifiUnmetered)
        state.onLinkPropertiesChanged("net-wifi", "wlan0-v4")
        // Repeated RSSI / bandwidth updates produce the same tracked capability mask.
        state.onCapabilitiesChanged("net-wifi", "nc-rssi-61", wifiUnmetered)
        state.onCapabilitiesChanged("net-wifi", "nc-rssi-58", wifiUnmetered)

        assertEquals(
            listOf(
                Event("net-wifi", null, null),
                Event("net-wifi", "wlan0-v4", "nc-rssi-55"),
            ),
            events,
        )
        assertEquals("nc-rssi-58", state.capabilities)
    }

    @Test
    fun capabilityUpdateAfterLinkPropertiesRefreshesOnceIncludingZeroMask() {
        val state = DefaultNetworkListenerState<String, String, String>()
        val events = mutableListOf<Event>()

        assertTrue(state.start("monitor") { net, lp, nc -> events += Event(net, lp, nc) })
        assertTrue(state.onAvailable("net-other"))
        state.onLinkPropertiesChanged("net-other", "other0")
        val zeroMask =
            DefaultNetworkCapabilityFlags.maskOf(
                hasWifi = false,
                hasCellular = false,
                hasEthernet = false,
                hasInternet = false,
                isMetered = false,
            )
        assertEquals(0, zeroMask)
        // First capability callback arrives after LP with all tracked flags clear (mask = 0).
        state.onCapabilitiesChanged("net-other", "nc-zero-1", zeroMask)
        // Subsequent identical zero-mask callbacks must be suppressed.
        state.onCapabilitiesChanged("net-other", "nc-zero-2", zeroMask)

        assertEquals(
            listOf(
                Event("net-other", null, null),
                Event("net-other", "other0", null),
                Event("net-other", "other0", "nc-zero-1"),
            ),
            events,
        )
    }

    @Test
    fun meteredFlipDeliversUpdatedCapabilitySnapshotInBothDirections() {
        val state = DefaultNetworkListenerState<String, String, String>()
        val events = mutableListOf<Event>()

        assertTrue(state.start("monitor") { net, lp, nc -> events += Event(net, lp, nc) })
        assertTrue(state.onAvailable("net-wifi"))
        state.onCapabilitiesChanged("net-wifi", "nc-unmetered-1", wifiUnmetered)
        state.onLinkPropertiesChanged("net-wifi", "wlan0")
        state.onCapabilitiesChanged("net-wifi", "nc-unmetered-2", wifiUnmetered)
        // Flip unmetered -> metered.
        state.onCapabilitiesChanged("net-wifi", "nc-metered-1", wifiMetered)
        state.onCapabilitiesChanged("net-wifi", "nc-metered-2", wifiMetered)
        // Flip metered -> unmetered.
        state.onCapabilitiesChanged("net-wifi", "nc-unmetered-3", wifiUnmetered)
        state.onCapabilitiesChanged("net-wifi", "nc-unmetered-4", wifiUnmetered)

        assertEquals(
            listOf(
                Event("net-wifi", null, null),
                Event("net-wifi", "wlan0", "nc-unmetered-1"),
                Event("net-wifi", "wlan0", "nc-metered-1"),
                Event("net-wifi", "wlan0", "nc-unmetered-3"),
            ),
            events,
        )
    }

    @Test
    fun preApi26InitialLinkPropertiesSnapshotTriggersInitialRefreshAndStillRefreshesOnCallbacks() {
        val state = DefaultNetworkListenerState<String, String, String>()
        val events = mutableListOf<Event>()

        assertTrue(state.start("monitor") { net, lp, nc -> events += Event(net, lp, nc) })
        assertTrue(state.onAvailable("net-legacy", initialLinkProperties = "wlan0-initial"))
        // Even if no initial onLinkPropertiesChanged arrives on API 21-25, Put already emitted LP.
        state.onCapabilitiesChanged("net-legacy", "nc-wifi-1", wifiUnmetered)
        state.onCapabilitiesChanged("net-legacy", "nc-wifi-2", wifiUnmetered)
        // Real onLinkPropertiesChanged later still refreshes with preserved capabilities snapshot.
        state.onLinkPropertiesChanged("net-legacy", "wlan0-v6")

        assertEquals(
            listOf(
                Event("net-legacy", "wlan0-initial", null),
                Event("net-legacy", "wlan0-initial", "nc-wifi-1"),
                Event("net-legacy", "wlan0-v6", "nc-wifi-2"),
            ),
            events,
        )
    }

    @Test
    fun multipleCapabilityChangesBeforeLinkPropertiesKeepOnlyLatestBaseline() {
        val state = DefaultNetworkListenerState<String, String, String>()
        val events = mutableListOf<Event>()

        assertTrue(state.start("monitor") { net, lp, nc -> events += Event(net, lp, nc) })
        assertTrue(state.onAvailable("net-wifi"))
        state.onCapabilitiesChanged("net-wifi", "nc-unmetered", wifiUnmetered)
        state.onCapabilitiesChanged("net-wifi", "nc-metered", wifiMetered)
        state.onLinkPropertiesChanged("net-wifi", "wlan0-v4")
        // Same as latest pre-LP baseline: no redundant refresh.
        state.onCapabilitiesChanged("net-wifi", "nc-metered-2", wifiMetered)
        // Transition back to unmetered must notify with the new capability snapshot.
        state.onCapabilitiesChanged("net-wifi", "nc-unmetered-2", wifiUnmetered)

        assertEquals(
            listOf(
                Event("net-wifi", null, null),
                Event("net-wifi", "wlan0-v4", "nc-metered"),
                Event("net-wifi", "wlan0-v4", "nc-unmetered-2"),
            ),
            events,
        )
    }

    @Test
    fun maskOfEncodesEachTrackedBooleanIndependentlyAndPreservesMultiTransportCombinations() {
        data class BitCase(
            val name: String,
            val expectedFlag: Int,
            val mask: Int,
        )

        val cases =
            listOf(
                BitCase(
                    "wifi",
                    DefaultNetworkCapabilityFlags.TRANSPORT_WIFI,
                    DefaultNetworkCapabilityFlags.maskOf(
                        hasWifi = true,
                        hasCellular = false,
                        hasEthernet = false,
                        hasInternet = false,
                        isMetered = false,
                    ),
                ),
                BitCase(
                    "cellular",
                    DefaultNetworkCapabilityFlags.TRANSPORT_CELLULAR,
                    DefaultNetworkCapabilityFlags.maskOf(
                        hasWifi = false,
                        hasCellular = true,
                        hasEthernet = false,
                        hasInternet = false,
                        isMetered = false,
                    ),
                ),
                BitCase(
                    "ethernet",
                    DefaultNetworkCapabilityFlags.TRANSPORT_ETHERNET,
                    DefaultNetworkCapabilityFlags.maskOf(
                        hasWifi = false,
                        hasCellular = false,
                        hasEthernet = true,
                        hasInternet = false,
                        isMetered = false,
                    ),
                ),
                BitCase(
                    "internet",
                    DefaultNetworkCapabilityFlags.CAPABILITY_INTERNET,
                    DefaultNetworkCapabilityFlags.maskOf(
                        hasWifi = false,
                        hasCellular = false,
                        hasEthernet = false,
                        hasInternet = true,
                        isMetered = false,
                    ),
                ),
                BitCase(
                    "metered",
                    DefaultNetworkCapabilityFlags.CAPABILITY_METERED,
                    DefaultNetworkCapabilityFlags.maskOf(
                        hasWifi = false,
                        hasCellular = false,
                        hasEthernet = false,
                        hasInternet = false,
                        isMetered = true,
                    ),
                ),
            )

        assertEquals(cases.size, cases.map { it.mask }.distinct().size)
        for (bitCase in cases) {
            assertNotEquals(bitCase.name, 0, bitCase.mask)
            assertEquals(bitCase.name, bitCase.expectedFlag, bitCase.mask)
        }

        val state = DefaultNetworkListenerState<String, String, String>()
        val events = mutableListOf<Event>()
        assertTrue(state.start("monitor") { net, lp, nc -> events += Event(net, lp, nc) })
        assertTrue(state.onAvailable("net-1"))
        val zeroMask =
            DefaultNetworkCapabilityFlags.maskOf(
                hasWifi = false,
                hasCellular = false,
                hasEthernet = false,
                hasInternet = false,
                isMetered = false,
            )
        state.onCapabilitiesChanged("net-1", "nc-0", zeroMask)
        state.onLinkPropertiesChanged("net-1", "iface0")
        events.clear()

        for (bitCase in cases) {
            val beforeSet = events.size
            state.onCapabilitiesChanged("net-1", "nc-${bitCase.name}-on", bitCase.mask)
            assertEquals("set ${bitCase.name}", beforeSet + 1, events.size)
            assertEquals(Event("net-1", "iface0", "nc-${bitCase.name}-on"), events.last())

            state.onCapabilitiesChanged("net-1", "nc-${bitCase.name}-dup", bitCase.mask)
            assertEquals("repeat ${bitCase.name}", beforeSet + 1, events.size)

            state.onCapabilitiesChanged("net-1", "nc-${bitCase.name}-off", zeroMask)
            assertEquals("clear ${bitCase.name}", beforeSet + 2, events.size)
            assertEquals(Event("net-1", "iface0", "nc-${bitCase.name}-off"), events.last())

            state.onCapabilitiesChanged("net-1", "nc-${bitCase.name}-off-dup", zeroMask)
            assertEquals("repeat clear ${bitCase.name}", beforeSet + 2, events.size)
        }

        val wifiOnly =
            DefaultNetworkCapabilityFlags.maskOf(
                hasWifi = true,
                hasCellular = false,
                hasEthernet = false,
                hasInternet = true,
                isMetered = false,
            )
        val wifiAndCellular =
            DefaultNetworkCapabilityFlags.maskOf(
                hasWifi = true,
                hasCellular = true,
                hasEthernet = false,
                hasInternet = true,
                isMetered = false,
            )
        val allTransportsMetered =
            DefaultNetworkCapabilityFlags.maskOf(
                hasWifi = true,
                hasCellular = true,
                hasEthernet = true,
                hasInternet = true,
                isMetered = true,
            )
        assertNotEquals(wifiOnly, wifiAndCellular)
        assertNotEquals(wifiAndCellular, allTransportsMetered)
        assertEquals(
            DefaultNetworkCapabilityFlags.TRANSPORT_WIFI or
                DefaultNetworkCapabilityFlags.TRANSPORT_CELLULAR or
                DefaultNetworkCapabilityFlags.CAPABILITY_INTERNET,
            wifiAndCellular,
        )

        val beforeMulti = events.size
        state.onCapabilitiesChanged("net-1", "nc-wifi", wifiOnly)
        assertEquals(beforeMulti + 1, events.size)
        state.onCapabilitiesChanged("net-1", "nc-wifi-cell", wifiAndCellular)
        assertEquals(beforeMulti + 2, events.size)
        state.onCapabilitiesChanged("net-1", "nc-wifi-cell-dup", wifiAndCellular)
        assertEquals(beforeMulti + 2, events.size)
        state.onCapabilitiesChanged("net-1", "nc-all", allTransportsMetered)
        assertEquals(beforeMulti + 3, events.size)
        assertEquals(Event("net-1", "iface0", "nc-all"), events.last())
    }

    @Test
    fun subsequentLinkPropertiesAlwaysRefreshEvenWhenCapabilityMaskUnchanged() {
        val state = DefaultNetworkListenerState<String, String, String>()
        val events = mutableListOf<Event>()

        assertTrue(state.start("monitor") { net, lp, nc -> events += Event(net, lp, nc) })
        assertTrue(state.onAvailable("net-wifi"))
        state.onCapabilitiesChanged("net-wifi", "nc-wifi", wifiUnmetered)
        state.onLinkPropertiesChanged("net-wifi", "wlan0-v4-only")
        // Late IPv6 SLAAC + IPv6 DNS arrives with unchanged capabilities.
        state.onLinkPropertiesChanged("net-wifi", "wlan0-v4-and-v6")
        state.onCapabilitiesChanged("net-wifi", "nc-wifi-rssi", wifiUnmetered)

        assertEquals(
            listOf(
                Event("net-wifi", null, null),
                Event("net-wifi", "wlan0-v4-only", "nc-wifi"),
                Event("net-wifi", "wlan0-v4-and-v6", "nc-wifi"),
            ),
            events,
        )
    }

    @Test
    fun networkSwitchIgnoresStaleCallbacksAndResetsCapabilityBaseline() {
        val state = DefaultNetworkListenerState<String, String, String>()
        val events = mutableListOf<Event>()

        assertTrue(state.start("monitor") { net, lp, nc -> events += Event(net, lp, nc) })
        assertTrue(state.onAvailable("net-1"))
        state.onCapabilitiesChanged("net-1", "nc-1", wifiUnmetered)
        state.onLinkPropertiesChanged("net-1", "wlan0-1")

        assertTrue(state.onAvailable("net-2"))
        // Late callbacks from net-1 must be ignored.
        state.onCapabilitiesChanged("net-1", "nc-1-stale", wifiMetered)
        state.onLinkPropertiesChanged("net-1", "wlan0-stale")
        state.onLost("net-1")
        assertEquals("net-2", state.network)
        assertNull(state.linkProperties)
        assertNull(state.capabilities)
        assertNull(state.capabilitiesMask)

        state.onLinkPropertiesChanged("net-2", "rmnet0")
        // Same mask value as net-1 still triggers once because net-2 had no capability baseline yet.
        state.onCapabilitiesChanged("net-2", "nc-2", wifiUnmetered)

        assertEquals(
            listOf(
                Event("net-1", null, null),
                Event("net-1", "wlan0-1", "nc-1"),
                Event("net-2", null, null),
                Event("net-2", "rmnet0", null),
                Event("net-2", "rmnet0", "nc-2"),
            ),
            events,
        )
    }

    @Test
    fun lostAndReAvailableStartsFromCleanBaseline() {
        val state = DefaultNetworkListenerState<String, String, String>()
        val events = mutableListOf<Event>()

        assertTrue(state.start("monitor") { net, lp, nc -> events += Event(net, lp, nc) })
        assertTrue(state.onAvailable("net-1"))
        state.onCapabilitiesChanged("net-1", "nc-1", wifiUnmetered)
        state.onLinkPropertiesChanged("net-1", "wlan0")
        state.onLost("net-1")
        assertNull(state.network)
        assertNull(state.linkProperties)
        assertNull(state.capabilities)
        assertNull(state.capabilitiesMask)

        assertTrue(state.onAvailable("net-1"))
        state.onLinkPropertiesChanged("net-1", "wlan0-reconnected")
        state.onCapabilitiesChanged("net-1", "nc-2", wifiUnmetered)

        assertEquals(
            listOf(
                Event("net-1", null, null),
                Event("net-1", "wlan0", "nc-1"),
                Event(null, null, null),
                Event("net-1", null, null),
                Event("net-1", "wlan0-reconnected", null),
                Event("net-1", "wlan0-reconnected", "nc-2"),
            ),
            events,
        )
    }

    @Test
    fun listenerLifecycleHandlesReplayNonFinalStopAndLatePutAfterFinalStop() {
        val state = DefaultNetworkListenerState<String, String, String>()
        val firstEvents = mutableListOf<Event>()
        val secondEvents = mutableListOf<Event>()
        val thirdEvents = mutableListOf<Event>()

        assertTrue(state.start("first") { net, lp, nc -> firstEvents += Event(net, lp, nc) })
        assertTrue(state.onAvailable("net-1"))
        state.onCapabilitiesChanged("net-1", "nc-1", wifiUnmetered)

        // Second listener joins before LP: only it receives replay (net-1, null, nc-1); mask stays intact.
        assertFalse(state.start("second") { net, lp, nc -> secondEvents += Event(net, lp, nc) })
        assertEquals(listOf(Event("net-1", null, null)), firstEvents)
        assertEquals(listOf(Event("net-1", null, "nc-1")), secondEvents)
        assertEquals(wifiUnmetered, state.capabilitiesMask)

        state.onLinkPropertiesChanged("net-1", "wlan0")
        // Third listener joins after LP: only it receives replay (net-1, wlan0, nc-1).
        assertFalse(state.start("third") { net, lp, nc -> thirdEvents += Event(net, lp, nc) })
        assertEquals(listOf(Event("net-1", "wlan0", "nc-1")), thirdEvents)
        assertEquals(2, firstEvents.size)
        assertEquals(2, secondEvents.size)

        // Stopping unknown key or non-last listener preserves shared state.
        assertFalse(state.stop("unknown"))
        assertFalse(state.stop("third"))
        assertFalse(state.stop("second"))
        assertEquals("net-1", state.network)
        assertEquals("wlan0", state.linkProperties)
        assertEquals("nc-1", state.capabilities)
        assertEquals(wifiUnmetered, state.capabilitiesMask)

        // Stopping the final listener clears state and requests unregister.
        assertTrue(state.stop("first"))
        assertNull(state.network)
        assertNull(state.linkProperties)
        assertNull(state.capabilities)
        assertNull(state.capabilitiesMask)

        // Late onAvailable while unregistered is ignored so the next start does not replay stale state.
        assertFalse(state.onAvailable("net-stale"))
        val restartedEvents = mutableListOf<Event>()
        assertTrue(state.start("restarted") { net, lp, nc -> restartedEvents += Event(net, lp, nc) })
        assertTrue(restartedEvents.isEmpty())
    }
}
