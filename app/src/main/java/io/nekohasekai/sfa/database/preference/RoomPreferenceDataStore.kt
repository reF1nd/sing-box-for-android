package io.nekohasekai.sfa.database.preference

import androidx.preference.PreferenceDataStore
import java.util.concurrent.ConcurrentHashMap

@Suppress("MemberVisibilityCanBePrivate", "unused")
open class RoomPreferenceDataStore(private val kvPairDao: KeyValueEntity.Dao) : PreferenceDataStore() {
    private val cache = ConcurrentHashMap<String, KeyValueEntity>()

    @Volatile
    private var loaded = false
    private val loadLock = Any()

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            kvPairDao.all().forEach { cache.putIfAbsent(it.key, it) }
            loaded = true
        }
    }

    private fun entity(key: String): KeyValueEntity? {
        ensureLoaded()
        return cache[key]
    }

    private fun store(entity: KeyValueEntity) {
        synchronized(loadLock) {
            cache[entity.key] = entity
            kvPairDao.put(entity)
        }
    }

    fun getBoolean(key: String) = entity(key)?.boolean

    fun getFloat(key: String) = entity(key)?.float

    fun getInt(key: String) = entity(key)?.long?.toInt()

    fun getLong(key: String) = entity(key)?.long

    fun getString(key: String) = entity(key)?.string

    fun getStringSet(key: String) = entity(key)?.stringSet

    fun reset() {
        synchronized(loadLock) {
            cache.clear()
            kvPairDao.reset()
            loaded = true
        }
    }

    override fun getBoolean(key: String, defValue: Boolean) = getBoolean(key) ?: defValue

    override fun getFloat(key: String, defValue: Float) = getFloat(key) ?: defValue

    override fun getInt(key: String, defValue: Int) = getInt(key) ?: defValue

    override fun getLong(key: String, defValue: Long) = getLong(key) ?: defValue

    override fun getString(key: String, defValue: String?) = getString(key) ?: defValue

    override fun getStringSet(key: String, defValue: MutableSet<String>?) = getStringSet(key) ?: defValue

    fun putBoolean(key: String, value: Boolean?) = if (value == null) remove(key) else putBoolean(key, value)

    fun putFloat(key: String, value: Float?) = if (value == null) remove(key) else putFloat(key, value)

    fun putInt(key: String, value: Int?) = if (value == null) remove(key) else putLong(key, value.toLong())

    fun putLong(key: String, value: Long?) = if (value == null) remove(key) else putLong(key, value)

    override fun putBoolean(key: String, value: Boolean) {
        store(KeyValueEntity(key).put(value))
        fireChangeListener(key)
    }

    override fun putFloat(key: String, value: Float) {
        store(KeyValueEntity(key).put(value))
        fireChangeListener(key)
    }

    override fun putInt(key: String, value: Int) {
        store(KeyValueEntity(key).put(value.toLong()))
        fireChangeListener(key)
    }

    override fun putLong(key: String, value: Long) {
        store(KeyValueEntity(key).put(value))
        fireChangeListener(key)
    }

    override fun putString(key: String, value: String?) = if (value == null) {
        remove(key)
    } else {
        store(KeyValueEntity(key).put(value))
        fireChangeListener(key)
    }

    override fun putStringSet(key: String, values: MutableSet<String>?) = if (values == null) {
        remove(key)
    } else {
        store(KeyValueEntity(key).put(values))
        fireChangeListener(key)
    }

    fun remove(key: String) {
        synchronized(loadLock) {
            cache.remove(key)
            kvPairDao.delete(key)
        }
        fireChangeListener(key)
    }

    private val listeners = HashSet<OnPreferenceDataStoreChangeListener>()

    private fun fireChangeListener(key: String) {
        val listeners =
            synchronized(listeners) {
                listeners.toList()
            }
        listeners.forEach { it.onPreferenceDataStoreChanged(this, key) }
    }

    fun registerChangeListener(listener: OnPreferenceDataStoreChangeListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun unregisterChangeListener(listener: OnPreferenceDataStoreChangeListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }
}
