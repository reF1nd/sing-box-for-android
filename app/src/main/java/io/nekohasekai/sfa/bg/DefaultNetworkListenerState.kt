package io.nekohasekai.sfa.bg

internal object DefaultNetworkCapabilityFlags {
    const val TRANSPORT_WIFI = 1 shl 0
    const val TRANSPORT_CELLULAR = 1 shl 1
    const val TRANSPORT_ETHERNET = 1 shl 2
    const val CAPABILITY_INTERNET = 1 shl 3
    const val CAPABILITY_METERED = 1 shl 4

    fun maskOf(
        hasWifi: Boolean,
        hasCellular: Boolean,
        hasEthernet: Boolean,
        hasInternet: Boolean,
        isMetered: Boolean,
    ): Int {
        var mask = 0
        if (hasWifi) mask = mask or TRANSPORT_WIFI
        if (hasCellular) mask = mask or TRANSPORT_CELLULAR
        if (hasEthernet) mask = mask or TRANSPORT_ETHERNET
        if (hasInternet) mask = mask or CAPABILITY_INTERNET
        if (isMetered) mask = mask or CAPABILITY_METERED
        return mask
    }
}

internal class DefaultNetworkListenerState<N : Any, LP : Any, NC : Any> {
    private val listeners = linkedMapOf<Any, (N?, LP?, NC?) -> Unit>()
    var network: N? = null
        private set
    var linkProperties: LP? = null
        private set
    var capabilities: NC? = null
        private set
    var capabilitiesMask: Int? = null
        private set

    val hasListeners: Boolean
        get() = listeners.isNotEmpty()

    fun start(key: Any, listener: (N?, LP?, NC?) -> Unit): Boolean {
        val shouldRegister = listeners.isEmpty()
        listeners[key] = listener
        val currentNetwork = network
        if (currentNetwork != null) {
            listener(currentNetwork, linkProperties, capabilities)
        }
        return shouldRegister
    }

    fun stop(key: Any): Boolean {
        if (listeners.isNotEmpty() && listeners.remove(key) != null && listeners.isEmpty()) {
            clearState()
            return true
        }
        return false
    }

    fun onAvailable(
        newNetwork: N,
        initialLinkProperties: LP? = null,
        beforeNotify: () -> Unit = {},
    ): Boolean {
        if (listeners.isEmpty()) {
            return false
        }
        network = newNetwork
        linkProperties = initialLinkProperties
        capabilities = null
        capabilitiesMask = null
        beforeNotify()
        notifyAll(newNetwork, initialLinkProperties, null)
        return true
    }

    fun onCapabilitiesChanged(targetNetwork: N, newCapabilities: NC, mask: Int) {
        if (network != targetNetwork) {
            return
        }
        val previousMask = capabilitiesMask
        capabilities = newCapabilities
        capabilitiesMask = mask
        val currentLinkProperties = linkProperties ?: return
        if (previousMask != mask) {
            notifyAll(network, currentLinkProperties, newCapabilities)
        }
    }

    fun onLinkPropertiesChanged(targetNetwork: N, newLinkProperties: LP) {
        if (network != targetNetwork) {
            return
        }
        linkProperties = newLinkProperties
        notifyAll(network, newLinkProperties, capabilities)
    }

    fun onLost(lostNetwork: N) {
        if (network != lostNetwork) {
            return
        }
        clearState()
        notifyAll(null, null, null)
    }

    private fun clearState() {
        network = null
        linkProperties = null
        capabilities = null
        capabilitiesMask = null
    }

    private fun notifyAll(
        currentNetwork: N?,
        currentLinkProperties: LP?,
        currentCapabilities: NC?,
    ) {
        listeners.values.forEach { it(currentNetwork, currentLinkProperties, currentCapabilities) }
    }
}
