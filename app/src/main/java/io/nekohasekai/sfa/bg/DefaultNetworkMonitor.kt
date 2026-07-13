package io.nekohasekai.sfa.bg

import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.util.Log
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.sfa.Application
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicReference

object DefaultNetworkMonitor {
    private const val TAG = "DefaultNetworkMonitor"

    private data class DefaultNetworkState(
        val network: Network? = null,
        val linkProperties: LinkProperties? = null,
    )

    private val defaultNetworkState = AtomicReference(DefaultNetworkState())
    val defaultNetwork: Network?
        get() = defaultNetworkState.get().network

    @Volatile
    private var listener: InterfaceUpdateListener? = null

    suspend fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val currentNetwork = Application.connectivity.activeNetwork
            defaultNetworkState.set(
                DefaultNetworkState(
                    currentNetwork,
                    currentNetwork?.let(Application.connectivity::getLinkProperties),
                ),
            )
        }
        DefaultNetworkListener.start(this) { network, linkProperties ->
            defaultNetworkState.set(DefaultNetworkState(network, linkProperties))
            checkDefaultInterfaceUpdate(network, linkProperties)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            DefaultNetworkListener.get()
        }
    }

    suspend fun stop() {
        DefaultNetworkListener.stop(this)
        defaultNetworkState.set(DefaultNetworkState())
        listener = null
    }

    suspend fun require(): Network {
        val network = defaultNetwork
        if (network != null) {
            return network
        }
        return DefaultNetworkListener.get()
    }

    fun setListener(listener: InterfaceUpdateListener?) {
        this.listener = listener
        val state = defaultNetworkState.get()
        checkDefaultInterfaceUpdate(state.network, state.linkProperties)
    }

    private fun checkDefaultInterfaceUpdate(newNetwork: Network?, linkProperties: LinkProperties?) {
        val listener = listener ?: return
        if (newNetwork == null) {
            listener.updateDefaultInterface("", -1, false, false)
            return
        }
        val interfaceName = linkProperties?.interfaceName ?: return
        repeat(10) {
            val interfaceIndex =
                try {
                    NetworkInterface.getByName(interfaceName).index
                } catch (e: Exception) {
                    Thread.sleep(100)
                    return@repeat
                }
            listener.updateDefaultInterface(interfaceName, interfaceIndex, false, false)
            return
        }
        Log.w(TAG, "failed to resolve interface index for $interfaceName")
    }
}
