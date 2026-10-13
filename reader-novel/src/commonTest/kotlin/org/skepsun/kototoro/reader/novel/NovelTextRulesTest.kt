package org.skepsun.kototoro.reader.novel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NovelTextRulesTest {

	@Test
	fun recognisesChapterTitleLines() {
		assertTrue(isNovelChapterTitleLine("第十二章 归来"))
		assertTrue(isNovelChapterTitleLine("  《序》  "))
		assertTrue(isNovelChapterTitleLine("【番外】"))
		assertFalse(isNovelChapterTitleLine("他说第一章写得很好"))
		assertFalse(isNovelChapterTitleLine("第一章" + "很".repeat(40)))
		assertFalse(isNovelChapterTitleLine("   "))
	}

	@Test
	fun blockAndInlineImagesBecomePlaceholders() {
		val parsed = parseNovelImages(
			"📷 [图片: a.jpg]\n<p>文字<img src=\"b.png\">文字</p>\n<p><IMG data-src='c.png'></p>",
		)

		assertEquals(listOf("a.jpg", "c.png"), parsed.blockImagePaths)
		assertEquals(listOf("b.png"), parsed.inlineImagePaths)
		assertEquals("[IMAGE_PLACEHOLDER_0]\n文字[INLINE_IMAGE_0]文字\n\n[IMAGE_PLACEHOLDER_1]\n", parsed.text)
	}

	@Test
	fun shortImageAltTextStaysInline() {
		assertEquals("A★B", parseNovelImages("A<img alt=\"★\">B").text)
	}

	@Test
	fun splitsParagraphsAtBlankLinesAndSkipsImagesForTranslation() {
		val paragraphs = NovelParagraphSplitter.split("第一段内容\n续行  \n\n\n📷 [图片: x.jpg]\n\n短")

		assertEquals(listOf("第一段内容\n续行", "📷 [图片: x.jpg]", "短"), paragraphs.map { it.originalText })
		assertEquals(
			listOf(NovelParagraphType.TEXT, NovelParagraphType.IMAGE, NovelParagraphType.IMAGE),
			paragraphs.map { it.type },
		)
		assertEquals(listOf("第一段内容\n续行"), NovelParagraphSplitter.buildTranslationInput(paragraphs))
	}

	@Test
	fun lineRangesCoverEveryLineOnce() {
		val tops = listOf(0f, 20f, 40f, 60f, 80f)
		val bottoms = listOf(20f, 40f, 60f, 80f, 100f)

		val ranges = splitNovelPageLineRanges(
			lineCount = tops.size,
			pageHeightPx = 45,
			lineTop = tops::get,
			lineBottom = bottoms::get,
		)

		assertEquals(listOf(0..1, 2..3, 4..4), ranges)
		assertEquals(tops.indices.toList(), ranges.flatMap { it.toList() })
	}

	@Test
	fun lineTallerThanAPageStillAdvances() {
		val ranges = splitNovelPageLineRanges(
			lineCount = 2,
			pageHeightPx = 40,
			lineTop = listOf(0f, 80f)::get,
			lineBottom = listOf(80f, 100f)::get,
		)

		assertEquals(listOf(0..0, 1..1), ranges)
	}

	@Test
	fun invalidDimensionsProduceNoPages() {
		assertEquals(emptyList(), splitNovelPageLineRanges(3, 0, { 0f }, { 0f }))
	}
}
