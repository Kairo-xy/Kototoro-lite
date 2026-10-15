package org.skepsun.kototoro.sync.google.domain

import android.content.SharedPreferences

/** In-memory preference values and real listener delivery for sync-store tests. */
internal class MemorySyncPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any>()
    private val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val pendingNotifications = mutableListOf<() -> Unit>()
    var deferNotifications = false
    var notifyOnClear = true

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = key in values
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners += listener
    }
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners -= listener
    }
    override fun edit(): SharedPreferences.Editor = Editor()

    fun dispatchNotifications() {
        val pending = pendingNotifications.toList()
        pendingNotifications.clear()
        pending.forEach { it() }
    }

    private inner class Editor : SharedPreferences.Editor {
        private val writes = linkedMapOf<String, Any?>()
        private var cleared = false

        override fun putString(key: String, value: String?) = apply { writes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { writes[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { writes[key] = value }
        override fun putLong(key: String, value: Long) = apply { writes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { writes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { writes[key] = value }
        override fun remove(key: String) = apply { writes[key] = null }
        override fun clear() = apply { cleared = true }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            val changed = mutableListOf<String?>()
            if (cleared) {
                values.clear()
                if (notifyOnClear) changed.add(null) // Android 11+ delivers a null key on clear().
            }
            writes.forEach { (key, value) ->
                if (values[key] != value) changed += key
                if (value == null) values.remove(key) else values[key] = value
            }
            val notify = {
                changed.forEach { key ->
                    listeners.toList().forEach { it.onSharedPreferenceChanged(this@MemorySyncPreferences, key) }
                }
            }
            if (deferNotifications) pendingNotifications += notify else notify()
        }
    }
}
