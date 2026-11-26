package io.nekohasekai.sfa.ui

import io.nekohasekai.sfa.constant.ServiceMode
import io.nekohasekai.sfa.constant.Status

internal enum class ShortcutEntryAction {
    CREATE_SHORTCUT,
    OPEN_MAIN_ACTIVITY,
    IGNORE,
}

internal sealed interface ShortcutToggleAction<out T> {
    data object Stop : ShortcutToggleAction<Nothing>

    data object Start : ShortcutToggleAction<Nothing>

    data class RequestVpnPermission<out T>(val prepareResult: T) : ShortcutToggleAction<T>

    data object OpenMainActivity : ShortcutToggleAction<Nothing>

    data object Ignore : ShortcutToggleAction<Nothing>
}

internal object ShortcutToggleDecision {
    const val SHORTCUT_TOGGLE_ID = "toggle"
    const val ACTION_CREATE_SHORTCUT = "android.intent.action.CREATE_SHORTCUT"
    const val ACTION_MAIN = "android.intent.action.MAIN"

    fun resolveEntryAction(action: String?): ShortcutEntryAction = when (action) {
        ACTION_CREATE_SHORTCUT -> ShortcutEntryAction.CREATE_SHORTCUT
        ACTION_MAIN -> ShortcutEntryAction.OPEN_MAIN_ACTIVITY
        else -> ShortcutEntryAction.IGNORE
    }

    fun isUnlocked(isKeyguardLocked: Boolean?): Boolean = isKeyguardLocked == false

    suspend fun <T> resolveToggleAction(
        status: Status,
        loadServiceMode: suspend () -> String,
        prepareVpn: () -> T?,
        isUnlocked: () -> Boolean = { true },
    ): ShortcutToggleAction<T> = when (status) {
        Status.Starting, Status.Started -> {
            if (isUnlocked()) ShortcutToggleAction.Stop else ShortcutToggleAction.Ignore
        }

        Status.Stopped -> {
            val serviceMode = loadServiceMode()
            if (!isUnlocked()) {
                ShortcutToggleAction.Ignore
            } else if (serviceMode == ServiceMode.VPN) {
                val prepareResult =
                    try {
                        prepareVpn()
                    } catch (_: Exception) {
                        return if (isUnlocked()) {
                            ShortcutToggleAction.OpenMainActivity
                        } else {
                            ShortcutToggleAction.Ignore
                        }
                    }
                if (!isUnlocked()) {
                    ShortcutToggleAction.Ignore
                } else if (prepareResult != null) {
                    ShortcutToggleAction.RequestVpnPermission(prepareResult)
                } else {
                    ShortcutToggleAction.Start
                }
            } else {
                ShortcutToggleAction.Start
            }
        }

        else -> ShortcutToggleAction.Ignore
    }
}
