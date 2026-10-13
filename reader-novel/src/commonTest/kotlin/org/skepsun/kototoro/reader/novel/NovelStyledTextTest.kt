package org.skepsun.kototoro.reader.novel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NovelStyledTextTest {

	private val chapter = """<?xml version="1.0" encoding="utf-8"?>
		<html><head><title>T</title><style>
		  /* book styles */
		  @media screen { p { color: red } }
		  h2.title { text-align: center; font-size: 1.5em }
		  .noindent { text-indent: 0 }
		  p.right, .r { text-align: right }
		  span.em { font-weight: bold; font-style: italic }
		  p:first-child { text-indent: 0 }
		</style></head>
		<body><h2 class="title">第一章 出发</h2>
		<p>普通段落<b>粗体</b>和<span class="em">强调</span>。</p>
		<p class="noindent">不缩进的段落</p>
		<p class="r">右对齐</p>
		<p style="text-align:center;font-size:80%">居中小字</p>
		<p><ruby>漢<rt>かん</rt>字<rt>じ</rt></ruby>を読む<sup>1</sup></p>
		</body></html>"""

	private val styled = novelHtmlToStyledText(chapter)

	@Test
	fun textIsExactlyThePlainConversion() {
		assertEquals(novelHtmlToText(chapter), styled.text)
		assertEquals(
			"第一章 出发\n\n普通段落粗体和强调。\n\n不缩进的段落\n\n右对齐\n\n居中小字\n\n漢字を読む1",
			styled.text,
		)
	}

	@Test
	fun paragraphsCarryHeadingAlignmentAndIndent() {
		val heading = styled.paragraphAt(0)!!
		assertEquals(2, heading.heading)
		assertEquals(NovelTextAlign.CENTER, heading.align)
		assertTrue(!heading.takesIndent)

		val body = styled.paragraphAt(styled.text.indexOf("普通"))!!
		assertEquals(0, body.heading)
		assertNull(body.indentEm)
		assertTrue(body.takesIndent)

		assertEquals(0f, styled.paragraphAt(styled.text.indexOf("不缩进"))!!.indentEm)
		assertEquals(NovelTextAlign.END, styled.paragraphAt(styled.text.indexOf("右对齐"))!!.align)
		assertEquals(NovelTextAlign.CENTER, styled.paragraphAt(styled.text.indexOf("居中"))!!.align)
		assertEquals("右对齐", styled.text.substring(styled.paragraphAt(styled.text.indexOf("右对齐"))!!.let { it.start until it.end }))
	}

	@Test
	fun inlineSpansCoverTheirCharacters() {
		fun styleOf(word: String): NovelInlineStyle? {
			val start = styled.text.indexOf(word)
			return styled.spans.firstOrNull { it.start <= start && start + word.length <= it.end }?.style
		}
		assertEquals(NovelInlineStyle(bold = true, fontScale = 1.5f), styleOf("第一章"))
		assertEquals(NovelInlineStyle(bold = true), styleOf("粗体"))
		assertEquals(NovelInlineStyle(bold = true, italic = true), styleOf("强调"))
		assertNull(styleOf("普通"))
		assertEquals(0.8f, styleOf("居中小字")!!.fontScale)
		assertEquals(1, styleOf("1")!!.verticalPosition)
	}

	@Test
	fun rubyReadingsAnnotateTheirBaseWithoutEnteringTheText() {
		val base = styled.text.indexOf("漢")
		assertEquals(
			listOf(NovelRubySpan(base, base + 1, "かん"), NovelRubySpan(base + 1, base + 2, "じ")),
			styled.rubies,
		)
	}

	@Test
	fun unclosedParagraphsAndUnknownMarkupDoNotLeakStyles() {
		val text = novelHtmlToStyledText("<p><b>粗<p>新段落<i>斜</p>正文")
		assertEquals("粗\n\n新段落斜\n\n正文", text.text)
		val plain = text.text.indexOf("正文")
		assertTrue(text.spans.none { it.start <= plain && plain < it.end }, "${text.spans}")
	}

	@Test
	fun spansAndRubiesClipToRanges() {
		val start = styled.text.indexOf("粗体")
		assertEquals(listOf(NovelStyleSpan(start, start + 1, NovelInlineStyle(bold = true))), styled.spansIn(start, start + 1))
		assertEquals(1, styled.rubiesIn(styled.text.indexOf("漢"), styled.text.indexOf("漢") + 1).size)
	}

	@Test
	fun paragraphBlocksFormatLikeTheReaderWithTheBooksStyles() {
		val (heading, afterHeading) = styled.formatParagraph("第一章 出发", 0, indentEnabled = true, spacingLines = 0)!!
		assertEquals("第一章 出发", heading.text)
		assertEquals(NovelTextAlign.CENTER, heading.lines.single().align)
		assertEquals(1.5f, heading.lines.single().fontScale)

		val (body, _) = styled.formatParagraph("普通段落粗体和强调。\n不缩进的段落", afterHeading, true, 1)!!
		assertEquals("　　普通段落粗体和强调。\n\n不缩进的段落", body.text)
		val bold = body.spans.first { it.style == NovelInlineStyle(bold = true) }
		assertEquals("粗体", body.text.substring(bold.start, bold.end))
		assertEquals(listOf(0 until 12, 14 until 20), body.lines.map { it.start until it.end })
	}

	@Test
	fun changedTextFallsBackToPlainFormatting() {
		assertNull(styled.formatParagraph("被替换规则改过的段落", 0, true, 0))
	}
}
