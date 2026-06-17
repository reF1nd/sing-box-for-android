package io.nekohasekai.sfa.xposed.hooks

import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

interface XposedUnhook {
    fun unhook()
}

interface XposedApi {
    fun hook(executable: Executable, hook: MethodHook): XposedUnhook

    fun invokeOriginal(executable: Executable, thisObject: Any?, args: Array<Any?>): Any?

    fun findAndHookMethod(target: Class<*>, methodName: String, vararg parameterTypesAndHook: Any?): XposedUnhook {
        val hook = parameterTypesAndHook.lastOrNull() as? MethodHook
            ?: throw IllegalArgumentException("last argument must be MethodHook")
        val parameterTypes = parameterTypesAndHook.dropLast(1).map {
            it as? Class<*> ?: throw IllegalArgumentException("method parameter must be Class")
        }.toTypedArray()
        return hook(ReflectionAccess.findMethod(target, methodName, *parameterTypes), hook)
    }

    fun findAndHookConstructor(target: Class<*>, vararg parameterTypesAndHook: Any?): XposedUnhook {
        val hook = parameterTypesAndHook.lastOrNull() as? MethodHook
            ?: throw IllegalArgumentException("last argument must be MethodHook")
        val parameterTypes = parameterTypesAndHook.dropLast(1).map {
            it as? Class<*> ?: throw IllegalArgumentException("constructor parameter must be Class")
        }.toTypedArray()
        return hook(ReflectionAccess.findConstructor(target, *parameterTypes), hook)
    }

    fun hookAllMethods(target: Class<*>, methodName: String, hook: MethodHook): Set<XposedUnhook> {
        val methods = target.declaredMethods.filter { it.name == methodName }
        if (methods.isEmpty()) return emptySet()
        return methods.mapTo(LinkedHashSet()) {
            it.isAccessible = true
            hook(it, hook)
        }
    }

    fun findClass(name: String, classLoader: ClassLoader?): Class<*> = ReflectionAccess.findClass(name, classLoader)

    fun findClassIfExists(name: String, classLoader: ClassLoader?): Class<*>? = ReflectionAccess.findClassIfExists(name, classLoader)

    fun findMethod(target: Class<*>, name: String, vararg parameterTypes: Class<*>): Method =
        ReflectionAccess.findMethod(target, name, *parameterTypes)

    fun findMethodIfExists(target: Class<*>, name: String, vararg parameterTypes: Class<*>): Method? =
        ReflectionAccess.findMethodIfExists(target, name, *parameterTypes)

    fun findField(target: Class<*>, name: String): Field = ReflectionAccess.findField(target, name)

    fun findFieldIfExists(target: Class<*>, name: String): Field? = ReflectionAccess.findFieldIfExists(target, name)

    fun getObjectField(instance: Any, name: String): Any? = ReflectionAccess.findField(instance.javaClass, name).get(instance)

    fun getIntField(instance: Any, name: String): Int = ReflectionAccess.findField(instance.javaClass, name).getInt(instance)

    fun getStaticObjectField(target: Class<*>, name: String): Any? = ReflectionAccess.findField(target, name).get(null)

    fun callMethod(instance: Any, name: String, vararg args: Any?): Any? = ReflectionAccess.callMethod(instance, name, *args)
}

object ReflectionAccess {
    private sealed class FieldLookup {
        class Found(val field: Field) : FieldLookup()

        object Missing : FieldLookup()
    }

    private data class CompatibleMethodKey(
        val name: String,
        val parameterTypes: List<Class<*>?>,
    )

    private val fieldCache = ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, FieldLookup>>()
    private val compatibleMethodCache =
        ConcurrentHashMap<Class<*>, ConcurrentHashMap<CompatibleMethodKey, Method>>()

    fun findClass(name: String, classLoader: ClassLoader?): Class<*> = if (classLoader != null) {
        Class.forName(name, false, classLoader)
    } else {
        Class.forName(name)
    }

    fun findClassIfExists(name: String, classLoader: ClassLoader?): Class<*>? = try {
        findClass(name, classLoader)
    } catch (_: Throwable) {
        null
    }

    fun findMethod(target: Class<*>, name: String, vararg parameterTypes: Class<*>): Method {
        var current: Class<*>? = target
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        throw NoSuchMethodException("${target.name}#$name")
    }

    fun findMethodIfExists(target: Class<*>, name: String, vararg parameterTypes: Class<*>): Method? = try {
        findMethod(target, name, *parameterTypes)
    } catch (_: Throwable) {
        null
    }

    fun findConstructor(target: Class<*>, vararg parameterTypes: Class<*>): Constructor<*> =
        target.getDeclaredConstructor(*parameterTypes).apply { isAccessible = true }

    fun findField(target: Class<*>, name: String): Field =
        findCachedField(target, name) ?: throw NoSuchFieldException("${target.name}#$name")

    fun findFieldIfExists(target: Class<*>, name: String): Field? = try {
        findCachedField(target, name)
    } catch (_: Throwable) {
        null
    }

    private fun findCachedField(target: Class<*>, name: String): Field? {
        val targetCache = fieldCache.computeIfAbsent(target) { ConcurrentHashMap() }
        val cached = targetCache[name]
        if (cached != null) {
            return (cached as? FieldLookup.Found)?.field
        }
        val resolved =
            try {
                FieldLookup.Found(resolveField(target, name))
            } catch (_: NoSuchFieldException) {
                FieldLookup.Missing
            }
        val effective = targetCache.putIfAbsent(name, resolved) ?: resolved
        return (effective as? FieldLookup.Found)?.field
    }

    private fun resolveField(target: Class<*>, name: String): Field {
        var current: Class<*>? = target
        while (current != null) {
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException("${target.name}#$name")
    }

    fun callMethod(instance: Any, name: String, vararg args: Any?): Any? {
        val parameterTypes: List<Class<*>?> = args.map { it?.javaClass }
        val target = instance.javaClass
        val targetCache = compatibleMethodCache.computeIfAbsent(target) { ConcurrentHashMap() }
        val key = CompatibleMethodKey(name, parameterTypes)
        val method =
            targetCache[key]
                ?: findCompatibleMethod(target, name, parameterTypes).let {
                    targetCache.putIfAbsent(key, it) ?: it
                }
        return method.invoke(instance, *args)
    }

    private fun findCompatibleMethod(target: Class<*>, name: String, parameterTypes: List<Class<*>?>): Method {
        var current: Class<*>? = target
        while (current != null) {
            val method = current.declaredMethods.firstOrNull {
                it.name == name && parametersMatch(it.parameterTypes, parameterTypes)
            }
            if (method != null) {
                method.isAccessible = true
                return method
            }
            current = current.superclass
        }
        throw NoSuchMethodException("${target.name}#$name")
    }

    private fun parametersMatch(expected: Array<Class<*>>, actual: List<Class<*>?>): Boolean {
        if (expected.size != actual.size) return false
        return expected.indices.all { index ->
            val actualType = actual[index] ?: return@all !expected[index].isPrimitive
            wrapPrimitive(expected[index]).isAssignableFrom(wrapPrimitive(actualType))
        }
    }

    private fun wrapPrimitive(type: Class<*>): Class<*> = when {
        !type.isPrimitive -> type
        type == java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        type == java.lang.Byte.TYPE -> java.lang.Byte::class.java
        type == java.lang.Character.TYPE -> java.lang.Character::class.java
        type == java.lang.Double.TYPE -> java.lang.Double::class.java
        type == java.lang.Float.TYPE -> java.lang.Float::class.java
        type == java.lang.Integer.TYPE -> java.lang.Integer::class.java
        type == java.lang.Long.TYPE -> java.lang.Long::class.java
        type == java.lang.Short.TYPE -> java.lang.Short::class.java
        type == java.lang.Void.TYPE -> java.lang.Void::class.java
        else -> type
    }

    fun isStatic(method: Method): Boolean = Modifier.isStatic(method.modifiers)
}
