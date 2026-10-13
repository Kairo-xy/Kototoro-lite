package org.skepsun.kototoro.reader.novel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NovelHtmlTest {

	@Test
	fun headIsNotChapterTextAndBlocksBecomeParagraphs() {
		val chapter = """<?xml version="1.0" encoding="utf-8"?>
			<!DOCTYPE html>
			<html xmlns="http://www.w3.org/1999/xhtml"><head><title>My Book - Part 1</title>
			<meta charset="utf-8"/><link rel="stylesheet" href="s.css"/></head>
			<body><h2>Part 1</h2><p>Paragraph one.</p>
			<p>Paragraph two.</p></body></html>"""

		assertEquals("Part 1\n\nParagraph one.\n\nParagraph two.", novelHtmlToText(chapter))
	}

	@Test
	fun sourceLineWrapsCollapseButLineBreaksAndPreKeepTheirLines() {
		assertEquals("a long wrapped line\nnext", novelHtmlToText("<p>a long\n   wrapped line<br/>next</p>"))
		assertEquals("x\n  y", novelHtmlToText("<pre>x\n  y</pre>"))
	}

	@Test
	fun rubyKeepsTheBaseTextOnly() {
		assertEquals(
			"漢字を読む",
			novelHtmlToText("<p><ruby>漢<rp>(</rp><rt>かん</rt><rp>)</rp>字<rt>じ</rt></ruby>を読む</p>"),
		)
	}

	@Test
	fun entitiesAreDecodedOnce() {
		assertEquals(
			"a & b <code> “q” … AB中😀 &amp; &unknown; �",
			novelHtmlToText("a &amp; b &lt;code&gt; &ldquo;q&rdquo; &hellip; &#65;&#x42;&#x4E2D;&#128512; " +
				"&amp;amp; &unknown; &#0;"),
		)
	}

	@Test
	fun fullWidthIndentSurvivesWhileNbspCollapses() {
		assertEquals("　　正文 x", novelHtmlToText("<p>　　正文&nbsp; x</p>"))
	}

	@Test
	fun scriptsStylesAndCommentsAreNotText() {
		assertEquals(
			"A\n\nB",
			novelHtmlToText("<p>A</p><SCRIPT>if (a < b) x = '<p>';</SCRIPT><style>p{}</style><!-- <p>c</p> --><p>B</p>"),
		)
	}

	@Test
	fun quotedGreaterThanStaysInsideTheAttribute() {
		assertEquals("📷 [图片: a>b.jpg]\n\nAfter", novelHtmlToText("<img alt=\"x > y\" src='a>b.jpg'/>After"))
	}

	@Test
	fun picturesBecomeParagraphPlaceholdersOnlyWhenKept() {
		val html = """<p>Before<img src="../img/a.jpg"/>after</p><svg><image xlink:href="cover.png"/></svg>"""

		assertEquals(
			"Before\n\n📷 [图片: ../img/a.jpg]\n\nafter\n\n📷 [图片: cover.png]",
			novelHtmlToText(html),
		)
		assertEquals("Beforeafter", novelHtmlToText(html, keepImages = false))
	}

	@Test
	fun strayLessThanStaysText() {
		assertEquals("1 < 2 and 3<4", novelHtmlToText("1 < 2 and 3<4"))
	}

	@Test
	fun lineDocumentKeepsOneBlockPerLine() {
		val text = novelHtmlToText("<h1>Title</h1><p>one<br>two</p><img src=\"p.png\">")
		val blocks = buildNovelLineDocument(text)

		assertEquals(listOf("Title", "one", "two"), blocks.take(3).map { assertIs<NovelDocumentBlock.Text>(it).original })
		assertEquals("p.png", assertIs<NovelDocumentBlock.Image>(blocks[3]).path)
		val second = assertIs<NovelDocumentBlock.Text>(blocks[1])
		assertEquals("one", text.substring(second.sourceRange!!))
	}
}
