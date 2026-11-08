package io.nekohasekai.sfa.utils

import android.content.Context
import io.nekohasekai.sfa.bg.LogEntry
import io.nekohasekai.sfa.bg.ParceledListSlice
import io.nekohasekai.sfa.utils.ConnectivityBinderUtils.TransactResult
import io.nekohasekai.sfa.xposed.HookStatusKeys

object HookErrorClient {
    enum class Failure {
        SERVICE_UNAVAILABLE,
        TRANSACTION_FAILED,
        REMOTE_ERROR,
        PROTOCOL_ERROR,
    }

    data class Result(val logs: List<LogEntry>, val hasWarnings: Boolean, val failure: Failure? = null, val detail: String? = null)

    private fun failureResult(failure: Failure, detail: String? = null) = Result(
        logs = emptyList(),
        hasWarnings = false,
        failure = failure,
        detail = detail,
    )

    fun query(context: Context): Result {
        val binder = ConnectivityBinderUtils.getBinder(context)
            ?: return failureResult(Failure.SERVICE_UNAVAILABLE)
        val result = ConnectivityBinderUtils.transact(binder, HookStatusKeys.TRANSACTION_GET_ERRORS) { reply ->
            if (reply.dataAvail() < 4) {
                failureResult(Failure.PROTOCOL_ERROR, "reply too short: ${reply.dataAvail()}")
            } else {
                val hasWarnings = reply.readInt() != 0
                val slice = ParceledListSlice.CREATOR.createFromParcel(reply, LogEntry::class.java.classLoader)
                @Suppress("UNCHECKED_CAST")
                Result(logs = slice.list as List<LogEntry>, hasWarnings = hasWarnings)
            }
        }
        return when (result) {
            is TransactResult.Success -> result.value
            TransactResult.NotHandled -> failureResult(Failure.TRANSACTION_FAILED)
            is TransactResult.Failure -> failureResult(Failure.REMOTE_ERROR, result.error.message)
        }
    }
}
