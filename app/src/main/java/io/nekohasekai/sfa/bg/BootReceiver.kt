package io.nekohasekai.sfa.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BootReceiver : BroadcastReceiver() {
    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
            }

            else -> return
        }
        val pendingResult = goAsync()
        GlobalScope.launch(Dispatchers.IO) {
            try {
                if (Settings.startedByUser) {
                    // Keep the crash-loop guard conservative when storage is
                    // temporarily unavailable. A manual start can retry setup.
                    if (!Application.application.reportsInstalled) return@launch
                    CrashReportManager.refresh()
                    if (CrashReportManager.unreadCount.value > 0) {
                        Settings.startedByUser = false
                        return@launch
                    }
                    withContext(Dispatchers.Main) {
                        BoxService.start().join()
                    }
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "auto-start", e)
            } finally {
                try {
                    Settings.dataStore.flush()
                } catch (e: Exception) {
                    Log.e("BootReceiver", "flush settings", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
