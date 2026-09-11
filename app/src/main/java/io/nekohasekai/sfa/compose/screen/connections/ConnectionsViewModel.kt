package io.nekohasekai.sfa.compose.screen.connections

import android.util.Log
import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Connections
import io.nekohasekai.sfa.compose.base.BaseViewModel
import io.nekohasekai.sfa.compose.base.ScreenEvent
import io.nekohasekai.sfa.compose.model.Connection
import io.nekohasekai.sfa.compose.model.ConnectionSort
import io.nekohasekai.sfa.compose.model.ConnectionStateFilter
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.ktx.toList
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ConnectionsUiState(
    val connections: List<Connection> = emptyList(),
    val allConnections: List<Connection> = emptyList(),
    val isLoading: Boolean = false,
    val stateFilter: ConnectionStateFilter = ConnectionStateFilter.Active,
    val sort: ConnectionSort = ConnectionSort.ByDate,
    val searchText: String = "",
    val isSearchActive: Boolean = false,
)

sealed class ConnectionsEvent : ScreenEvent {
    data class ConnectionClosed(val id: String) : ConnectionsEvent()
    data object AllConnectionsClosed : ConnectionsEvent()
}

class ConnectionsViewModel : BaseViewModel<ConnectionsUiState, ConnectionsEvent>() {

    private val _serviceStatus = MutableStateFlow(Status.Stopped)
    val serviceStatus = _serviceStatus.asStateFlow()

    private val _visibleCount = MutableStateFlow(0)

    // Only the main dispatcher changes this reference. Workers own one store and
    // must verify its identity before publishing a snapshot back on main.
    private var activeStore: ConnectionStore? = null

    private class ConnectionStore {
        val mutex = Mutex()
        var connections: Connections? = null
    }

    override fun createInitialState() = ConnectionsUiState()

    private data class SessionTarget(val connect: Boolean, val remoteServerId: Long?)

    init {
        viewModelScope.launch {
            combine(
                AppLifecycleObserver.isForeground,
                AppLifecycleObserver.isScreenOn,
                _visibleCount,
                _serviceStatus,
                combine(
                    RemoteControlManager.remoteServer,
                    RemoteControlManager.isConnected,
                ) { remoteServer, remoteConnected -> remoteServer?.id to remoteConnected },
            ) { foreground, screenOn, visibleCount, status, (remoteServerId, remoteConnected) ->
                val serviceReady =
                    if (remoteServerId != null) remoteConnected else status == Status.Started
                SessionTarget(
                    connect = foreground && screenOn && visibleCount > 0 && serviceReady,
                    remoteServerId = remoteServerId,
                )
            }.distinctUntilChanged().collectLatest { target ->
                clearConnections(loading = target.connect)
                if (target.connect) {
                    try {
                        subscribe(target)
                    } finally {
                        // collectLatest waits for cancellation before starting the next target.
                        clearConnections(loading = false)
                    }
                }
            }
        }
    }

    fun setVisible(visible: Boolean) {
        _visibleCount.value += if (visible) 1 else -1
    }

    fun updateServiceStatus(status: Status) {
        _serviceStatus.value = status
    }

    private fun clearConnections(loading: Boolean) {
        activeStore = null
        updateState {
            copy(connections = emptyList(), allConnections = emptyList(), isLoading = loading)
        }
    }

    private suspend fun subscribe(target: SessionTarget) {
        val subscription = ConnectionSubscription<ConnectionEvents>(createClient = { scope, listener ->
            val client = CommandClient(
                scope,
                CommandClient.ConnectionType.Connections,
                object : CommandClient.Handler {
                    override fun writeConnectionEvents(events: ConnectionEvents) {
                        listener.onEvent(events)
                    }

                    override fun onConnectionError(kind: CommandClient.ConnectionErrorKind, message: String) {
                        listener.onFailure(
                            ConnectionSubscription.Failure(
                                message,
                                retryable = isRetryableConnectionError(message) &&
                                    (target.remoteServerId == null || kind == CommandClient.ConnectionErrorKind.ConnectionLost),
                            ),
                        )
                    }
                },
                localOnly = target.remoteServerId == null,
            )
            object : ConnectionSubscription.Client {
                override fun connect() = client.connect()
                override fun disconnect() = client.disconnect()
            }
        })
        subscription.collect(
            createConsumer = {
                val store = ConnectionStore()
                activeStore = store
                updateState { copy(isLoading = true) }
                val consume: suspend (List<ConnectionEvents>) -> Unit = { events ->
                    val snapshot = withContext(Dispatchers.Default) {
                        store.mutex.withLock {
                            if (store.connections == null) {
                                // An empty reset is a valid first snapshot too.
                                if (!events.first().reset) {
                                    throw ConnectionSubscription.Failure("Missing initial connection snapshot")
                                }
                                store.connections = Connections()
                            }
                            val connections = checkNotNull(store.connections)
                            events.forEach { connections.applyEvents(it) }
                            buildConnectionLists(connections, uiState.value)
                        }
                    }
                    publishConnections(store, snapshot)
                }
                consume
            },
            onFailure = { failure ->
                clearConnections(loading = failure.retryable)
                Log.d("ConnectionsViewModel", "Connection subscription failed", failure)
                if (!failure.retryable) sendError(failure)
            },
        )
    }

    fun setStateFilter(filter: ConnectionStateFilter) {
        updateState { copy(stateFilter = filter) }
        requestConnectionsRefresh()
    }

    fun setSort(sort: ConnectionSort) {
        updateState { copy(sort = sort) }
        requestConnectionsRefresh()
    }

    fun setSearchText(text: String) {
        updateState { copy(searchText = text) }
        requestConnectionsRefresh()
    }

    fun toggleSearch() {
        val newSearchActive = !currentState.isSearchActive
        updateState {
            copy(
                isSearchActive = newSearchActive,
                searchText = if (newSearchActive) searchText else "",
            )
        }
        if (!newSearchActive) {
            requestConnectionsRefresh()
        }
    }

    fun closeConnection(connectionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                CommandTarget.standaloneClient().closeConnection(connectionId)
                withContext(Dispatchers.Main) {
                    sendEvent(ConnectionsEvent.ConnectionClosed(connectionId))
                }
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    fun closeAllConnections() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                CommandTarget.standaloneClient().closeConnections()
                withContext(Dispatchers.Main) {
                    sendEvent(ConnectionsEvent.AllConnectionsClosed)
                }
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    private fun requestConnectionsRefresh() {
        val store = activeStore ?: return
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.Default) {
                store.mutex.withLock {
                    val connections = store.connections ?: return@withLock null
                    buildConnectionLists(connections, uiState.value)
                }
            } ?: return@launch
            publishConnections(store, snapshot)
        }
    }

    private fun publishConnections(store: ConnectionStore, snapshot: ConnectionLists) {
        if (activeStore !== store) return
        updateState {
            copy(
                connections = snapshot.connections,
                allConnections = snapshot.allConnections,
                isLoading = false,
            )
        }
    }

    private fun buildConnectionLists(
        connections: Connections,
        currentState: ConnectionsUiState,
    ): ConnectionLists {
        connections.filterState(ConnectionStateFilter.All.libboxValue)
        val allConnectionList = connections.iterator().toList()
            .filter { it.outboundType != "dns" }
            .map { Connection.from(it) }

        connections.filterState(currentState.stateFilter.libboxValue)

        when (currentState.sort) {
            ConnectionSort.ByDate -> connections.sortByDate()
            ConnectionSort.ByTraffic -> connections.sortByTraffic()
            ConnectionSort.ByTrafficTotal -> connections.sortByTrafficTotal()
        }

        val connectionList = connections.iterator().toList()
            .filter { it.outboundType != "dns" }
            .map { Connection.from(it) }
            .filter { it.performSearch(currentState.searchText) }

        return ConnectionLists(
            connections = connectionList,
            allConnections = allConnectionList,
        )
    }

    private data class ConnectionLists(
        val connections: List<Connection>,
        val allConnections: List<Connection>,
    )
}
