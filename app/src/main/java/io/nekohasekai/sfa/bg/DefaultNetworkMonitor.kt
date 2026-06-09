package io.nekohasekai.sfa.bg

import android.net.Network
import android.os.Build
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

object DefaultNetworkMonitor {

    @Volatile
    var defaultNetwork: Network? = null

    @Volatile
    private var listener: InterfaceUpdateListener? = null
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val interfaceUpdateLock = Any()
    private val interfaceUpdateMutex = Mutex()
    private var interfaceUpdateJob: Job? = null

    suspend fun start() {
        DefaultNetworkListener.start(this) {
            defaultNetwork = it
            checkDefaultInterfaceUpdate(it)
        }
        defaultNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Application.connectivity.activeNetwork
        } else {
            DefaultNetworkListener.get()
        }
    }

    suspend fun stop() {
        DefaultNetworkListener.stop(this)
        defaultNetwork = null
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
        return checkDefaultInterfaceUpdate(defaultNetwork)
    }

    private fun checkDefaultInterfaceUpdate(newNetwork: Network?): Job? {
        if (listener == null) return null
        return synchronized(interfaceUpdateLock) {
            interfaceUpdateJob?.cancel()
            interfaceUpdateJob = monitorScope.launch {
                interfaceUpdateMutex.withLock {
                    val listener = listener ?: return@withLock
                    if (newNetwork == null) {
                        listener.updateDefaultInterface("", -1, false, false)
                        return@withLock
                    }

                    repeat(10) {
                        val linkProperties = Application.connectivity.getLinkProperties(newNetwork)
                        if (linkProperties == null) {
                            delay(100)
                            return@repeat
                        }
                        val interfaceIndex =
                            try {
                                NetworkInterface.getByName(linkProperties.interfaceName).index
                            } catch (e: Exception) {
                                delay(100)
                                return@repeat
                            }
                        listener.updateDefaultInterface(linkProperties.interfaceName, interfaceIndex, false, false)
                        return@withLock
                    }
                }
            }
            interfaceUpdateJob
        }
    }

    private fun cancelInterfaceUpdate() {
        synchronized(interfaceUpdateLock) {
            interfaceUpdateJob?.cancel()
            interfaceUpdateJob = null
        }
    }
}
