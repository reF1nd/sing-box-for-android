package io.nekohasekai.sfa.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.bg.ServiceConnection
import io.nekohasekai.sfa.compose.MainActivity
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShortcutToggleActivity :
    ComponentActivity(),
    ServiceConnection.Callback {
    companion object {
        private const val STATE_PENDING_VPN_PREPARE = "pending_vpn_prepare"

        private fun isKeyguardUnlocked(context: Context): Boolean = ShortcutToggleDecision.isUnlocked(
            context.applicationContext.getSystemService<KeyguardManager>()?.isKeyguardLocked,
        )
    }

    private val connection = ServiceConnection(this, this, false)
    private var handled = false
    private var pendingVpnPrepare = false

    private val prepareLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            pendingVpnPrepare = false
            if (result.resultCode == RESULT_OK && isKeyguardUnlocked()) {
                val appContext = applicationContext
                BoxService.start(shouldStart = { isKeyguardUnlocked(appContext) })
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.getBoolean(STATE_PENDING_VPN_PREPARE, false) == true) {
            handled = true
            pendingVpnPrepare = true
            return
        }
        val keyguardManager = getSystemService<KeyguardManager>()
        if (keyguardManager == null) {
            finish()
            return
        }
        if (ShortcutToggleDecision.isUnlocked(keyguardManager.isKeyguardLocked)) {
            toggle()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguardManager.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        if (!isFinishing && !isDestroyed && isKeyguardUnlocked()) {
                            toggle()
                        } else {
                            finish()
                        }
                    }

                    override fun onDismissCancelled() {
                        finish()
                    }

                    override fun onDismissError() {
                        finish()
                    }
                },
            )
        } else {
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PENDING_VPN_PREPARE, pendingVpnPrepare)
    }

    private fun isKeyguardUnlocked(): Boolean = isKeyguardUnlocked(this)

    private fun toggle() {
        ShortcutManagerCompat.reportShortcutUsed(this, ShortcutToggleDecision.SHORTCUT_TOGGLE_ID)
        connection.connect()
    }

    override fun onServiceStatusChanged(status: Status) {
        if (handled) return
        handled = true
        connection.disconnect()
        if (!isKeyguardUnlocked()) {
            finish()
            return
        }
        lifecycleScope.launch {
            val action =
                ShortcutToggleDecision.resolveToggleAction(
                    status = status,
                    loadServiceMode = {
                        Settings.dataStore.initialize()
                        withContext(Dispatchers.IO) {
                            Settings.rebuildServiceMode()
                            Settings.serviceMode
                        }
                    },
                    prepareVpn = { VpnService.prepare(this@ShortcutToggleActivity) },
                    isUnlocked = ::isKeyguardUnlocked,
                )
            if (isFinishing || isDestroyed) return@launch
            if (!isKeyguardUnlocked()) {
                finish()
                return@launch
            }
            when (action) {
                ShortcutToggleAction.Stop -> {
                    BoxService.stop()
                    finish()
                }

                ShortcutToggleAction.Start -> {
                    val appContext = applicationContext
                    BoxService.start(shouldStart = { isKeyguardUnlocked(appContext) })
                    finish()
                }

                is ShortcutToggleAction.RequestVpnPermission -> {
                    pendingVpnPrepare = true
                    try {
                        prepareLauncher.launch(action.prepareResult)
                    } catch (_: Exception) {
                        pendingVpnPrepare = false
                        openMainActivity()
                        finish()
                    }
                }

                ShortcutToggleAction.OpenMainActivity -> {
                    openMainActivity()
                    finish()
                }

                ShortcutToggleAction.Ignore -> {
                    finish()
                }
            }
        }
    }

    private fun openMainActivity() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    override fun onDestroy() {
        connection.disconnect()
        super.onDestroy()
    }
}
