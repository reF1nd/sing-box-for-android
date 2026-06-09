package io.nekohasekai.sfa.compose.screen.usbip

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.USBIPServerStatus
import io.nekohasekai.libbox.USBIPServerStatusHandler
import io.nekohasekai.libbox.USBIPServerStatusSubscription
import io.nekohasekai.libbox.USBIPServerStatusUpdate
import io.nekohasekai.libbox.USBSharedDevice
import io.nekohasekai.sfa.compose.base.BaseViewModel
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

data class UsbSharedInterfaceData(
    val interfaceClass: Int,
    val interfaceSubClass: Int,
    val interfaceProtocol: Int,
)

data class UsbSharedDeviceData(
    val busId: String,
    val stableId: String,
    val backend: Int,
    val state: Int,
    val deviceId: String,
    val busNum: Int,
    val devNum: Int,
    val speed: Int,
    val vendorId: Int,
    val productId: Int,
    val bcdDevice: Int,
    val deviceClass: Int,
    val deviceSubClass: Int,
    val deviceProtocol: Int,
    val configurationValue: Int,
    val numConfigurations: Int,
    val serial: String,
    val product: String,
    val interfaces: List<UsbSharedInterfaceData>,
) {
    val key: String get() = stableId.ifEmpty { busId }
}

data class UsbipServerData(
    val serverTag: String,
    val devices: List<UsbSharedDeviceData>,
)

data class USBIPStatusState(
    val servers: List<UsbipServerData> = emptyList(),
    val isSubscribed: Boolean = false,
)

class USBIPStatusViewModel : BaseViewModel<USBIPStatusState, Nothing>() {
    companion object {
        private const val MIN_API_VERSION_USBIP = 2
    }

    @Volatile
    private var subscription: USBIPServerStatusSubscription? = null

    @Volatile
    private var subscriptionGeneration = 0L

    private val routeActiveFlow = MutableStateFlow(false)
    private val serviceStatusFlow = MutableStateFlow(Status.Stopped)

    override fun createInitialState() = USBIPStatusState()

    init {
        viewModelScope.launch {
            combine(
                AppLifecycleObserver.isForeground,
                RemoteControlManager.remoteServer,
                routeActiveFlow,
                serviceStatusFlow,
            ) { foreground, remoteServer, routeActive, status ->
                SubscriptionTarget(
                    active = foreground && routeActive && (remoteServer != null || status == Status.Started),
                    remoteServerId = remoteServer?.id,
                )
            }.distinctUntilChanged().collect { target ->
                cancel()
                if (target.active) {
                    subscribe()
                }
            }
        }
    }

    private data class SubscriptionTarget(val active: Boolean, val remoteServerId: Long?)

    fun updateRouteState(active: Boolean, status: Status) {
        routeActiveFlow.value = active
        serviceStatusFlow.value = status
    }

    fun subscribe() {
        if (currentState.isSubscribed) return
        val generation = ++subscriptionGeneration
        updateState { copy(isSubscribed = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = CommandTarget.standaloneClient()
                if (client.getAPIVersion() < MIN_API_VERSION_USBIP) {
                    viewModelScope.launch {
                        if (!isCurrentSubscription(generation)) return@launch
                        updateState { copy(servers = emptyList(), isSubscribed = false) }
                        subscription = null
                    }
                    return@launch
                }
                val newSubscription =
                    client.subscribeUSBIPServerStatus(
                        object : USBIPServerStatusHandler {
                            override fun onStatusUpdate(status: USBIPServerStatusUpdate) {
                                val servers = convertUpdate(status)
                                viewModelScope.launch {
                                    if (!isCurrentSubscription(generation)) return@launch
                                    updateState { copy(servers = servers) }
                                }
                            }

                            override fun onError(message: String) {
                                viewModelScope.launch {
                                    if (!isCurrentSubscription(generation)) return@launch
                                    updateState { copy(servers = emptyList(), isSubscribed = false) }
                                    subscription = null
                                    sendErrorMessage(message)
                                }
                            }
                        },
                    )
                setCurrentSubscription(generation, newSubscription)
            } catch (_: Exception) {
                viewModelScope.launch {
                    if (!isCurrentSubscription(generation)) return@launch
                    updateState { copy(servers = emptyList(), isSubscribed = false) }
                    subscription = null
                }
            }
        }
    }

    private fun isCurrentSubscription(generation: Long): Boolean {
        return subscriptionGeneration == generation && currentState.isSubscribed
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun closeSubscription(currentSubscription: USBIPServerStatusSubscription) {
        GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                currentSubscription.close()
            }
        }
    }

    private fun closeSubscription() {
        val currentSubscription = subscription ?: return
        subscription = null
        closeSubscription(currentSubscription)
    }

    private fun setCurrentSubscription(
        generation: Long,
        newSubscription: USBIPServerStatusSubscription,
    ) {
        if (!isCurrentSubscription(generation)) {
            closeSubscription(newSubscription)
            return
        }
        subscription = newSubscription
        if (!isCurrentSubscription(generation)) {
            if (subscription === newSubscription) {
                subscription = null
            }
            closeSubscription(newSubscription)
        }
    }

    fun cancel() {
        subscriptionGeneration++
        closeSubscription()
        updateState { copy(servers = emptyList(), isSubscribed = false) }
    }

    fun server(tag: String): UsbipServerData? = currentState.servers.firstOrNull { it.serverTag == tag }

    override fun onCleared() {
        cancel()
        super.onCleared()
    }

    private fun convertUpdate(status: USBIPServerStatusUpdate): List<UsbipServerData> {
        val servers = mutableListOf<UsbipServerData>()
        val iterator = status.servers()
        while (iterator.hasNext()) {
            servers.add(convertServer(iterator.next()))
        }
        return servers
    }

    private fun convertServer(server: USBIPServerStatus): UsbipServerData {
        val devices = mutableListOf<UsbSharedDeviceData>()
        val iterator = server.devices()
        while (iterator.hasNext()) {
            devices.add(convertDevice(iterator.next()))
        }
        return UsbipServerData(serverTag = server.serverTag, devices = devices)
    }

    private fun convertDevice(device: USBSharedDevice): UsbSharedDeviceData {
        val interfaces = mutableListOf<UsbSharedInterfaceData>()
        val iterator = device.interfaces()
        while (iterator.hasNext()) {
            val iface = iterator.next()
            interfaces.add(
                UsbSharedInterfaceData(
                    interfaceClass = iface.interfaceClass,
                    interfaceSubClass = iface.interfaceSubClass,
                    interfaceProtocol = iface.interfaceProtocol,
                ),
            )
        }
        return UsbSharedDeviceData(
            busId = device.busID,
            stableId = device.stableID,
            backend = device.backend,
            state = device.state,
            deviceId = device.deviceID,
            busNum = device.busNum,
            devNum = device.devNum,
            speed = device.speed,
            vendorId = device.vendorID,
            productId = device.productID,
            bcdDevice = device.getBCDDevice(),
            deviceClass = device.deviceClass,
            deviceSubClass = device.deviceSubClass,
            deviceProtocol = device.deviceProtocol,
            configurationValue = device.configurationValue,
            numConfigurations = device.numConfigurations,
            serial = device.serial,
            product = device.product,
            interfaces = interfaces,
        )
    }
}
