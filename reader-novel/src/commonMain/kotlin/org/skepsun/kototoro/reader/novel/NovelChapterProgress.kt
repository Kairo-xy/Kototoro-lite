package org.skepsun.kototoro.reader.novel

import kotlin.math.roundToInt

/**
 * A novel position is stored portably as the chapter progress in the history `scroll` field, in units of
 * 1/[NOVEL_PROGRESS_SCALE]. Page and line numbers depend on the reader and its layout, so readers restore from this
 * value and only fall back to their own index when it is 0 (the chapter start, or a record written before it).
 */
const val NOVEL_PROGRESS_SCALE = 10_000

/** [progress] (0..1) as the stored value; any position past the start stays above 0 so it is never read as legacy. */
fun novelProgressScroll(progress: Float): Int {
    val value = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    if (value <= 0f) return 0
    return (value * NOVEL_PROGRESS_SCALE).roundToInt().coerceIn(1, NOVEL_PROGRESS_SCALE)
}

/** The chapter progress a stored value describes, or null when it carries none. */
fun novelScrollProgress(scroll: Float): Float? =
    if (scroll.isFinite() && scroll > 0f) (scroll / NOVEL_PROGRESS_SCALE).coerceIn(0f, 1f) else null

/** The page of a chapter split into [pageCount] pages that holds [progress]. */
fun novelPageForProgress(progress: Float, pageCount: Int): Int {
    if (pageCount <= 1) return 0
    val value = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    return (value * pageCount).toInt().coerceIn(0, pageCount - 1)
}

/**
 * Character [offset] of a chapter text of [length] characters as the stored value. Rounded up, so that
 * [novelScrollOffset] lands on the same character or at most one unit (length / 10000 characters) later, never in
 * the preceding line. Lines are restored exactly while they are longer than one unit.
 */
fun novelOffsetScroll(offset: Int, length: Int): Int {
    if (length <= 0 || offset <= 0) return 0
    val clamped = minOf(offset, length).toLong()
    return ((clamped * NOVEL_PROGRESS_SCALE + length - 1) / length).toInt()
}

/** The character a stored value points at in a chapter text of [length] characters, or null when it carries none. */
fun novelScrollOffset(scroll: Float, length: Int): Int? {
    if (!scroll.isFinite() || scroll <= 0f) return null
    val units = scroll.toLong().coerceIn(0, NOVEL_PROGRESS_SCALE.toLong())
    return (units * length.coerceAtLeast(0) / NOVEL_PROGRESS_SCALE).toInt()
}

/**
 * The block of [blocks] (from [buildNovelLineDocument] or [buildNovelDocument]) shown at character [offset]: the last
 * block starting at or before it. Blocks without a source range are skipped; null when there is no positioned block.
 */
fun novelBlockAtOffset(blocks: List<NovelDocumentBlock>, offset: Int): Int? {
    var found: Int? = null
    blocks.forEachIndexed { index, block ->
        val start = block.sourceRange?.first ?: return@forEachIndexed
        if (start <= offset || found == null) found = index
        if (start > offset) return found
    }
    return found
}
