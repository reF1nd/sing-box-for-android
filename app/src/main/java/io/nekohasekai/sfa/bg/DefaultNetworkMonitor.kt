package io.nekohasekai.sfa.bg

import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.util.Log
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.sfa.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val interfaceUpdateLock = Any()
    private val interfaceUpdateMutex = Mutex()
    private var interfaceUpdateJob: Job? = null

    @Volatile
    private var interfaceUpdateGeneration = 0L

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
        cancelInterfaceUpdate()
    }

    suspend fun require(): Network {
        val network = defaultNetwork
        if (network != null) {
            return network
        }
        return DefaultNetworkListener.get()
    }

    fun setListener(listener: InterfaceUpdateListener?): Job? {
        this.listener = listener
        if (listener == null) {
            cancelInterfaceUpdate()
            return null
        }
        val state = defaultNetworkState.get()
        return checkDefaultInterfaceUpdate(state.network, state.linkProperties)
    }

    private fun checkDefaultInterfaceUpdate(newNetwork: Network?, linkProperties: LinkProperties?): Job? {
        if (listener == null) return null
        return synchronized(interfaceUpdateLock) {
            val generation = ++interfaceUpdateGeneration
            interfaceUpdateJob?.cancel()
            if (newNetwork != null && linkProperties == null) {
                interfaceUpdateJob = null
                return@synchronized null
            }
            interfaceUpdateJob = monitorScope.launch {
                interfaceUpdateMutex.withLock {
                    if (generation != interfaceUpdateGeneration) return@withLock
                    val listener = listener ?: return@withLock
                    if (newNetwork == null) {
                        listener.updateDefaultInterface("", -1, false, false)
                        return@withLock
                    }

                    val interfaceName = linkProperties!!.interfaceName
                    repeat(10) {
                        if (generation != interfaceUpdateGeneration) return@withLock
                        val interfaceIndex =
                            try {
                                NetworkInterface.getByName(interfaceName).index
                            } catch (e: Exception) {
                                delay(100)
                                return@repeat
                            }
                        if (generation != interfaceUpdateGeneration) return@withLock
                        listener.updateDefaultInterface(interfaceName, interfaceIndex, false, false)
                        return@withLock
                    }
                    if (generation == interfaceUpdateGeneration) {
                        Log.w(TAG, "failed to resolve interface index for $interfaceName")
                    }
                }
            }
            interfaceUpdateJob
        }
    }

    private fun cancelInterfaceUpdate() {
        synchronized(interfaceUpdateLock) {
            interfaceUpdateGeneration++
            interfaceUpdateJob?.cancel()
            interfaceUpdateJob = null
        }
    }
}
