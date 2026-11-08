package io.nekohasekai.sfa.xposed

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.reflect.Method
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

object PrivilegeSettingsStore {
    private const val SETTINGS_DIR = "/data/system/sing-box-reF1nd"

    // Shared with upstream SFA by modules older than HookModuleVersion 4. It is imported
    // into SETTINGS_DIR once and left in place for the upstream module.
    private const val LEGACY_SETTINGS_DIR = "/data/system/sing-box"
    private const val SETTINGS_FILE = "privilege_settings.conf"
    private const val SETTINGS_VERSION = "1"

    @Volatile
    private var state = State()
    private val uidCache = ConcurrentHashMap<Int, Boolean>()

    private val appGlobalsClass by lazy { Class.forName("android.app.AppGlobals") }
    private val getPackageManagerMethod by lazy { appGlobalsClass.getMethod("getPackageManager") }
    private var getPackagesForUidMethod: Method? = null

    // Binder threads may call this concurrently; keep the saved file in step with the
    // in-memory state and never share the temp file between writers.
    @Synchronized
    fun update(enabled: Boolean, packages: Set<String>, interfaceRenameEnabled: Boolean, interfacePrefix: String) {
        val newState = State(
            enabled = enabled,
            packageSet = packages.toSet(),
            interfaceRenameEnabled = interfaceRenameEnabled,
            interfacePrefix = normalizePrefix(interfacePrefix),
        )
        applyState(newState, "updated")
        writeSettingsFile(newState)
    }

    @Synchronized
    fun loadFromDisk() {
        val legacyFile = File(LEGACY_SETTINGS_DIR, SETTINGS_FILE)
        val file = File(SETTINGS_DIR, SETTINGS_FILE).takeIf { it.isFile }
            ?: legacyFile.takeIf { it.isFile }
            ?: return
        try {
            val properties = Properties()
            file.bufferedReader().use(properties::load)
            if (properties.getProperty("version") != SETTINGS_VERSION) {
                HookErrorStore.w("PrivilegeSettingsStore", "Ignoring unsupported privilege settings version")
                return
            }
            val newState = State(
                enabled = parseFlag(properties, "enabled"),
                packageSet = properties.getProperty("packages")
                    ?.split(',')
                    ?.filterTo(LinkedHashSet()) { it.isNotBlank() }
                    ?: throw IllegalArgumentException("missing packages"),
                interfaceRenameEnabled = parseFlag(properties, "rename"),
                interfacePrefix = normalizePrefix(
                    properties.getProperty("prefix") ?: throw IllegalArgumentException("missing prefix"),
                ),
            )
            applyState(newState, "restored from ${file.path}")
            if (file == legacyFile) {
                writeSettingsFile(newState)
            }
        } catch (e: Throwable) {
            HookErrorStore.e("PrivilegeSettingsStore", "Failed to load privilege settings file", e)
        }
    }

    fun isEnabled(): Boolean = state.enabled

    fun shouldRenameInterface(): Boolean = state.interfaceRenameEnabled

    fun interfacePrefix(): String = state.interfacePrefix

    fun isUidSelected(uid: Int): Boolean {
        val cached = uidCache[uid]
        if (cached != null) {
            return cached
        }
        val selected = getPackagesForUid(uid).any { state.packageSet.contains(it) }
        uidCache[uid] = selected
        return selected
    }

    fun shouldHideUid(uid: Int): Boolean {
        if (!state.enabled) {
            return false
        }
        return isUidSelected(uid)
    }

    private fun normalizePrefix(prefix: String): String {
        val trimmed = prefix.trim()
        if (trimmed.isEmpty()) {
            return "en"
        }
        val filtered = buildString(trimmed.length) {
            for (ch in trimmed) {
                if (ch.isLetterOrDigit() || ch == '_') {
                    append(ch)
                }
            }
        }
        return if (filtered.isEmpty()) "en" else filtered
    }

    private fun parseFlag(properties: Properties, key: String): Boolean = when (properties.getProperty(key)) {
        "1" -> true
        "0" -> false
        else -> throw IllegalArgumentException("invalid $key")
    }

    private fun applyState(newState: State, action: String) {
        state = newState
        uidCache.clear()
        HookErrorStore.i(
            "PrivilegeSettingsStore",
            "PrivilegeSettings $action: enabled=${newState.enabled} size=${newState.packageSet.size} " +
                "rename=${newState.interfaceRenameEnabled} prefix=${newState.interfacePrefix}",
        )
    }

    private fun writeSettingsFile(state: State) {
        try {
            val dir = File(SETTINGS_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                HookErrorStore.e("PrivilegeSettingsStore", "Failed to create settings dir: ${dir.path}")
                return
            }
            val file = File(dir, SETTINGS_FILE)
            val packagesLine = state.packageSet.sorted().joinToString(",")
            val content = buildString {
                append("version=")
                append(SETTINGS_VERSION)
                append('\n')
                append("enabled=")
                append(if (state.enabled) "1" else "0")
                append('\n')
                append("rename=")
                append(if (state.interfaceRenameEnabled) "1" else "0")
                append('\n')
                append("prefix=")
                append(state.interfacePrefix)
                append('\n')
                append("packages=")
                append(packagesLine)
                append('\n')
            }
            // Replace the file atomically: a truncated file would be preferred over the
            // legacy one on the next boot and fail to parse.
            val tempFile = File(dir, "$SETTINGS_FILE.tmp")
            try {
                FileOutputStream(tempFile).use { output ->
                    output.write(content.toByteArray())
                    output.fd.sync()
                }
                tempFile.setReadable(true, true)
                tempFile.setWritable(true, true)
                if (!tempFile.renameTo(file)) {
                    throw IOException("Failed to rename ${tempFile.path} to ${file.path}")
                }
            } finally {
                tempFile.delete()
            }
        } catch (e: Throwable) {
            HookErrorStore.e("PrivilegeSettingsStore", "Failed to write privilege settings file", e)
        }
    }

    private data class State(
        val enabled: Boolean = false,
        val packageSet: Set<String> = emptySet(),
        val interfaceRenameEnabled: Boolean = false,
        val interfacePrefix: String = "en",
    )

    private fun getPackagesForUid(uid: Int): List<String> {
        val pm = getPackageManager() ?: return emptyList()
        return try {
            val method = getPackagesForUidMethod ?: run {
                pm.javaClass.getMethod("getPackagesForUid", Int::class.javaPrimitiveType).also {
                    getPackagesForUidMethod = it
                }
            }
            val result = method.invoke(pm, uid)
            when (result) {
                is Array<*> -> result.filterIsInstance<String>()
                is List<*> -> result.filterIsInstance<String>()
                else -> emptyList()
            }
        } catch (e: Throwable) {
            HookErrorStore.e("PrivilegeSettingsStore", "getPackagesForUid failed for uid=$uid", e)
            emptyList()
        }
    }

    private fun getPackageManager(): Any? = try {
        getPackageManagerMethod.invoke(null)
    } catch (e: Throwable) {
        HookErrorStore.e("PrivilegeSettingsStore", "getPackageManager failed", e)
        null
    }
}
