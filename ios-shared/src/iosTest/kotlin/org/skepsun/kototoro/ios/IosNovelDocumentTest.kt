package org.skepsun.kototoro.ios

import org.skepsun.kototoro.reader.novel.novelHtmlToStyledText
import org.skepsun.kototoro.reader.novel.novelProgressScroll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosNovelDocumentTest {
    private val html = (1..40).joinToString("") { "<p>第${it}段正文内容。</p>" } + "<img src=\"https://example.com/a.jpg\">"
    private val document = IosNovelDocument(novelHtmlToStyledText(html))

    @Test
    fun linesAndPicturesKeepTheirOrder() {
        assertEquals(41, document.blocks.size)
        assertEquals("第1段正文内容。", document.blocks.first().text)
        assertNull(document.blocks.last().text)
        assertEquals("https://example.com/a.jpg", document.blocks.last().imageUrl)
    }

    @Test
    fun ownPositionsRoundTripThroughTheStoredProgress() {
        for (index in document.blocks.indices) {
            assertEquals(index, document.index(document.scroll(index), 0))
        }
        assertEquals(0f, document.scroll(0))
    }

    @Test
    fun androidChapterProgressPlacesTheMatchingLine() {
        // Android stores its page progress (page / (pages - 1)); halfway through lands mid-chapter here.
        val scroll = novelProgressScroll(0.5f).toFloat()
        assertEquals(21, document.index(scroll, 3))
    }

    @Test
    fun recordsWithoutProgressKeepTheirLineIndex() {
        assertEquals(7, document.index(0f, 7))
        assertEquals(40, document.index(0f, 400))
    }

    @Test
    fun bookStylesMapToThePagedText() {
        val styled = IosNovelDocument(novelHtmlToStyledText(
            "<h1>序章</h1><p>正文<b>粗体</b></p><p style=\"text-indent:0\"><ruby>漢<rt>かん</rt></ruby>字</p>"))
        val pages = styled.pages(indent = true, spacingLines = 0, bookStyles = true)
        val text = pages.text(0)!!
        // The heading and the paragraph without indent keep flush; the body paragraph is indented.
        assertEquals("序章\n　　正文粗体\n漢字", text)
        val bold = pages.styles(0).single { it.bold && !it.italic && it.scale == 1f }
        assertEquals("粗体", text.substring(bold.start, bold.end))
        val ruby = pages.styles(0).single { it.ruby != null }
        assertEquals("かん", ruby.ruby)
        assertEquals("漢", text.substring(ruby.start, ruby.end))
        val lines = pages.paragraphs(0)
        assertEquals(listOf(1, 0, 0), lines.map { it.heading })
        assertEquals(2f, lines[0].scale)
        assertEquals(listOf(false, false, true), lines.map { it.ruby })
        assertEquals("　　正文粗体", text.substring(lines[1].start, lines[1].end))

        val plain = styled.pages(indent = true, spacingLines = 0, bookStyles = false)
        assertEquals("　　序章\n　　正文粗体\n　　漢字", plain.text(0))
        assertEquals(emptyList(), plain.styles(0))
    }
}
