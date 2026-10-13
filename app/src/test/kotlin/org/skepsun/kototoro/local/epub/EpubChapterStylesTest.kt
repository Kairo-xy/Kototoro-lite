package org.skepsun.kototoro.local.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.reader.novel.NovelTextAlign
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubChapterStylesTest {

	@TempDir
	lateinit var dir: File

	private fun epub(entries: Map<String, String>): File {
		val file = File(dir, "book.epub")
		ZipOutputStream(file.outputStream()).use { out ->
			entries.forEach { (name, text) -> out.putNextEntry(ZipEntry(name)); out.write(text.toByteArray()); out.closeEntry() }
		}
		return file
	}

	@Test
	fun `linked style sheets style the document and its text matches the plain conversion`() {
		val chapter = """<html><head><link rel="stylesheet" href="../Styles/book.css"/></head>
			<body><h1 class="title">第一章</h1><p class="noindent">正文</p></body></html>"""
		val file = epub(mapOf(
			"OEBPS/Text/c1.xhtml" to chapter,
			"OEBPS/Styles/book.css" to "h1.title { text-align: center } .noindent { text-indent: 0 }",
		))

		val styles = requireNotNull(EpubChapterStyles.load(file, "OEBPS/Text/c1.xhtml"))

		assertEquals(epubHtmlToText(chapter, keepImages = true), styles.text)
		assertEquals(NovelTextAlign.CENTER, styles.paragraphAt(0)!!.align)
		assertEquals(0f, styles.paragraphAt(styles.text.indexOf("正文"))!!.indentEm)
	}

	@Test
	fun `a missing document has no styles`() {
		assertNull(EpubChapterStyles.load(epub(mapOf("a.xhtml" to "<p>x</p>")), "missing.xhtml"))
	}
}
