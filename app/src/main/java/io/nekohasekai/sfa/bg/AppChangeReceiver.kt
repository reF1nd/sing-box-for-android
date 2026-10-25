package io.nekohasekai.sfa.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.screen.profileoverride.PerAppProxyScanner
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AppChangeReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AppChangeReceiver"

        // Broadcasts for different packages are handled concurrently.
        private val managedListAccess = Mutex()
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: ${intent.action}")
        val packageName = intent.data?.schemeSpecificPart ?: return
        // An update sends REMOVED and ADDED with EXTRA_REPLACING before
        // REPLACED, so only the final REPLACED broadcast is handled.
        if (intent.action != Intent.ACTION_PACKAGE_REPLACED &&
            intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        ) {
            return
        }
        val removed = intent.action == Intent.ACTION_PACKAGE_REMOVED
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!Settings.perAppProxyEnabled) {
                    Log.d(TAG, "per app proxy disabled")
                    return@launch
                }
                if (!Settings.perAppProxyManagedMode) {
                    Log.d(TAG, "managed mode disabled")
                    return@launch
                }
                rescanPackage(packageName, removed)
                Settings.dataStore.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rescan $packageName", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.error_title, Toast.LENGTH_SHORT).show()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun rescanPackage(packageName: String, removed: Boolean) = managedListAccess.withLock {
        val packageInfo = if (removed) null else getPackageInfo(packageName)
        val isChinaApp = packageInfo != null && PerAppProxyScanner.scanChinaPackage(packageInfo)
        val managedList = Settings.perAppProxyManagedList
        val newList = if (isChinaApp) managedList + packageName else managedList - packageName
        if (newList != managedList) {
            Settings.perAppProxyManagedList = newList
        }
        Log.d(TAG, "rescan complete, $packageName china app: $isChinaApp")
    }

    private fun getPackageInfo(packageName: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Application.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            Application.packageManager.getPackageInfo(packageName, 0)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
