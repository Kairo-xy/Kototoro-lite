package org.skepsun.kototoro.sync.google.domain

import android.content.Context
import androidx.core.content.edit
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.reader.data.TapGridSettings
import org.skepsun.kototoro.reader.ui.tapgrid.TapGridConfig

class SettingsSyncStoreTest {
    private val preferences = mutableMapOf<String, MemorySyncPreferences>()
    private val app = prefs("app")
    private val context = mockk<Context> {
        every { getSharedPreferences(any(), any()) } answers { prefs(firstArg()) }
    }
    private val appSettings = mockk<AppSettings> { every { prefs } returns app }
    private val store = SettingsSyncStore(context, appSettings)

    private fun prefs(name: String) = preferences.getOrPut(name) { MemorySyncPreferences() }
    private fun theme() = store.buildLocal().getValue("app").getValue("theme")

    @Test
    fun `offline edits retain their edit time through later snapshots`() {
        app.edit { putString("theme", "initial") }
        assertEquals(0L, theme().at)
        val beforeEdit = System.currentTimeMillis()
        app.edit { putString("theme", "dark") }
        val afterEdit = System.currentTimeMillis()
        val edited = theme()
        assertTrue(edited.at in beforeEdit..afterEdit)
        assertTrue(edited.at > 0L)
        assertEquals(edited, theme())
        val newerRemote = SettingEntry("s", "light", edited.at + 1)
        val merged = SettingsSyncMerger.merge(mapOf("theme" to theme()), mapOf("theme" to newerRemote))
        assertEquals(newerRemote, merged["theme"])
    }

    @Test
    fun `removal and clear retain tombstones at the reset time`() {
        app.edit { putString("theme", "dark") }
        store.buildLocal()
        val beforeReset = System.currentTimeMillis()
        app.edit { remove("theme") }
        val reset = theme()
        assertTrue(reset.isDeleted)
        assertTrue(reset.at >= beforeReset)
        assertEquals(reset, theme())
        app.edit { putString("reader_mode", "paged") }
        app.edit { clear() }
        assertTrue(store.buildLocal().getValue("app").getValue("reader_mode").isDeleted)
    }

    @Test
    fun `remote application and deferred callbacks preserve remote timestamps`() {
        store.buildLocal()
        app.deferNotifications = true
        assertEquals(1, store.apply(mapOf("app" to mapOf("theme" to SettingEntry("s", "dark", 50)))))
        app.dispatchNotifications()
        assertEquals(50L, theme().at)
        assertEquals(0, store.apply(mapOf("app" to mapOf("theme" to SettingEntry("s", "dark", 100)))))
        assertEquals(100L, theme().at)
    }

    @Test
    fun `an edit made while a remote snapshot is in flight is not overwritten`() {
        store.buildLocal()
        app.edit { putString("theme", "offline") }
        val local = theme()
        assertEquals(0, store.apply(mapOf("app" to mapOf("theme" to SettingEntry("s", "remote", local.at - 1)))))
        assertEquals(local, theme())
    }

    @Test
    fun `listeners resume from persisted metadata after process restart`() {
        store.buildLocal()
        val restarted = SettingsSyncStore(context, appSettings)
        app.edit { putString("theme", "offline") }
        val local = restarted.buildLocal().getValue("app").getValue("theme")
        assertTrue(local.at > 0)
        assertEquals(local, theme())
    }

    @Test
    fun `tap grid disable all records resets on Android versions without clear callbacks`() {
        val grid = TapGridSettings(context)
        val initial = store.buildLocal().getValue("tap_grid").keys - TapGridConfig.KEY_INIT
        assertTrue(initial.isNotEmpty())
        prefs("tap_grid").notifyOnClear = false
        val beforeReset = System.currentTimeMillis()
        grid.disableAll()
        val reset = store.buildLocal().getValue("tap_grid")
        assertTrue(initial.all { reset[it]?.isDeleted == true })
        assertTrue(initial.all { reset.getValue(it).at >= beforeReset })
        assertTrue(prefs("tap_grid").getBoolean(TapGridConfig.KEY_INIT, false))
    }

    @Test
    fun `credentials are never applied or added to a local snapshot`() {
        app.edit { putString("app_password", "device-only") }
        assertEquals(0, store.apply(mapOf("app" to mapOf("app_password" to SettingEntry("s", "remote", 100)))))
        assertEquals("device-only", app.getString("app_password", null))
        assertFalse(store.buildLocal().getValue("app").containsKey("app_password"))
    }
}
