package io.nekohasekai.sfa.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.getSystemService
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppLifecycleObserver : DefaultLifecycleObserver {
    private val _isForeground = MutableStateFlow(true)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val _isScreenOn = MutableStateFlow(true)
    val isScreenOn: StateFlow<Boolean> = _isScreenOn.asStateFlow()

    private var powerManager: PowerManager? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> _isScreenOn.value = true
                Intent.ACTION_SCREEN_OFF -> _isScreenOn.value = false
            }
        }
    }

    fun register(context: Context) {
        powerManager = context.getSystemService<PowerManager>()!!
        refreshScreenState()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        context.registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
    }

    private fun refreshScreenState() {
        powerManager?.let { _isScreenOn.value = it.isInteractive }
    }

    override fun onStart(owner: LifecycleOwner) {
        refreshScreenState()
        _isForeground.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        _isForeground.value = false
    }
}
