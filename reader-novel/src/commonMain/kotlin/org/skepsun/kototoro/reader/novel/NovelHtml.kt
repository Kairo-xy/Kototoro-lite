package org.skepsun.kototoro.reader.novel

/**
 * Plain text of a chapter's HTML or XHTML, as the novel readers show it.
 *
 * Block elements (`p`, `div`, headings, list items, ...) are separated by a blank line and `<br>` ends a line, so
 * [NovelParagraphSplitter] sees each block as a paragraph. Source whitespace collapses as in HTML except inside
 * `<pre>`; `<head>`, `<title>`, `<script>`, `<style>` and ruby readings (`<rt>`, `<rp>`) are not text. Entities are
 * decoded once. Pictures (`<img>`, SVG `<image>`) become `📷 [图片: src]` paragraphs when [keepImages] is set.
 *
 * Markup is tokenized rather than matched with patterns: a `>` inside a quoted attribute, comments, CDATA and
 * upper-case tags are handled, and a stray `<` stays text.
 */
fun novelHtmlToText(html: String, keepImages: Boolean = true): String = novelHtmlToStyledText(html, keepImages).text

/**
 * [novelHtmlToText] together with the styling the chapter asks for: paragraph styles (heading level, alignment,
 * first-line indent, quotes, preformatted text), inline styles (bold, italic, underline, strike-through, super- and
 * subscript, relative size) and ruby readings over their base text. The text is exactly [novelHtmlToText]'s, whatever
 * the styles, so positions stored against it stay valid. Styles come from the elements themselves, `<style>` sheets
 * met before the content (the subset in [NovelStyleSheet]) and `style` attributes.
 */
fun novelHtmlToStyledText(html: String, keepImages: Boolean = true): NovelStyledText {
    val sheet = NovelStyleSheet()
    val stack = mutableListOf<NovelOpenElement>()
    fun current(): NovelElementStyle = stack.lastOrNull()?.style ?: NovelElementStyle.ROOT
    val out = NovelTextBuilder(
        inlineStyle = { current().inline },
        paragraphStyle = { start ->
            val style = current()
            NovelParagraphStyle(start, start, style.heading, style.align, style.indentEm, style.quote, style.preformatted)
        },
    )
    var preDepth = 0
    var rubyBase = -1
    var i = 0
    while (i < html.length) {
        val lt = html.indexOf('<', i)
        val textEnd = if (lt < 0) html.length else lt
        if (textEnd > i) out.text(decodeNovelHtmlEntities(html.substring(i, textEnd)), preDepth > 0)
        if (lt < 0) break
        when {
            html.startsWith("<!--", lt) -> i = html.indexOf("-->", lt + 4).let { if (it < 0) html.length else it + 3 }
            html.startsWith("<![CDATA[", lt) -> {
                val end = html.indexOf("]]>", lt + 9).let { if (it < 0) html.length else it }
                out.text(html.substring(lt + 9, end), preDepth > 0)
                i = minOf(html.length, end + 3)
            }
            html.startsWith("<!", lt) || html.startsWith("<?", lt) ->
                i = html.indexOf('>', lt + 2).let { if (it < 0) html.length else it + 1 }
            else -> {
                val tag = readTag(html, lt)
                if (tag == null) {
                    out.text("<", preDepth > 0)
                    i = lt + 1
                    continue
                }
                i = tag.end
                val name = tag.name
                when {
                    tag.closing -> {
                        val open = stack.indexOfLast { it.name == name }
                        if (open >= 0) repeat(stack.size - open) { stack.removeAt(stack.lastIndex) }
                        when {
                            name == "pre" -> { preDepth = maxOf(0, preDepth - 1); out.paragraph() }
                            name == "ruby" -> rubyBase = -1
                            name in BLOCK_ELEMENTS -> out.paragraph()
                        }
                    }
                    name in SKIPPED_ELEMENTS -> if (!tag.selfClosing) {
                        val end = elementEnd(html, i, name)
                        val content = html.substring(i, end.first)
                        when (name) {
                            "style" -> sheet.add(content)
                            "head" -> HEAD_STYLE.findAll(content).forEach { sheet.add(it.groupValues[1]) }
                            "rt" -> if (rubyBase >= 0) {
                                out.ruby(rubyBase, novelHtmlToText(content, keepImages = false))
                                rubyBase = out.length
                            }
                        }
                        i = end.second
                    }
                    name == "br" -> out.lineBreak()
                    name == "img" || name == "image" -> if (keepImages) {
                        val src = tag.attribute("src") ?: tag.attribute("data-src")
                            ?: tag.attribute("xlink:href") ?: tag.attribute("href")
                        if (!src.isNullOrBlank()) out.image(src.trim())
                    }
                    else -> {
                        val block = name in BLOCK_ELEMENTS || name == "pre"
                        // A new block closes an open paragraph, as HTML parsers do, and with it any inline element
                        // left open inside (browsers would reopen those; a missing </b> must not embolden a chapter).
                        if (block) {
                            val paragraph = stack.indexOfLast { it.name == "p" }
                            if (paragraph >= 0 && stack.drop(paragraph + 1).none { it.name in BLOCK_ELEMENTS }) {
                                repeat(stack.size - paragraph) { stack.removeAt(stack.lastIndex) }
                            }
                        }
                        if (block) out.paragraph()
                        if (!tag.selfClosing && name !in VOID_ELEMENTS) {
                            stack += NovelOpenElement(name, sheet.style(current(), name, tag))
                        }
                        if (name == "pre" && !tag.selfClosing) preDepth++
                        if (name == "ruby") rubyBase = out.length
                    }
                }
            }
        }
    }
    return out.build()
}

/** Decodes named and numeric character references once; unknown or malformed ones stay as written. */
fun decodeNovelHtmlEntities(text: String): String {
    var amp = text.indexOf('&')
    if (amp < 0) return text
    val result = StringBuilder(text.length)
    var from = 0
    while (amp >= 0) {
        result.append(text, from, amp)
        val semicolon = text.indexOf(';', amp + 1)
        val decoded = if (semicolon > amp + 1 && semicolon - amp <= MAX_ENTITY_LENGTH) {
            entityValue(text.substring(amp + 1, semicolon))
        } else {
            null
        }
        if (decoded != null) {
            result.append(decoded)
            from = semicolon + 1
        } else {
            result.append('&')
            from = amp + 1
        }
        amp = text.indexOf('&', from)
    }
    result.append(text, from, text.length)
    return result.toString()
}

private fun entityValue(name: String): String? {
    if (name.startsWith('#')) {
        val hex = name.startsWith("#x") || name.startsWith("#X")
        val digits = name.substring(if (hex) 2 else 1)
        if (digits.isEmpty() || digits.length > 8) return null
        val code = digits.toIntOrNull(if (hex) 16 else 10) ?: return null
        return codePointText(code)
    }
    return NAMED_ENTITIES[name]
}

private fun codePointText(code: Int): String = when {
    code <= 0 || code > 0x10FFFF || code in 0xD800..0xDFFF -> "�"
    code < 0x10000 -> code.toChar().toString()
    else -> {
        val offset = code - 0x10000
        charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
    }
}

private class NovelTag(val name: String, val closing: Boolean, val selfClosing: Boolean, val end: Int,
    private val attributes: Map<String, String>) : NovelTagAttributes {
    override fun attribute(name: String): String? = attributes[name]
}

/** Reads the tag starting at [start] (`<name ...>` or `</name>`), or null when the `<` does not open a tag. */
private fun readTag(html: String, start: Int): NovelTag? {
    var i = start + 1
    val closing = html.getOrNull(i) == '/'
    if (closing) i++
    val nameStart = i
    if (html.getOrNull(i)?.isLetter() != true) return null
    while (i < html.length && !html[i].isWhitespace() && html[i] != '>' && html[i] != '/') i++
    val name = html.substring(nameStart, i).lowercase()
    val attributes = HashMap<String, String>()
    var selfClosing = false
    while (i < html.length) {
        val c = html[i]
        when {
            c == '>' -> return NovelTag(name, closing, selfClosing, i + 1, attributes)
            c.isWhitespace() -> i++
            c == '/' -> { selfClosing = true; i++ }
            else -> {
                selfClosing = false
                val keyStart = i
                while (i < html.length && !html[i].isWhitespace() && html[i] != '=' && html[i] != '>' &&
                    !(html[i] == '/' && html.getOrNull(i + 1) == '>')) i++
                val key = html.substring(keyStart, i).lowercase()
                while (i < html.length && html[i].isWhitespace()) i++
                var value = ""
                if (html.getOrNull(i) == '=') {
                    i++
                    while (i < html.length && html[i].isWhitespace()) i++
                    val quote = html.getOrNull(i)
                    if (quote == '"' || quote == '\'') {
                        val close = html.indexOf(quote, i + 1).let { if (it < 0) html.length else it }
                        value = html.substring(i + 1, close)
                        i = minOf(html.length, close + 1)
                    } else {
                        val valueStart = i
                        while (i < html.length && !html[i].isWhitespace() && html[i] != '>') i++
                        value = html.substring(valueStart, i)
                    }
                }
                attributes.getOrPut(key) { decodeNovelHtmlEntities(value) }
            }
        }
    }
    return NovelTag(name, closing, selfClosing, html.length, attributes)
}

/** Where the content of [name] ends and where its end tag ends (ignoring case); the text end when never closed. */
private fun elementEnd(html: String, from: Int, name: String): Pair<Int, Int> {
    var i = from
    while (true) {
        val lt = html.indexOf("</", i)
        if (lt < 0) return html.length to html.length
        if (html.regionMatches(lt + 2, name, 0, name.length, ignoreCase = true)) {
            val after = html.getOrNull(lt + 2 + name.length)
            if (after == null || after == '>' || after.isWhitespace()) {
                return lt to html.indexOf('>', lt).let { if (it < 0) html.length else it + 1 }
            }
        }
        i = lt + 2
    }
}

private class NovelOpenElement(val name: String, val style: NovelElementStyle)

/**
 * Collects the text and its styles. It never rewrites what it has written (no more than one blank line, no leading
 * newlines, no spaces at line ends outside `<pre>`), so style offsets are offsets of the final text.
 */
private class NovelTextBuilder(
    private val inlineStyle: () -> NovelInlineStyle,
    private val paragraphStyle: (start: Int) -> NovelParagraphStyle,
) {
    private val out = StringBuilder()
    private var pendingSpace = false
    private val paragraphs = mutableListOf<NovelParagraphStyle>()
    private val spans = mutableListOf<NovelStyleSpan>()
    private val rubies = mutableListOf<NovelRubySpan>()
    private var paragraph: NovelParagraphStyle? = null

    val length: Int get() = out.length

    fun text(value: String, preformatted: Boolean) {
        if (preformatted) {
            pendingSpace = false
            for (c in value.replace("\r\n", "\n").replace('\r', '\n')) if (c == '\n') newline(2) else append(c)
            return
        }
        for (c in value) {
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C' || c == '\u00A0') {
                pendingSpace = true
            } else {
                if (pendingSpace && out.isNotEmpty() && out.last() != '\n') append(' ')
                pendingSpace = false
                append(c)
            }
        }
    }

    fun lineBreak() {
        pendingSpace = false
        newline(2)
    }

    fun paragraph() {
        pendingSpace = false
        closeParagraph()
        while (trailingNewlines() < 2 && out.isNotEmpty()) out.append('\n')
    }

    fun image(src: String) {
        paragraph()
        out.append("📷 [图片: ").append(src).append(']')
        paragraph()
    }

    /** [text] reads the base text written since [start]. */
    fun ruby(start: Int, text: String) {
        val annotation = text.trim()
        if (annotation.isNotEmpty() && out.length > start) rubies += NovelRubySpan(start, out.length, annotation)
    }

    fun build(): NovelStyledText {
        closeParagraph()
        val text = out.toString().trimEnd('\n')
        return NovelStyledText(
            text,
            paragraphs.filter { it.start < text.length },
            spans.mapNotNull { span -> span.takeIf { it.start < text.length }?.copy(end = minOf(span.end, text.length)) },
            rubies.filter { it.end <= text.length },
        )
    }

    private fun append(c: Char) {
        if (c != '\n' && paragraph == null) paragraph = paragraphStyle(out.length)
        val style = inlineStyle()
        if (style != NovelInlineStyle.PLAIN && c != '\n') {
            val last = spans.lastOrNull()
            if (last != null && last.end == out.length && last.style == style) {
                spans[spans.lastIndex] = last.copy(end = out.length + 1)
            } else {
                spans += NovelStyleSpan(out.length, out.length + 1, style)
            }
        }
        out.append(c)
    }

    /** A line end, keeping at most [limit] consecutive newlines and none at the start. */
    private fun newline(limit: Int) {
        if (out.isNotEmpty() && trailingNewlines() < limit) out.append('\n')
    }

    private fun closeParagraph() {
        val open = paragraph ?: return
        paragraphs += open.copy(end = out.length - trailingNewlines())
        paragraph = null
    }

    private fun trailingNewlines(): Int {
        var count = 0
        while (count < out.length && out[out.length - 1 - count] == '\n') count++
        return count
    }
}

private const val MAX_ENTITY_LENGTH = 12

private val SKIPPED_ELEMENTS = setOf("head", "title", "script", "style", "noscript", "template", "rt", "rp", "rtc")

private val HEAD_STYLE = Regex("<style\\b[^>]*>([\\s\\S]*?)</style\\s*>", RegexOption.IGNORE_CASE)

private val VOID_ELEMENTS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "image", "input", "link", "meta", "param", "source", "track", "wbr",
)

private val BLOCK_ELEMENTS = setOf(
    "address", "article", "aside", "blockquote", "body", "center", "dd", "details", "div", "dl", "dt", "figcaption",
    "figure", "footer", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "html", "li", "main", "nav", "ol", "p",
    "section", "summary", "table", "tr", "ul",
)

private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "ensp" to " ", "emsp" to " ", "thinsp" to " ", "zwnj" to "‌", "zwj" to "‍",
    "shy" to "­", "hellip" to "…", "mdash" to "—", "ndash" to "–", "lsquo" to "‘", "rsquo" to "’",
    "ldquo" to "“", "rdquo" to "”", "sbquo" to "‚", "bdquo" to "„", "laquo" to "«", "raquo" to "»",
    "lsaquo" to "‹", "rsaquo" to "›", "middot" to "·", "bull" to "•", "deg" to "°", "times" to "×",
    "divide" to "÷", "copy" to "©", "reg" to "®", "trade" to "™", "sect" to "§", "para" to "¶",
    "dagger" to "†", "Dagger" to "‡", "prime" to "′", "Prime" to "″", "larr" to "←", "rarr" to "→",
    "uarr" to "↑", "darr" to "↓", "hearts" to "♥", "star" to "☆",
)
