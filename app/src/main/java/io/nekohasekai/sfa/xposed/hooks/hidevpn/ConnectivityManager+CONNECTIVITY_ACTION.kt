package io.nekohasekai.sfa.xposed.hooks.hidevpn

import android.net.ConnectivityManager
import android.net.NetworkInfo
import io.nekohasekai.sfa.xposed.VpnSanitizer
import io.nekohasekai.sfa.xposed.hooks.SafeMethodHook

class HookConnectivityManagerConnectivityAction(private val helper: ConnectivityServiceHookHelper) {
    private companion object {
        private const val SOURCE = "HookConnectivityManagerConnectivityAction"
    }

    fun install() {
        helper.api.findAndHookMethod(
            helper.cls,
            "sendGeneralBroadcast",
            NetworkInfo::class.java,
            String::class.java,
            object : SafeMethodHook(SOURCE) {
                override fun beforeHook(param: MethodHookParam) {
                    val info = param.args[0] as? NetworkInfo ?: return
                    if (info.type != ConnectivityManager.TYPE_VPN) return
                    val service = param.thisObject ?: return
                    val defaultNai = helper.api.callMethod(service, "getDefaultNetwork")
                        ?: return
                    if (helper.isVpnNai(defaultNai)) {
                        return
                    }
                    val replacement = helper.api.getObjectField(defaultNai, "networkInfo") as? NetworkInfo
                        ?: return
                    param.args[0] = VpnSanitizer.cloneNetworkInfo(replacement)
                }
            },
        )
    }
}
