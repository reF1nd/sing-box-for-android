package io.nekohasekai.sfa.utils

import android.content.Context
import android.net.ConnectivityManager
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import io.nekohasekai.sfa.xposed.HookStatusKeys

object ConnectivityBinderUtils {
    private const val TAG = "ConnectivityBinderUtils"

    sealed interface TransactResult<out T> {
        data class Success<T>(val value: T) : TransactResult<T>

        data object NotHandled : TransactResult<Nothing>

        data class Failure(val error: Exception) : TransactResult<Nothing>
    }

    // The module may be missing, stale, or another build that rejects this caller,
    // so binder and reply errors must never escape to UI callers.
    inline fun <T> transact(
        binder: IBinder,
        code: Int,
        writeData: (Parcel) -> Unit = {},
        readReply: (Parcel) -> T,
    ): TransactResult<T> = withParcel { data, reply ->
        try {
            data.writeInterfaceToken(HookStatusKeys.DESCRIPTOR)
            writeData(data)
            if (!binder.transact(code, data, reply, 0)) {
                TransactResult.NotHandled
            } else {
                reply.readException()
                TransactResult.Success(readReply(reply))
            }
        } catch (e: RemoteException) {
            TransactResult.Failure(e)
        } catch (e: RuntimeException) {
            TransactResult.Failure(e)
        }
    }

    fun getBinder(context: Context): IBinder? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        try {
            val field = cm.javaClass.getDeclaredField("mService")
            field.isAccessible = true
            val service = field.get(cm) as? android.os.IInterface
            if (service != null) {
                return service.asBinder()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get ConnectivityManager service binder", e)
        }
        return try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            getService.invoke(null, Context.CONNECTIVITY_SERVICE) as? IBinder
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get binder from ServiceManager", e)
            null
        }
    }

    inline fun <T> withParcel(block: (data: Parcel, reply: Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            block(data, reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
