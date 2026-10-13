package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.novel.buildNovelDocument
import org.skepsun.kototoro.reader.novel.novelHtmlToStyledText

class NovelBookStyleTextTest {

	private val styled = novelHtmlToStyledText(
		"<style>h1 { text-align: center }</style><h1>第一章 开始</h1><p>正文<b>粗体</b>。</p><p>改过的段落</p>",
	)

	@Test
	fun `blocks found in the book get its styles and changed blocks stay plain`() {
		// The reader's text after a replacement rule changed the last paragraph.
		val blocks = buildNovelDocument("第一章 开始\n\n正文粗体。\n\n替换后的段落")
		val styledBlocks = styleNovelBlocks(blocks, styled, indentEnabled = true, spacingLines = 0)

		assertEquals(setOf("text-0", "text-1"), styledBlocks.keys)
		val body = styledBlocks.getValue("text-1")
		assertEquals("　　正文粗体。", body.text)
	}

	@Test
	fun `annotated text carries the alignment, heading size and emphasis`() {
		val blocks = buildNovelDocument("第一章 开始\n\n正文粗体。")
		val styledBlocks = styleNovelBlocks(blocks, styled, indentEnabled = true, spacingLines = 0)
		val base = TextStyle(fontSize = 17.sp, lineHeight = 27.sp)

		val heading = styledBlocks.getValue("text-0").toAnnotatedString(base)
		val paragraph = heading.paragraphStyles.single()
		assertEquals(TextAlign.Center, paragraph.item.textAlign)
		assertEquals(54f, paragraph.item.lineHeight.value, 0.01f)
		assertTrue(heading.spanStyles.any { it.item.fontSize == 34.sp && it.item.fontWeight == FontWeight.Bold })

		val body = styledBlocks.getValue("text-1").toAnnotatedString(base)
		val bold = body.spanStyles.single()
		assertEquals("粗体", body.text.substring(bold.start, bold.end))
		assertEquals(FontWeight.Bold, bold.item.fontWeight)
		assertTrue(body.paragraphStyles.isEmpty())
	}
}
