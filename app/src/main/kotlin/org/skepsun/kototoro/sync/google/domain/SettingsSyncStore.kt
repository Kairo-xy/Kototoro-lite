package org.skepsun.kototoro.sync.google.domain

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.core.prefs.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsSyncStore @Inject constructor(
	@ApplicationContext context: Context,
	appSettings: AppSettings,
) {

	private val stores: Map<String, SharedPreferences> = linkedMapOf(
		STORE_APP to appSettings.prefs,
		STORE_TAP_GRID to context.getSharedPreferences("tap_grid", Context.MODE_PRIVATE),
		STORE_NOVEL_READER to context.getSharedPreferences("novel_reader_settings", Context.MODE_PRIVATE),
		// Source locks (per-source enabled state lock): name -> true/false, "none" = unlocked marker.
		STORE_SOURCE_LOCKS to context.getSharedPreferences("source_locks", Context.MODE_PRIVATE),
	)
	private val meta = context.getSharedPreferences("sync_settings_meta", Context.MODE_PRIVATE)

	/**
	 * Snapshot of the local settings with per-key modification times.
	 *
	 * First-sync policy: on the very first run every existing key is recorded with `at = 0`
	 * (initial/default state), so any remote entry wins over it. After that, a key that was never seen
	 * before is a new user change and gets `at = now`. Keys that disappeared from the preferences
	 * (reset to default) are emitted as tombstones so that a reset converges instead of being undone.
	 */
	fun buildLocal(now: Long): Map<String, Map<String, SettingEntry>> {
		val metaUpdates = HashMap<String, String>()
		val initialized = meta.getString(META_INITIALIZED, null) == "true"
		val metaAll = meta.all
		val result = LinkedHashMap<String, Map<String, SettingEntry>>()
		for ((store, prefs) in stores) {
			val entries = LinkedHashMap<String, SettingEntry>()
			val current = prefs.all
			for ((key, value) in current) {
				if (!SettingsSyncMerger.isSyncableKey(key)) continue
				val encoded = encode(value) ?: continue
				val metaKey = "$store/$key"
				val hash = hashOf(encoded)
				val stored = metaAll[metaKey] as? String
				val storedHash = stored?.substringBefore('|')?.toIntOrNull()
				val storedAt = stored?.substringAfter('|', "")?.toLongOrNull()
				val at = when {
					stored == null -> {
						val initial = if (initialized) now else 0L
						metaUpdates[metaKey] = "$hash|$initial"
						initial
					}
					storedHash == hash && storedAt != null -> storedAt
					else -> {
						// Changed locally, or re-created after a deletion.
						metaUpdates[metaKey] = "$hash|$now"
						now
					}
				}
				entries[key] = SettingEntry(t = encoded.first, v = encoded.second, at = at)
			}
			val prefix = "$store/"
			for ((metaKey, stored) in metaAll) {
				if (!metaKey.startsWith(prefix) || stored !is String) continue
				val key = metaKey.removePrefix(prefix)
				if (key in current || !SettingsSyncMerger.isSyncableKey(key)) continue
				val deletedAt = if (stored.startsWith(DELETED_PREFIX)) {
					stored.removePrefix(DELETED_PREFIX).toLongOrNull() ?: now
				} else {
					now.also { metaUpdates[metaKey] = "$DELETED_PREFIX$now" }
				}
				entries[key] = SettingEntry.deleted(deletedAt)
			}
			result[store] = entries
		}
		if (!initialized) metaUpdates[META_INITIALIZED] = "true"
		saveMeta(metaUpdates)
		return result
	}

	fun apply(merged: Map<String, Map<String, SettingEntry>>): Int {
		val metaUpdates = HashMap<String, String>()
		var applied = 0
		for ((store, entries) in merged) {
			val prefs = stores[store] ?: continue
			val localValues = prefs.all
			val writes = LinkedHashMap<String, Any>()
			val removals = ArrayList<String>()
			for ((key, entry) in entries) {
				if (!SettingsSyncMerger.isSyncableKey(key)) continue
				if (entry.isDeleted) {
					if (localValues.containsKey(key)) removals += key
					metaUpdates["$store/$key"] = "$DELETED_PREFIX${entry.at}"
					continue
				}
				if (encode(localValues[key]) == (entry.t to entry.v)) continue
				val value = decode(entry) ?: continue
				val encoded = encode(value) ?: continue
				writes[key] = value
				metaUpdates["$store/$key"] = "${hashOf(encoded)}|${entry.at}"
			}
			if (writes.isEmpty() && removals.isEmpty()) continue
			prefs.edit {
				removals.forEach { remove(it) }
				for ((key, value) in writes) {
					when (value) {
						is Boolean -> putBoolean(key, value)
						is Int -> putInt(key, value)
						is Long -> putLong(key, value)
						is Float -> putFloat(key, value)
						is String -> putString(key, value)
						is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
					}
				}
			}
			applied += writes.size + removals.size
		}
		saveMeta(metaUpdates)
		return applied
	}

	private fun encode(value: Any?): Pair<String, String>? {
		return when (value) {
			is Boolean -> "b" to value.toString()
			is Int -> "i" to value.toString()
			is Long -> "l" to value.toString()
			is Float -> "f" to value.toString()
			is String -> "s" to value
			is Set<*> -> {
				val strings = value.filterIsInstance<String>()
				if (strings.size != value.size) {
					null
				} else {
					"ss" to Json.encodeToString(ListSerializer(String.serializer()), strings.sorted())
				}
			}
			else -> null
		}
	}

	private fun decode(entry: SettingEntry): Any? = runCatching {
		when (entry.t) {
			"b" -> entry.v.toBooleanStrict()
			"i" -> entry.v.toInt()
			"l" -> entry.v.toLong()
			"f" -> entry.v.toFloat()
			"s" -> entry.v
			"ss" -> Json.decodeFromString(ListSerializer(String.serializer()), entry.v).toSet()
			else -> null
		}
	}.getOrNull()

	private fun hashOf(encoded: Pair<String, String>): Int = (encoded.first + ":" + encoded.second).hashCode()

	private fun saveMeta(updates: Map<String, String>) {
		if (updates.isEmpty()) return
		meta.edit {
			for ((key, value) in updates) {
				putString(key, value)
			}
		}
	}

	private companion object {
		const val META_INITIALIZED = "__initialized__"
		const val DELETED_PREFIX = "d|"
		const val STORE_APP = "app"
		const val STORE_TAP_GRID = "tap_grid"
		const val STORE_NOVEL_READER = "novel_reader"
		const val STORE_SOURCE_LOCKS = "source_locks"
	}
}
