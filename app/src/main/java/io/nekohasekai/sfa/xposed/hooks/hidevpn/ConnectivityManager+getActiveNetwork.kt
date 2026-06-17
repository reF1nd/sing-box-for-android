package io.nekohasekai.sfa.xposed.hooks.hidevpn

import android.os.Binder
import io.nekohasekai.sfa.xposed.hooks.SafeMethodHook

class HookConnectivityManagerGetActiveNetwork(private val helper: ConnectivityServiceHookHelper) {
    private companion object {
        private const val SOURCE = "HookConnectivityManagerGetActiveNetwork"
    }

    fun install() {
        helper.api.findAndHookMethod(
            helper.cls,
            "getActiveNetwork",
            object : SafeMethodHook(SOURCE) {
                override fun afterHook(param: MethodHookParam) {
                    val uid = Binder.getCallingUid()
                    if (!helper.shouldHide(param.thisObject, uid)) return
                    val replacement = helper.getUnderlyingNetwork(param.thisObject, uid) ?: return
                    param.result = replacement
                }
            },
        )

        helper.api.findAndHookMethod(
            helper.cls,
            "getActiveNetworkForUid",
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            object : SafeMethodHook(SOURCE) {
                override fun afterHook(param: MethodHookParam) {
                    val uid = param.args[0] as Int
                    if (!helper.shouldHide(param.thisObject, uid)) return
                    val replacement = helper.getUnderlyingNetwork(param.thisObject, uid) ?: return
                    param.result = replacement
                }
            },
        )
    }
}
