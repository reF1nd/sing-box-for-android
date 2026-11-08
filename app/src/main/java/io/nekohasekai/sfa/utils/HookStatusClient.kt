package io.nekohasekai.sfa.utils

import android.content.Context
import android.content.pm.PackageInfo
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import io.nekohasekai.sfa.bg.ParceledListSlice
import io.nekohasekai.sfa.utils.ConnectivityBinderUtils.TransactResult
import io.nekohasekai.sfa.xposed.HookModuleVersion
import io.nekohasekai.sfa.xposed.HookStatusKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object HookStatusClient {
    private const val TAG = "HookStatusClient"

    data class Status(val active: Boolean, val lastPatchedAt: Long, val version: Int, val systemPid: Int)

    private val statusFlow = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = statusFlow

    @Volatile
    private var appContext: Context? = null

    fun register(context: Context) {
        appContext = context.applicationContext
        refresh()
    }

    fun refresh() {
        val context = appContext ?: return
        val binder = ConnectivityBinderUtils.getBinder(context) ?: run {
            statusFlow.value = null
            return
        }
        val result = ConnectivityBinderUtils.transact(binder, HookStatusKeys.TRANSACTION_STATUS, readReply = ::readStatus)
        statusFlow.value = when (result) {
            is TransactResult.Success -> result.value
            TransactResult.NotHandled -> queryLegacyStatus(binder)
            is TransactResult.Failure -> {
                Log.w(TAG, "Query hook status failed", result.error)
                null
            }
        }
    }

    // A module older than HookModuleVersion 4 may still be loaded until hot reload or reboot.
    // It cannot serve the current transaction codes, so it is reported inactive and its version
    // is only used to request a restart. The upstream module rejects this package on the same
    // code, which leaves the status empty.
    private fun queryLegacyStatus(binder: IBinder): Status? {
        val result = ConnectivityBinderUtils.transact(binder, HookStatusKeys.LEGACY_TRANSACTION_STATUS, readReply = ::readStatus)
        return (result as? TransactResult.Success)?.value
            ?.takeIf { it.version < HookModuleVersion.CURRENT }
            ?.copy(active = false)
    }

    private fun readStatus(reply: Parcel) = Status(
        active = reply.readInt() != 0,
        lastPatchedAt = reply.readLong(),
        version = reply.readInt(),
        systemPid = reply.readInt(),
    )

    fun getInstalledPackages(context: Context, flags: Long, userId: Int): List<PackageInfo>? {
        val binder = ConnectivityBinderUtils.getBinder(context) ?: return null
        val result = ConnectivityBinderUtils.transact(
            binder,
            HookStatusKeys.TRANSACTION_GET_INSTALLED_PACKAGES,
            writeData = { data ->
                data.writeLong(flags)
                data.writeInt(userId)
            },
        ) { reply ->
            val slice = ParceledListSlice.CREATOR.createFromParcel(reply, PackageInfo::class.java.classLoader)
            @Suppress("UNCHECKED_CAST")
            (slice as ParceledListSlice<PackageInfo>).list
        }
        return when (result) {
            is TransactResult.Success -> result.value
            TransactResult.NotHandled -> null
            is TransactResult.Failure -> {
                Log.w(TAG, "Query installed packages failed", result.error)
                null
            }
        }
    }
}
