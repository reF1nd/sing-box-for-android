package io.nekohasekai.sfa.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.nekohasekai.sfa.xposed.hooks.LegacyXposedApi

class XposedLegacyInit : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") {
            return
        }
        HookInstaller.install(LegacyXposedApi, lpparam.classLoader)
    }
}
