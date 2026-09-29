package io.nekohasekai.sfa.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import go.Seq
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.MainActivity
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Alert
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.ktx.hasPermission
import io.nekohasekai.sfa.vendor.Vendor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class BoxService(private val service: Service, private val platformInterface: PlatformInterface) : CommandServerHandler {
    companion object {
        private const val PROFILE_UPDATE_INTERVAL = 15L * 60 * 1000 // 15 minutes in milliseconds
        private const val TAG = "BoxService"

        @Volatile
        private var activeService: BoxService? = null

        @OptIn(DelicateCoroutinesApi::class)
        fun start() = GlobalScope.launch(Dispatchers.Main.immediate) {
            Settings.dataStore.initialize()
            val intent = Intent(Application.application, Settings.serviceClass())
            ContextCompat.startForegroundService(Application.application, intent)
        }

        fun stop() {
            Application.application.sendBroadcast(
                Intent(Action.SERVICE_CLOSE).setPackage(
                    Application.application.packageName,
                ),
            )
        }

        suspend fun stopAndWait() {
            val commandSocket = File(Application.application.filesDir, "command.sock")
            if (activeService == null && !commandSocket.exists()) return
            stop()
            repeat(20) {
                delay(100)
                if (activeService == null && !commandSocket.exists()) return
            }
            error(Application.application.getString(R.string.error_stop_vpn_timeout))
        }
    }

    var fileDescriptor: ParcelFileDescriptor? = null

    private val status = MutableLiveData(Status.Stopped)
    private val binder = ServiceBinder(status)
    private val notification = ServiceNotification(status, service)
    private var commandServerInstance: CommandServer? = null
    private val commandServer: CommandServer
        get() = checkNotNull(commandServerInstance)

    @Volatile
    private var commandServerReady = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startup = ServiceStartTask(serviceScope)
    private var stopJob: Job? = null
    private var shutdownComplete = false
    private var destroyed = false
    private val idleModeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idleModeUpdates = Channel<Boolean>(Channel.UNLIMITED)

    init {
        idleModeScope.launch {
            for (idle in idleModeUpdates) {
                if (!commandServerReady) continue
                val commandServer = commandServerInstance ?: continue
                if (idle) {
                    commandServer.pause()
                } else {
                    commandServer.wake()
                }
            }
        }
    }

    private var receiverRegistered = false
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Action.SERVICE_CLOSE -> {
                        stopService()
                    }

                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            serviceUpdateIdleMode()
                        }
                    }
                }
            }
        }

    private suspend fun startCommandServer() {
        currentCoroutineContext().ensureActive()
        Libbox.promoteOOMDraft()
        Libbox.promotePowerReportDraft()
        currentCoroutineContext().ensureActive()
        val commandServer = CommandServer(this, platformInterface)
        // Retain ownership even if start() fails or finishes after cancellation.
        commandServerInstance = commandServer
        currentCoroutineContext().ensureActive()
        commandServer.start()
        currentCoroutineContext().ensureActive()
        commandServerReady = true
    }

    private var lastProfileName = ""

    private suspend fun startService() {
        try {
            val selectedProfileId = Settings.selectedProfile
            if (selectedProfileId == -1L) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            val profile = ProfileManager.get(selectedProfileId)
            if (profile == null) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            val content = File(profile.typed.path).readText()
            currentCoroutineContext().ensureActive()
            if (content.isBlank()) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            lastProfileName = profile.name
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_starting)
            }

            DefaultNetworkMonitor.start()
            currentCoroutineContext().ensureActive()

            try {
                commandServer.startOrReloadService(
                    content,
                    OverrideOptions().apply {
                        autoRedirect = Settings.autoRedirect
                        if (Vendor.isPerAppProxyAvailable() && Settings.perAppProxyEnabled) {
                            val appList = Settings.getEffectivePerAppProxyList()
                            if (Settings.getEffectivePerAppProxyMode() == Settings.PER_APP_PROXY_INCLUDE) {
                                includePackage =
                                    PlatformInterfaceWrapper.StringArray((appList + Application.application.packageName).iterator())
                            } else {
                                excludePackage =
                                    PlatformInterfaceWrapper.StringArray((appList - Application.application.packageName).iterator())
                            }
                        }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                stopAndAlert(Alert.CreateService, e.message)
                return
            }

            currentCoroutineContext().ensureActive()
            if (commandServer.needWIFIState()) {
                val wifiPermission =
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    } else {
                        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    }
                if (!service.hasPermission(wifiPermission)) {
                    stopAndAlert(Alert.RequestLocationPermission)
                    return
                }
            }

            notification.start()
            withContext(Dispatchers.Main) {
                status.value = Status.Started
                notification.show(lastProfileName, R.string.status_started)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            stopAndAlert(Alert.StartService, e.message)
            return
        }
    }

    override fun serviceStop() {
        notification.close()
        status.postValue(Status.Starting)
        val pfd = fileDescriptor
        if (pfd != null) {
            pfd.close()
            fileDescriptor = null
        }
        closeService()
    }

    override fun serviceReload() {
        runBlocking {
            serviceReload0()
        }
    }

    suspend fun serviceReload0() {
        val selectedProfileId = Settings.selectedProfile
        if (selectedProfileId == -1L) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }

        val profile = ProfileManager.get(selectedProfileId)
        if (profile == null) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }

        val content = File(profile.typed.path).readText()
        if (content.isBlank()) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }
        lastProfileName = profile.name
        try {
            commandServer.startOrReloadService(
                content,
                OverrideOptions().apply {
                    autoRedirect = Settings.autoRedirect
                    if (Vendor.isPerAppProxyAvailable() && Settings.perAppProxyEnabled) {
                        val appList = Settings.getEffectivePerAppProxyList()
                        if (Settings.getEffectivePerAppProxyMode() == Settings.PER_APP_PROXY_INCLUDE) {
                            includePackage = PlatformInterfaceWrapper.StringArray((appList + Application.application.packageName).iterator())
                        } else {
                            excludePackage = PlatformInterfaceWrapper.StringArray((appList - Application.application.packageName).iterator())
                        }
                    }
                },
            )
        } catch (e: Exception) {
            stopAndAlert(Alert.CreateService, e.message)
            return
        }

        if (commandServer.needWIFIState()) {
            val wifiPermission =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                } else {
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                }
            if (!service.hasPermission(wifiPermission)) {
                stopAndAlert(Alert.RequestLocationPermission)
                return
            }
        }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus? {
        val status = SystemProxyStatus()
        if (service is VPNService) {
            status.available = service.systemProxyAvailable
            status.enabled = service.systemProxyEnabled
        }
        return status
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) {
        serviceReload()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun serviceUpdateIdleMode() {
        if (commandServerReady) {
            idleModeUpdates.trySend(Application.powerManager.isDeviceIdleMode)
        }
    }

    private fun stopService() {
        requestStop()
    }

    private fun closeService() {
        val commandServer = commandServerInstance ?: return
        runCatching {
            commandServer.closeService()
        }.onFailure {
            commandServer.setError("android: close service: ${it.message}")
        }
    }

    private fun stopAndAlert(type: Alert, message: String? = null) {
        requestStop(type, message)
    }

    private fun requestStop(type: Alert? = null, message: String? = null, clearStartedByUser: Boolean = true) {
        serviceScope.launch(Dispatchers.Main.immediate) {
            if (stopJob?.isActive == true) return@launch
            if (!destroyed && status.value == Status.Stopped && type == null) return@launch
            status.value = Status.Stopping
            val startupJob = if (destroyed) startup.destroy() else startup.cancel()
            if (receiverRegistered) {
                service.unregisterReceiver(receiver)
                receiverRegistered = false
            }
            notification.close()
            if (type != null && !destroyed) {
                binder.broadcast { callback ->
                    callback.onServiceAlert(type.ordinal, message)
                }
            }
            // Native calls are not cancellable. Wait for the startup job to
            // return before closing any resources it may still be creating.
            stopJob = serviceScope.launch(start = CoroutineStart.LAZY) {
                try {
                    startupJob?.join()
                    releaseResources()
                    if (clearStartedByUser) {
                        cleanup("persist service stop") {
                            Settings.startedByUser = false
                            Settings.dataStore.flush()
                        }
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        shutdownComplete = true
                        stopJob = null
                        notification.close()
                        if (activeService === this@BoxService) activeService = null
                        if (!destroyed) {
                            status.value = Status.Stopped
                            service.stopSelf()
                        } else {
                            serviceScope.cancel()
                        }
                    }
                }
            }
            stopJob?.start()
        }
    }

    private suspend fun releaseResources() {
        commandServerReady = false
        val pfd = fileDescriptor
        fileDescriptor = null
        cleanup("close TUN") { pfd?.close() }
        val server = commandServerInstance
        if (server != null) {
            cleanup("stop network monitor") { DefaultNetworkMonitor.stop() }
            cleanup("close core service") { server.closeService() }
            cleanup("close command server") { server.close() }
            commandServerInstance = null
            cleanup("promote power report draft") { Libbox.promotePowerReportDraft() }
            cleanup("refresh power reports") { PowerReportManager.refresh() }
        }
    }

    private suspend fun cleanup(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, name, e)
        }
    }

    @Suppress("SameReturnValue")
    internal fun onStartCommand(): Int {
        if (destroyed) {
            service.stopSelf()
            return Service.START_NOT_STICKY
        }

        // Promote before dispatching any initialization or command-server I/O.
        // Repeated starts during shutdown also carry a foreground obligation.
        try {
            notification.show(
                lastProfileName,
                when (status.value) {
                    Status.Started -> R.string.status_started
                    Status.Stopping -> R.string.status_stopping
                    else -> R.string.status_starting
                },
            )
        } catch (e: Exception) {
            stopAndAlert(Alert.StartService, e.message)
            service.stopSelf()
            return Service.START_NOT_STICKY
        }
        if (status.value != Status.Stopped) return Service.START_NOT_STICKY
        status.value = Status.Starting
        shutdownComplete = false
        activeService = this

        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                service,
                receiver,
                IntentFilter().apply {
                    addAction(Action.SERVICE_CLOSE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    }
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }

        startup.start(
            initialize = { Application.application.awaitLibboxInitialization() },
            startService = {
                Settings.startedByUser = true
                try {
                    startCommandServer()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    stopAndAlert(Alert.StartCommandServer, e.message)
                    return@start
                }
                startService()
            },
            onFailure = { stopAndAlert(Alert.StartService, it.message) },
        )
        return Service.START_NOT_STICKY
    }

    internal fun onBind(): IBinder = binder

    internal fun onDestroy() {
        val wasRunning = status.value != Status.Stopped || commandServerInstance != null
        destroyed = true
        startup.destroy()
        idleModeUpdates.cancel()
        idleModeScope.cancel()
        if (receiverRegistered) {
            service.unregisterReceiver(receiver)
            receiverRegistered = false
        }
        notification.close()
        if (shutdownComplete || !wasRunning) {
            if (activeService === this) activeService = null
            serviceScope.cancel()
        } else {
            // Destruction alone must not disable the user's boot-start setting.
            requestStop(clearStartedByUser = false)
        }
        binder.close()
    }

    internal fun onRevoke() {
        stopService()
    }

    internal fun sendNotification(notification: Notification) {
        val channel = "notification-${notification.typeID}"
        val builder =
            NotificationCompat.Builder(service, channel).setShowWhen(false)
                .setContentTitle(notification.title).setContentText(notification.body)
                .setOnlyAlertOnce(true).setSmallIcon(R.drawable.ic_menu)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
        if (!notification.subtitle.isNullOrBlank()) {
            builder.setContentInfo(notification.subtitle)
        }
        if (!notification.openURL.isNullOrBlank()) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    Intent(
                        service,
                        MainActivity::class.java,
                    ).apply {
                        setAction(Action.OPEN_URL).setData(Uri.parse(notification.openURL))
                        setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    },
                    ServiceNotification.flags,
                ),
            )
        }
        GlobalScope.launch(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Application.notification.createNotificationChannel(
                    NotificationChannel(
                        channel,
                        notification.typeName,
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            Application.notification.notify(notification.identifier, notification.typeID, builder.build())
        }
    }

    internal fun cancelNotification(identifier: String, typeID: Int) {
        GlobalScope.launch(Dispatchers.Main) {
            Application.notification.cancel(identifier, typeID)
        }
    }

    override fun triggerNativeCrash() {
        Thread {
            Thread.sleep(200)
            throw RuntimeException("debug native crash")
        }.start()
    }

    override fun writeDebugMessage(message: String?) {
        Log.d("sing-box", message!!)
    }

    override fun connectSSHAgent(): Int = -1
}
