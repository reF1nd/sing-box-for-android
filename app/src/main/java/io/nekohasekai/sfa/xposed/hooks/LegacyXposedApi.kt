package io.nekohasekai.sfa.xposed.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Executable

object LegacyXposedApi : XposedApi {
    override fun hook(executable: Executable, hook: MethodHook): XposedUnhook {
        val unhook = XposedBridge.hookMethod(
            executable,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    val hookParam = param.toHookParam()
                    hook.before(hookParam)
                    if (hookParam.skipOriginal) {
                        copyBack(hookParam, param)
                    }
                }

                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    val hookParam = param.toHookParam()
                    hookParam.switchToAfter()
                    hook.after(hookParam)
                    copyBack(hookParam, param)
                }
            },
        )
        return object : XposedUnhook {
            override fun unhook() {
                unhook.unhook()
            }
        }
    }

    override fun invokeOriginal(executable: Executable, thisObject: Any?, args: Array<Any?>): Any? =
        XposedBridge.invokeOriginalMethod(executable, thisObject, args)

    private fun XC_MethodHook.MethodHookParam.toHookParam(): io.nekohasekai.sfa.xposed.hooks.MethodHookParam {
        val hookParam = MethodHookParam(
            method as Executable,
            thisObject,
            args,
        )
        if (throwable != null) {
            hookParam.setOriginalThrowable(throwable)
        } else {
            hookParam.setOriginalResult(result)
        }
        return hookParam
    }

    private fun copyBack(
        hookParam: io.nekohasekai.sfa.xposed.hooks.MethodHookParam,
        xposedParam: XC_MethodHook.MethodHookParam,
    ) {
        val throwable = hookParam.throwable
        if (throwable != null) {
            xposedParam.throwable = throwable
        } else {
            xposedParam.result = hookParam.result
        }
    }
}
