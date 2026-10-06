package io.github.crockalet.haunt.android.settings

import android.content.SharedPreferences

/**
 * Minimal typed key/value storage behind [SettingsStore], so the settings mapping can be unit-tested
 * on the JVM without Android's SharedPreferences.
 */
interface KeyValueStore {
    fun contains(key: String): Boolean
    fun getString(key: String): String?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getFloat(key: String, default: Float): Float
    fun getLong(key: String, default: Long): Long

    /** Applies all [values] at once; a null value removes the key. Values are String, Boolean, Float or Long. */
    fun putAll(values: Map<String, Any?>)
}

/** [KeyValueStore] backed by [SharedPreferences] (writes with `apply()`). */
class SharedPreferencesKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun contains(key: String) = prefs.contains(key)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun getFloat(key: String, default: Float) = prefs.getFloat(key, default)
    override fun getLong(key: String, default: Long) = prefs.getLong(key, default)

    override fun putAll(values: Map<String, Any?>) {
        val editor = prefs.edit()
        for ((key, value) in values) {
            when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Long -> editor.putLong(key, value)
                else -> throw IllegalArgumentException("Unsupported value type for $key: ${value::class}")
            }
        }
        editor.apply()
    }
}

/** In-memory [KeyValueStore] (tests, previews). */
class InMemoryKeyValueStore(initial: Map<String, Any?> = emptyMap()) : KeyValueStore {
    private val map = HashMap<String, Any>().apply { initial.forEach { (k, v) -> if (v != null) put(k, v) } }

    val snapshot: Map<String, Any> @Synchronized get() = HashMap(map)

    @Synchronized override fun contains(key: String) = key in map
    @Synchronized override fun getString(key: String) = map[key] as? String
    @Synchronized override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
    @Synchronized override fun getFloat(key: String, default: Float) = map[key] as? Float ?: default
    @Synchronized override fun getLong(key: String, default: Long) = map[key] as? Long ?: default

    @Synchronized
    override fun putAll(values: Map<String, Any?>) {
        values.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
    }
}
