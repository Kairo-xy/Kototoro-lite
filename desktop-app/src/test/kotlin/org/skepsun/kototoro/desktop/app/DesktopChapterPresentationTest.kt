package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.core.ui.chapters.ChapterSection

class DesktopChapterPresentationTest {
    private fun chapter(id: Long, number: Float, title: String? = null, volume: Int = 0) =
        SourceChapter(id, title, number, volume, "fixture:$id", null, 0L, null, SourceRef("fixture", "zh", "MANGA"))

    @Test
    fun `unknown numbers keep unnamed chapters visible and search uses Android number and volume labels`() {
        val unknown = chapter(1, 0f)
        val negative = chapter(2, -1f)
        val decimal = chapter(3, 12.5f)
        val volume = chapter(4, 0f, volume = 7)
        val named = chapter(5, 1f, "The WIND")
        val chapters = listOf(unknown, negative, decimal, volume, named)
        fun visible(query: String) = desktopChapterList(chapters, null, query, false).sections
            .filterIsInstance<ChapterSection.Item<SourceChapter>>().map { it.chapter }
        assertEquals(chapters, visible(""))
        assertEquals(listOf(decimal), visible("12.5"))
        assertEquals(listOf(volume), visible("7"))
        assertEquals(listOf(named), visible("wind"))
        assertTrue(visible("0").isEmpty())
        assertNull(unknown.numberLabel())
        assertEquals(AndroidStrings["unnamed_chapter"], desktopChapterTitle(unknown))
        assertEquals(AndroidStrings["unnamed_chapter"], desktopChapterTitle(negative))
        assertEquals(AndroidStrings["chapter_number"].format("12.5"), desktopChapterTitle(decimal))
    }

    @Test
    fun `download and current chapter identity survives metadata updates but distinguishes branches and urls`() {
        val original = chapter(1, 1f, "Old title")
        assertTrue(DesktopDownloadStore.sameChapter(original, original.copy(title = "New title", uploadDate = 5L)))
        assertFalse(DesktopDownloadStore.sameChapter(original, original.copy(branch = "Another translation")))
        assertFalse(DesktopDownloadStore.sameChapter(original, original.copy(url = "fixture:replaced")))
        assertFalse(DesktopDownloadStore.sameChapter(original, original.copy(source = original.source.copy(name = "another"))))
    }
}
