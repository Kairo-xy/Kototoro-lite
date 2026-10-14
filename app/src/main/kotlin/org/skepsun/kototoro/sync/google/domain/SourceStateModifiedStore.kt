package org.skepsun.kototoro.sync.google.domain

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import org.skepsun.kototoro.core.db.entity.MangaSourceEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks when each source's enabled/pinned/sort state was last modified, independently of
 * [MangaSourceEntity.lastUsedAt] (which only changes when a source is used).
 *
 * First-sync policy mirrors [SettingsSyncStore]: on the first run existing sources are recorded at 0 (initial
 * state), afterwards a state that differs from the recorded one is a new change stamped with the current time.
 */
@Singleton
class SourceStateModifiedStore @Inject constructor(
	@ApplicationContext context: Context,
) {

	private val prefs = context.getSharedPreferences("sync_source_state_meta", Context.MODE_PRIVATE)

	fun modifiedAt(entities: List<MangaSourceEntity>, now: Long): Map<String, Long> {
		val initialized = prefs.getBoolean(KEY_INITIALIZED, false)
		val updates = HashMap<String, String>()
		val result = HashMap<String, Long>(entities.size)
		for (entity in entities) {
			val hash = stateHash(entity.isEnabled, entity.isPinned, entity.sortKey)
			val stored = prefs.getString(entity.source, null)
			val storedHash = stored?.substringBefore('|')?.toIntOrNull()
			val storedAt = stored?.substringAfter('|', "")?.toLongOrNull()
			val at = when {
				stored == null -> (if (initialized) now else 0L).also { updates[entity.source] = "$hash|$it" }
				storedHash == hash && storedAt != null -> storedAt
				else -> now.also { updates[entity.source] = "$hash|$it" }
			}
			result[entity.source] = at
		}
		prefs.edit {
			updates.forEach { (key, value) -> putString(key, value) }
			if (!initialized) putBoolean(KEY_INITIALIZED, true)
		}
		return result
	}

	/** Records a state received from the remote so that applying it is not mistaken for a local change. */
	fun recordApplied(source: String, enabled: Boolean, pinned: Boolean, sortKey: Int, modifiedAt: Long) {
		prefs.edit { putString(source, "${stateHash(enabled, pinned, sortKey)}|$modifiedAt") }
	}

	private fun stateHash(enabled: Boolean, pinned: Boolean, sortKey: Int): Int =
		"$enabled:$pinned:$sortKey".hashCode()

	private companion object {
		const val KEY_INITIALIZED = "__initialized__"
	}
}
