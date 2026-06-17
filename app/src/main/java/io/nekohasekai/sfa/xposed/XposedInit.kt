package io.nekohasekai.sfa.xposed

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.nekohasekai.sfa.xposed.hooks.ModernXposedApi

class XposedInit : XposedModule() {

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        HookErrorStore.i(TAG, "Using modern hooks for API $apiVersion")
        HookInstaller.install(ModernXposedApi(this), param.classLoader)
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        HookErrorStore.i(TAG, "Preparing API 102 hot reload")
        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        val oldHooks = param.oldHookHandles.map { OldHook(it, it.id) }
        if (!param.isSystemServer) {
            HookErrorStore.w(TAG, "Ignoring hot reload outside system_server")
            releaseOldHooks(oldHooks, emptySet())
            return
        }

        val api = ModernXposedApi(this)
        val classLoader = resolveSystemServerClassLoader(oldHooks)
        HookErrorStore.i(TAG, "Reinstalling hooks after API 102 hot reload")
        HookInstaller.install(api, classLoader)
        releaseOldHooks(oldHooks, api.installedHookIds())
    }

    private fun resolveSystemServerClassLoader(
        oldHooks: List<OldHook>,
    ): ClassLoader = oldHooks.asSequence()
        .mapNotNull { it.handle.executable.declaringClass.classLoader }
        .firstOrNull()
        ?: ClassLoader.getSystemClassLoader()

    private fun releaseOldHooks(
        oldHooks: List<OldHook>,
        replacedHookIds: Set<String>,
    ) {
        var replaced = 0
        var released = 0
        oldHooks.forEach { oldHook ->
            if (oldHook.id?.let(replacedHookIds::contains) == true) {
                replaced++
                return@forEach
            }
            try {
                oldHook.handle.unhook()
                released++
            } catch (e: Throwable) {
                HookErrorStore.e(TAG, "Failed to release an old hook", e)
            }
        }
        HookErrorStore.i(TAG, "Hot reload completed: replaced=$replaced, released=$released")
    }

    private data class OldHook(
        val handle: XposedInterface.HookHandle,
        val id: String?,
    )

    companion object {
        const val TAG = "sing-box-lsposed"
    }
}
