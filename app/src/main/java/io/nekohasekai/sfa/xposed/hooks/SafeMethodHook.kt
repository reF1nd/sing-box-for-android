package io.nekohasekai.sfa.xposed.hooks

import io.nekohasekai.sfa.xposed.HookErrorStore
import java.lang.reflect.Executable

interface MethodHook {
    fun before(param: MethodHookParam)
    fun after(param: MethodHookParam)
}

class MethodHookParam(
    val method: Executable,
    val thisObject: Any?,
    val args: Array<Any?>,
) {
    internal enum class Phase {
        BEFORE,
        AFTER,
    }

    internal var phase = Phase.BEFORE
    internal var skipOriginal = false

    private var currentResult: Any? = null
    private var currentThrowable: Throwable? = null

    var result: Any?
        get() = currentResult
        set(value) {
            currentResult = value
            currentThrowable = null
            if (phase == Phase.BEFORE) {
                skipOriginal = true
            }
        }

    var throwable: Throwable?
        get() = currentThrowable
        set(value) {
            currentThrowable = value
            if (phase == Phase.BEFORE) {
                skipOriginal = true
            }
        }

    internal fun setOriginalResult(value: Any?) {
        currentResult = value
        currentThrowable = null
    }

    internal fun setOriginalThrowable(value: Throwable) {
        currentThrowable = value
    }

    internal fun switchToAfter() {
        phase = Phase.AFTER
    }
}

abstract class SafeMethodHook(private val source: String) : MethodHook {
    @Volatile
    private var disabled = false

    final override fun before(param: MethodHookParam) {
        if (disabled) return
        try {
            beforeHook(param)
        } catch (e: Throwable) {
            disabled = true
            HookErrorStore.e(source, "Hook disabled due to unrecoverable error", e)
        }
    }

    final override fun after(param: MethodHookParam) {
        if (disabled) return
        try {
            afterHook(param)
        } catch (e: Throwable) {
            disabled = true
            HookErrorStore.e(source, "Hook disabled due to unrecoverable error", e)
        }
    }

    protected open fun beforeHook(param: MethodHookParam) {}
    protected open fun afterHook(param: MethodHookParam) {}
}
