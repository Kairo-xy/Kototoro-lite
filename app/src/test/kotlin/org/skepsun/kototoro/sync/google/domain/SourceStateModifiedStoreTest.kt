package org.skepsun.kototoro.sync.google.domain

import android.content.Context
import androidx.room.withTransaction
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.dao.MangaSourcesDao
import org.skepsun.kototoro.core.db.entity.MangaSourceEntity

class SourceStateModifiedStoreTest {
    private val prefs = MemorySyncPreferences()
    private val context = mockk<Context> {
        every { getSharedPreferences("sync_source_state_meta", Context.MODE_PRIVATE) } returns prefs
    }
    private val store = SourceStateModifiedStore(context)
    private val original = MangaSourceEntity("source", false, 0, 1, 0, false, 0)

    @Test
    fun `snapshotting does not turn an old edit or usage into a newer edit`() {
        store.recordApplied(original.source, original.isEnabled, original.isPinned, original.sortKey, 10)
        assertEquals(10L, store.modifiedAt(listOf(original.copy(lastUsedAt = 100)))[original.source])
        assertEquals(10L, store.modifiedAt(listOf(original))[original.source])
        assertEquals(0L, store.modifiedAt(listOf(original.copy(isEnabled = true)))[original.source])
        assertEquals(10L, store.modifiedAt(listOf(original))[original.source])
    }

    @Test
    fun `local edits are timestamped on commit and no-op edits preserve their version`() = runTest {
        withTransactionStub { database, read, write ->
            store.withLocalChanges(database, setOf(original.source)) { write(read().copy(isEnabled = true)) }
            val editedAt = store.modifiedAt(listOf(read())).getValue(original.source)
            assertTrue(editedAt > 0)
            store.withLocalChanges(database) { write(read().copy(lastUsedAt = editedAt + 10)) }
            assertEquals(editedAt, store.modifiedAt(listOf(read()))[original.source])
            store.withLocalChanges(database) { write(read().copy(isEnabled = true)) }
            assertEquals(editedAt, store.modifiedAt(listOf(read()))[original.source])
        }
    }

    @Test
    fun `a transaction failure after its body leaves no phantom committed metadata`() = runTest {
        store.recordApplied(original.source, original.isEnabled, original.isPinned, original.sortKey, 10)
        withTransactionStub(failCommit = true) { database, read, write ->
            val error = runCatching {
                store.withLocalChanges(database) { write(read().copy(isEnabled = true)) }
            }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertEquals(original, read())
            assertEquals(10L, store.modifiedAt(listOf(original))[original.source])
        }
    }

    @Test
    fun `cancelled state operations release the mutex`() = runTest {
        val error = runCatching { store.withStateLock { throw CancellationException() } }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(42, store.withStateLock { 42 })
    }

    /** Transaction adapter isolates commit sequencing; this is not a SQLite integration test. */
    private suspend fun withTransactionStub(
        failCommit: Boolean = false,
        action: suspend (MangaDatabase, () -> MangaSourceEntity, (MangaSourceEntity) -> Unit) -> Unit,
    ) {
        var entity = original
        val dao = mockk<MangaSourcesDao> {
            coEvery { findAll() } answers { listOf(entity) }
            coEvery { find(original.source) } answers { entity }
        }
        val database = mockk<MangaDatabase> { every { getSourcesDao() } returns dao }
        mockkStatic("androidx.room.RoomDatabaseKt")
        try {
            coEvery { database.withTransaction(any<suspend () -> Any?>()) } coAnswers {
                val before = entity
                try {
                    val result = secondArg<suspend () -> Any?>().invoke()
                    if (failCommit) error("Commit failed")
                    result
                } catch (e: Throwable) {
                    entity = before
                    throw e
                }
            }
            action(database, { entity }, { entity = it })
        } finally {
            unmockkStatic("androidx.room.RoomDatabaseKt")
        }
    }
}
