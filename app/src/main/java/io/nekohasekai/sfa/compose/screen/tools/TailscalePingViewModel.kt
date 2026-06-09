package io.nekohasekai.sfa.compose.screen.tools

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.TailscalePingHandler
import io.nekohasekai.libbox.TailscalePingResult
import io.nekohasekai.libbox.TailscalePingSession
import io.nekohasekai.sfa.compose.base.BaseViewModel
import io.nekohasekai.sfa.utils.CommandTarget
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TailscalePingState(
    val isRunning: Boolean = false,
    val hasResult: Boolean = false,
    val latencyMs: Double = 0.0,
    val isDirect: Boolean = false,
    val derpRegionCode: String = "",
    val endpoint: String = "",
    val peerRelay: String = "",
    val error: String = "",
    val latencyHistory: List<Float> = emptyList(),
)

class TailscalePingViewModel : BaseViewModel<TailscalePingState, Nothing>() {
    private val maxHistorySize = 30
    private var pingSession: TailscalePingSession? = null
    private var sessionGeneration = 0L

    override fun createInitialState() = TailscalePingState()

    fun startPing(endpointTag: String, peerIP: String) {
        val generation = ++sessionGeneration
        updateState {
            copy(
                isRunning = true,
                hasResult = false,
                error = "",
                latencyHistory = emptyList(),
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                pingSession =
                    CommandTarget.standaloneClient()
                        .startTailscalePing(
                            endpointTag,
                            peerIP,
                            object : TailscalePingHandler {
                                override fun onPingResult(result: TailscalePingResult?) {
                                    result ?: return
                                    viewModelScope.launch {
                                        if (!isCurrentSession(generation)) return@launch
                                        if (result.error.isNotEmpty()) {
                                            updateState { copy(error = result.error) }
                                            return@launch
                                        }
                                        val newHistory = currentState.latencyHistory.toMutableList()
                                        newHistory.add(result.latencyMs.toFloat())
                                        if (newHistory.size > maxHistorySize) {
                                            newHistory.removeFirstOrNull()
                                        }
                                        updateState {
                                            copy(
                                                hasResult = true,
                                                latencyMs = result.latencyMs,
                                                isDirect = result.isDirect,
                                                derpRegionCode = result.derpRegionCode,
                                                endpoint = result.endpoint,
                                                peerRelay = result.peerRelay,
                                                error = "",
                                                latencyHistory = newHistory,
                                            )
                                        }
                                    }
                                }

                                override fun onError(message: String?) {
                                    viewModelScope.launch {
                                        if (!isCurrentSession(generation)) return@launch
                                        updateState { copy(isRunning = false) }
                                        pingSession = null
                                    }
                                }
                            },
                        )
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isCurrentSession(generation)) return@withContext
                    updateState { copy(isRunning = false) }
                    pingSession = null
                }
            }
        }
    }

    private fun isCurrentSession(generation: Long): Boolean {
        return sessionGeneration == generation && currentState.isRunning
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun closePingSession() {
        val session = pingSession ?: return
        pingSession = null
        GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                session.close()
            }
        }
    }

    fun stopPing() {
        sessionGeneration++
        closePingSession()
        updateState { copy(isRunning = false) }
    }

    override fun onCleared() {
        super.onCleared()
        stopPing()
    }
}
