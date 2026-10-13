package org.skepsun.kototoro.reader.novel

/**
 * Splits a measured chapter into pages of whole lines. The platform supplies each line's top and bottom; a line
 * taller than a page still gets a page of its own, so pagination always advances.
 */
fun splitNovelPageLineRanges(
    lineCount: Int,
    pageHeightPx: Int,
    lineTop: (Int) -> Float,
    lineBottom: (Int) -> Float,
): List<IntRange> {
    if (lineCount <= 0 || pageHeightPx <= 0) return emptyList()
    return buildList {
        var firstLine = 0
        while (firstLine < lineCount) {
            val pageTop = lineTop(firstLine)
            var lastLine = firstLine
            while (lastLine + 1 < lineCount && lineBottom(lastLine + 1) - pageTop <= pageHeightPx) {
                lastLine++
            }
            add(firstLine..lastLine)
            firstLine = lastLine + 1
        }
    }
}
