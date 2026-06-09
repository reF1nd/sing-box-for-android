package io.nekohasekai.sfa.utils

import android.util.Log
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogEntry
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.sfa.ktx.toList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

open class CommandClient(
    private val scope: CoroutineScope,
    private val connectionTypes: List<ConnectionType>,
    private val handler: Handler,
) {
    constructor(
        scope: CoroutineScope,
        connectionType: ConnectionType,
        handler: Handler,
    ) : this(scope, listOf(connectionType), handler)

    private val additionalHandlers = mutableListOf<Handler>()
    private var cachedGroups: MutableList<OutboundGroup>? = null

    fun addHandler(handler: Handler) {
        synchronized(additionalHandlers) {
            if (!additionalHandlers.contains(handler)) {
                additionalHandlers.add(handler)
                cachedGroups?.let { groups ->
                    handler.updateGroups(groups)
                }
            }
        }
    }

    fun removeHandler(handler: Handler) {
        synchronized(additionalHandlers) {
            additionalHandlers.remove(handler)
        }
    }

    private fun getAllHandlers(): List<Handler> = synchronized(additionalHandlers) {
        listOf(handler) + additionalHandlers
    }

    enum class ConnectionType {
        Status,
        Groups,
        Log,
        ClashMode,
        Connections,
    }

    interface Handler {
        fun onConnected() {}

        fun onDisconnected() {}

        fun updateStatus(status: StatusMessage) {}

        fun setDefaultLogLevel(level: Int) {}

        fun clearLogs() {}

        fun appendLogs(message: List<LogEntry>) {}

        fun updateGroups(newGroups: MutableList<OutboundGroup>) {}

        fun initializeClashMode(modeList: List<String>, currentMode: String) {}

        fun updateClashMode(newMode: String) {}

        fun writeConnectionEvents(events: ConnectionEvents) {}
    }

    private val access = Any()
    private var connectionEpoch = 0
    private var connecting = false
    private var commandClient: io.nekohasekai.libbox.CommandClient? = null

    fun connect() {
        val epoch: Int
        val previousClient: io.nekohasekai.libbox.CommandClient?
        synchronized(access) {
            epoch = ++connectionEpoch
            previousClient = commandClient
            commandClient = null
            cachedGroups = null
            connecting = true
        }
        if (previousClient != null) {
            getAllHandlers().forEach { it.onDisconnected() }
            runCatching {
                previousClient.disconnect()
            }
        }
        val options = CommandClientOptions()
        connectionTypes.forEach { connectionType ->
            val command =
                when (connectionType) {
                    ConnectionType.Status -> Libbox.CommandStatus
                    ConnectionType.Groups -> Libbox.CommandGroup
                    ConnectionType.Log -> Libbox.CommandLog
                    ConnectionType.ClashMode -> Libbox.CommandClashMode
                    ConnectionType.Connections -> Libbox.CommandConnections
                }
            options.addCommand(command)
        }
        options.statusInterval = 1 * 1000 * 1000 * 1000
        val commandClient = io.nekohasekai.libbox.CommandClient(ClientHandler(epoch), options)
        try {
            commandClient.connect()
        } catch (e: Exception) {
            synchronized(access) {
                if (epoch == connectionEpoch) {
                    connecting = false
                }
            }
            Log.d("CommandClient", "connect failed", e)
            return
        }
        val stale =
            synchronized(access) {
                if (epoch != connectionEpoch) {
                    true
                } else {
                    this.commandClient = commandClient
                    connecting = false
                    false
                }
            }
        if (stale) {
            runCatching {
                commandClient.disconnect()
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun disconnect() {
        val client: io.nekohasekai.libbox.CommandClient?
        val notifyDisconnected: Boolean
        synchronized(access) {
            connectionEpoch++
            client = commandClient
            notifyDisconnected = client != null || connecting
            cachedGroups = null
            commandClient = null
            connecting = false
        }
        if (notifyDisconnected) {
            getAllHandlers().forEach { it.onDisconnected() }
        }
        if (client != null) {
            // The owning scope may already be cancelled when this is called from
            // ViewModel.onCleared, so release the libbox client independently.
            GlobalScope.launch(Dispatchers.IO) {
                runCatching {
                    client.disconnect()
                }
            }
        }
    }

    private fun isActiveEpoch(epoch: Int): Boolean = synchronized(access) { epoch == connectionEpoch }

    private inner class ClientHandler(private val epoch: Int) : CommandClientHandler {
        override fun connected() {
            if (!isActiveEpoch(epoch)) return
            getAllHandlers().forEach { it.onConnected() }
            Log.d("CommandClient", "connected")
        }

        override fun disconnected(message: String?) {
            synchronized(access) {
                if (epoch != connectionEpoch) return
                connectionEpoch++
                connecting = false
                cachedGroups = null
                commandClient = null
            }
            getAllHandlers().forEach { it.onDisconnected() }
            Log.d("CommandClient", "disconnected: $message")
        }

        override fun writeGroups(message: OutboundGroupIterator?) {
            if (message == null || !isActiveEpoch(epoch)) {
                return
            }
            val groups = mutableListOf<OutboundGroup>()
            while (message.hasNext()) {
                groups.add(message.next())
            }
            cachedGroups = groups
            getAllHandlers().forEach { it.updateGroups(groups) }
        }

        override fun setDefaultLogLevel(level: Int) {
            if (!isActiveEpoch(epoch)) return
            getAllHandlers().forEach { it.setDefaultLogLevel(level) }
        }

        override fun clearLogs() {
            if (!isActiveEpoch(epoch)) return
            getAllHandlers().forEach { it.clearLogs() }
        }

        override fun writeLogs(messageList: LogIterator?) {
            if (messageList == null || !isActiveEpoch(epoch)) {
                return
            }
            val logs = messageList.toList()
            getAllHandlers().forEach { it.appendLogs(logs) }
        }

        override fun writeStatus(message: StatusMessage) {
            if (!isActiveEpoch(epoch)) return
            getAllHandlers().forEach { it.updateStatus(message) }
        }

        override fun initializeClashMode(modeList: StringIterator, currentMode: String) {
            if (!isActiveEpoch(epoch)) return
            val modes = modeList.toList()
            getAllHandlers().forEach { it.initializeClashMode(modes, currentMode) }
        }

        override fun updateClashMode(newMode: String) {
            if (!isActiveEpoch(epoch)) return
            getAllHandlers().forEach { it.updateClashMode(newMode) }
        }

        override fun writeConnectionEvents(events: ConnectionEvents?) {
            if (events == null || !isActiveEpoch(epoch)) return
            getAllHandlers().forEach { it.writeConnectionEvents(events) }
        }
    }
}
