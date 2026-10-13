package org.skepsun.kototoro.local.epub

import org.skepsun.kototoro.reader.novel.NovelStyledText
import org.skepsun.kototoro.reader.novel.novelHtmlToStyledText
import java.io.File
import java.util.zip.ZipFile

/**
 * The book's styling of one EPUB content document: its XHTML with the style sheets its head links (and its own
 * `<style>` elements), read by the shared converter. The text equals what [epubHtmlToText] produces for the document,
 * so the reader matches the styles to its paragraphs by their text.
 */
internal object EpubChapterStyles {

    /** [href] is the document's path inside the archive, as the chapter loader reports it. */
    fun load(file: File, href: String): NovelStyledText? = runCatching {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(href) ?: return@use null
            val xhtml = zip.getInputStream(entry).use { it.readBytes() }.decodeToString()
            val head = HEAD.find(xhtml)?.groupValues?.get(1).orEmpty()
            val css = LINK.findAll(head)
                .filter { STYLESHEET.containsMatchIn(it.value) }
                .mapNotNull { link -> HREF.find(link.value)?.groupValues?.get(1) }
                .mapNotNull { linked -> zip.getEntry(resolve(href, linked))?.let { zip.getInputStream(it).use { s -> s.readBytes() } } }
                .joinToString("\n") { it.decodeToString() }
            val sheet = if (css.isBlank()) "" else "<style>" + css.replace("</style", "") + "</style>"
            novelHtmlToStyledText(sheet + xhtml)
        }
    }.getOrNull()

    /** [link] relative to the document [base], both archive paths. */
    private fun resolve(base: String, link: String): String {
        val target = java.net.URLDecoder.decode(link.substringBefore('#'), "UTF-8")
        val parts = if (target.startsWith('/')) mutableListOf() else base.substringBeforeLast('/', "")
            .split('/').filter(String::isNotEmpty).toMutableList()
        target.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += segment
            }
        }
        return parts.joinToString("/")
    }

    private val HEAD = Regex("<head\\b[^>]*>([\\s\\S]*?)</head\\s*>", RegexOption.IGNORE_CASE)
    private val LINK = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val STYLESHEET = Regex("\\brel\\s*=\\s*[\"'][^\"']*stylesheet", RegexOption.IGNORE_CASE)
    private val HREF = Regex("\\bhref\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
}
