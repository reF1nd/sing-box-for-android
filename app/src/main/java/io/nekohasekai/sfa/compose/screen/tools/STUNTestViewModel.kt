package io.nekohasekai.sfa.compose.screen.tools

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.STUNTestHandler
import io.nekohasekai.libbox.STUNTestProgress
import io.nekohasekai.libbox.STUNTestResult
import io.nekohasekai.libbox.STUNTestSession
import io.nekohasekai.sfa.compose.base.BaseViewModel
import io.nekohasekai.sfa.utils.CommandTarget
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class STUNTestState(
    val phase: Int = -1,
    val externalAddr: String = "",
    val latencyMs: Int = 0,
    val natMapping: Int = 0,
    val natFiltering: Int = 0,
    val natTypeSupported: Boolean = false,
    val isRunning: Boolean = false,
    val server: String = Libbox.STUNDefaultServer,
    val selectedOutbound: String = "",
)

class STUNTestViewModel : BaseViewModel<STUNTestState, Nothing>() {
    private var standaloneTest: io.nekohasekai.libbox.STUNTest? = null

    @Volatile
    private var stunSession: STUNTestSession? = null

    @Volatile
    private var sessionGeneration = 0L

    override fun createInitialState() = STUNTestState()

    fun updateServer(server: String) {
        updateState { copy(server = server) }
    }

    fun selectOutbound(tag: String) {
        updateState { copy(selectedOutbound = tag) }
    }

    fun onVpnDisconnected() {
        cancelTest()
        updateState { copy(selectedOutbound = "") }
    }

    fun startTest(vpnRunning: Boolean) {
        val generation = ++sessionGeneration
        updateState {
            copy(
                phase = -1,
                externalAddr = "",
                latencyMs = 0,
                natMapping = 0,
                natFiltering = 0,
                natTypeSupported = false,
                isRunning = true,
            )
        }

        val server = currentState.server
        val outboundTag = currentState.selectedOutbound
        val handler = createHandler(generation)

        if (vpnRunning) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val newSession =
                        CommandTarget.standaloneClient()
                            .startSTUNTest(server, outboundTag, handler)
                    setStunSession(generation, newSession)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        if (!isCurrentSession(generation)) return@withContext
                        updateState { copy(isRunning = false) }
                        stunSession = null
                        sendError(e)
                    }
                }
            }
        } else {
            val test = Libbox.newSTUNTest()
            standaloneTest = test
            launch {
                withContext(Dispatchers.IO) {
                    test.start(server, handler)
                }
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun closeStunSession(session: STUNTestSession) {
        GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                session.close()
            }
        }
    }

    private fun closeStunSession() {
        val session = stunSession ?: return
        stunSession = null
        closeStunSession(session)
    }

    private fun setStunSession(generation: Long, newSession: STUNTestSession) {
        if (!isCurrentSession(generation)) {
            closeStunSession(newSession)
            return
        }
        stunSession = newSession
        if (!isCurrentSession(generation)) {
            if (stunSession === newSession) {
                stunSession = null
            }
            closeStunSession(newSession)
        }
    }

    fun cancelTest() {
        sessionGeneration++
        closeStunSession()
        standaloneTest?.cancel()
        standaloneTest = null
        updateState { copy(isRunning = false) }
    }

    override fun onCleared() {
        cancelTest()
        super.onCleared()
    }

    private fun isCurrentSession(generation: Long): Boolean {
        return sessionGeneration == generation && currentState.isRunning
    }

    private fun createHandler(generation: Long): STUNTestHandler {
        return object : STUNTestHandler {
            override fun onProgress(progress: STUNTestProgress?) {
                progress ?: return
                viewModelScope.launch {
                    if (!isCurrentSession(generation)) return@launch
                    updateState {
                        copy(
                            phase = progress.phase.toInt(),
                            externalAddr = progress.externalAddr,
                            latencyMs = progress.latencyMs.toInt(),
                            natMapping = progress.natMapping.toInt(),
                            natFiltering = progress.natFiltering.toInt(),
                        )
                    }
                }
            }

            override fun onResult(result: STUNTestResult?) {
                result ?: return
                viewModelScope.launch {
                    if (!isCurrentSession(generation)) return@launch
                    updateState {
                        copy(
                            phase = Libbox.STUNPhaseDone.toInt(),
                            isRunning = false,
                            externalAddr = result.externalAddr,
                            latencyMs = result.latencyMs.toInt(),
                            natMapping = result.natMapping.toInt(),
                            natFiltering = result.natFiltering.toInt(),
                            natTypeSupported = result.natTypeSupported,
                        )
                    }
                    standaloneTest = null
                    stunSession = null
                }
            }

            override fun onError(message: String?) {
                viewModelScope.launch {
                    if (!isCurrentSession(generation)) return@launch
                    updateState { copy(isRunning = false) }
                    standaloneTest = null
                    stunSession = null
                    if (message != null) {
                        sendErrorMessage(message)
                    }
                }
            }
        }
    }
}
