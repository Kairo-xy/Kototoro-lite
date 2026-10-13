package org.skepsun.kototoro.reader.novel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NovelChapterProgressTest {

	@Test
	fun progressIsStoredInTenThousandthsAndNeverLooksLikeTheStartWhenItIsNot() {
		assertEquals(0, novelProgressScroll(0f))
		assertEquals(6_250, novelProgressScroll(0.625f))
		assertEquals(1, novelProgressScroll(0.00001f))
		assertEquals(NOVEL_PROGRESS_SCALE, novelProgressScroll(3f))
		assertEquals(0, novelProgressScroll(Float.NaN))
	}

	@Test
	fun storedValueReadsBackAsProgressAndZeroCarriesNone() {
		assertEquals(0.625f, novelScrollProgress(6_250f))
		assertEquals(1f, novelScrollProgress(20_000f))
		assertNull(novelScrollProgress(0f))
		assertNull(novelScrollProgress(Float.NaN))
	}

	@Test
	fun pageForProgressInvertsPageOverLastPage() {
		val pageCount = 10
		for (page in 0 until pageCount) {
			assertEquals(page, novelPageForProgress(page.toFloat() / (pageCount - 1), pageCount))
		}
		assertEquals(0, novelPageForProgress(0.7f, 1))
	}

	@Test
	fun blockAtOffsetFindsTheLineAndSkipsUnpositionedBlocks() {
		val text = novelHtmlToText("<p>alpha</p><p>beta</p><img src=\"x.png\"><p>gamma</p>")
		val blocks = buildNovelLineDocument(text)

		assertEquals(0, novelBlockAtOffset(blocks, 0))
		assertEquals(1, novelBlockAtOffset(blocks, text.indexOf("beta") + 2))
		assertEquals(2, novelBlockAtOffset(blocks, text.indexOf("📷")))
		assertEquals(3, novelBlockAtOffset(blocks, text.length))
		assertEquals(1, novelBlockAtOffset(listOf(NovelDocumentBlock.Image("i", "p")) + blocks, 0))
		assertNull(novelBlockAtOffset(listOf(NovelDocumentBlock.Image("i", "p")), 3))
	}

	@Test
	fun lineOffsetsRoundTripWhileLinesAreLongerThanOneUnit() {
		for (lines in listOf(5, 200, 3_000)) {
			val text = novelHtmlToText((1..lines).joinToString("") { "<p>第${it}行内容</p>" })
			val blocks = buildNovelLineDocument(text)
			for (index in blocks.indices) {
				val stored = novelOffsetScroll(blocks[index].sourceRange!!.first, text.length)
				val restored = novelScrollOffset(stored.toFloat(), text.length) ?: 0
				assertEquals(index, novelBlockAtOffset(blocks, restored), "line $index of $lines")
			}
		}
	}

	@Test
	fun offsetsBeyondTheStartAreNeverStoredAsZero() {
		assertEquals(0, novelOffsetScroll(0, 100_000))
		assertEquals(1, novelOffsetScroll(1, 100_000_000))
		assertEquals(NOVEL_PROGRESS_SCALE, novelOffsetScroll(500, 100))
		assertNull(novelScrollOffset(0f, 100))
	}
}
