package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ReaderChapterWindowTest {
    @Test
    fun `includes only adjacent tail and head in reading order`() {
        assertEquals(listOf("p2", "p3", "c0", "c1", "n0", "n1"), ReaderChapterWindow.pages(
            listOf("p0", "p1", "p2", "p3"), listOf("c0", "c1"), listOf("n0", "n1", "n2"), 2,
        ))
    }

    @Test
    fun `book ends and short chapters require no synthetic pages`() {
        assertEquals(listOf(1, 2), ReaderChapterWindow.pages(emptyList(), listOf(1), listOf(2), 2))
        assertEquals(listOf(1, 2), ReaderChapterWindow.pages(listOf(1), listOf(2), emptyList(), 2))
        assertEquals(listOf(2), ReaderChapterWindow.pages(listOf(1), listOf(2), listOf(3), 0))
        assertThrows(IllegalArgumentException::class.java) {
            ReaderChapterWindow.pages(listOf(1), listOf(2), listOf(3), -1)
        }
    }
}
