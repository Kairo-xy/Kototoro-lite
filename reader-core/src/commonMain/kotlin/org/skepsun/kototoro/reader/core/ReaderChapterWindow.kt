package org.skepsun.kototoro.reader.core

/** Reading-order window: previous tail, the complete active chapter, then next head. */
object ReaderChapterWindow {
    fun <T> pages(previous: List<T>, current: List<T>, next: List<T>, adjacentPageCount: Int): List<T> {
        require(adjacentPageCount >= 0)
        return buildList {
            addAll(previous.takeLast(adjacentPageCount))
            addAll(current)
            addAll(next.take(adjacentPageCount))
        }
    }
}
