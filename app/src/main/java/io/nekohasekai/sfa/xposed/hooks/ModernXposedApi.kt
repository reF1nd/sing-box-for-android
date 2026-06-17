package io.nekohasekai.sfa.xposed.hooks

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ModernXposedApi(private val xposed: XposedInterface) : XposedApi {
    private val hookIdCounters = ConcurrentHashMap<String, AtomicInteger>()
    private val installedHookIds = ConcurrentHashMap.newKeySet<String>()

    override fun hook(executable: Executable, hook: MethodHook): XposedUnhook {
        val hookId = nextHookId(executable)
        val handle = xposed.hook(executable).setId(hookId).intercept { chain ->
            val param = MethodHookParam(
                chain.executable,
                chain.thisObject,
                chain.args.toTypedArray(),
            )
            hook.before(param)
            if (!param.skipOriginal) {
                try {
                    param.setOriginalResult(chain.proceed(param.args))
                } catch (e: Throwable) {
                    param.setOriginalThrowable(e)
                }
            }
            param.switchToAfter()
            hook.after(param)
            param.throwable?.let { throw it }
            param.result
        }
        installedHookIds.add(hookId)
        return object : XposedUnhook {
            override fun unhook() {
                handle.unhook()
            }
        }
    }

    fun installedHookIds(): Set<String> = installedHookIds.toSet()

    private fun nextHookId(executable: Executable): String {
        val baseId = buildString {
            append(HOOK_ID_PREFIX)
            append(executable.declaringClass.name)
            append('#')
            append(if (executable is Constructor<*>) "<init>" else executable.name)
            append('(')
            executable.parameterTypes.joinTo(this, separator = ",") { it.name }
            append(')')
        }
        val ordinal = hookIdCounters.computeIfAbsent(baseId) { AtomicInteger() }.getAndIncrement()
        return if (ordinal == 0) baseId else "$baseId@$ordinal"
    }

    override fun invokeOriginal(executable: Executable, thisObject: Any?, args: Array<Any?>): Any? = when (executable) {
        is Method -> {
            val invoker = xposed.getInvoker(executable)
            invoker.setType(XposedInterface.Invoker.Type.ORIGIN)
            invoker.invoke(thisObject, *args)
        }
        is Constructor<*> -> {
            val invoker = xposed.getInvoker(executable)
            invoker.setType(XposedInterface.Invoker.Type.ORIGIN)
            invoker.newInstance(*args)
        }
        else -> throw IllegalArgumentException("Unsupported executable: ${executable.javaClass.name}")
    }

    private companion object {
        const val HOOK_ID_PREFIX = "sfa:"
    }
}
