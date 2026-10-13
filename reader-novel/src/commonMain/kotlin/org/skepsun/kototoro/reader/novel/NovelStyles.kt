package org.skepsun.kototoro.reader.novel

/** Text alignment a book asks for. */
enum class NovelTextAlign { START, CENTER, END, JUSTIFY }

/** Character styling; [fontScale] is relative to the reader's font size, [verticalPosition] is 1 for superscript. */
data class NovelInlineStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val verticalPosition: Int = 0,
    val fontScale: Float = 1f,
) {
    companion object {
        val PLAIN = NovelInlineStyle()
    }
}

data class NovelStyleSpan(val start: Int, val end: Int, val style: NovelInlineStyle)

/** A ruby reading ([text]) over the base characters `start until end`. */
data class NovelRubySpan(val start: Int, val end: Int, val text: String)

/**
 * Styling of the paragraph (text between blank lines) covering `start until end`. [indentEm] is the book's first-line
 * indent in ems, or null when it sets none and the reader's own indent applies.
 */
data class NovelParagraphStyle(
    val start: Int,
    val end: Int,
    val heading: Int = 0,
    val align: NovelTextAlign = NovelTextAlign.START,
    val indentEm: Float? = null,
    val quote: Boolean = false,
    val preformatted: Boolean = false,
) {
    /** A plain body paragraph, which takes the reader's paragraph indent. */
    val takesIndent: Boolean
        get() = heading == 0 && !preformatted && (align == NovelTextAlign.START || align == NovelTextAlign.JUSTIFY) &&
            (indentEm == null || indentEm > 0f)
}

/** The converted text with the styles of its ranges; spans are sorted and do not overlap. */
class NovelStyledText(
    val text: String,
    val paragraphs: List<NovelParagraphStyle>,
    val spans: List<NovelStyleSpan>,
    val rubies: List<NovelRubySpan>,
) {
    /** The paragraph holding [offset], if any. */
    fun paragraphAt(offset: Int): NovelParagraphStyle? {
        var low = 0
        var high = paragraphs.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val paragraph = paragraphs[middle]
            when {
                offset < paragraph.start -> high = middle - 1
                offset >= maxOf(paragraph.end, paragraph.start + 1) -> low = middle + 1
                else -> return paragraph
            }
        }
        return null
    }

    /** Styled spans overlapping `start until end`, clipped to it. */
    fun spansIn(start: Int, end: Int): List<NovelStyleSpan> = spans.mapNotNull { span ->
        if (span.end <= start || span.start >= end) null
        else span.copy(start = maxOf(span.start, start), end = minOf(span.end, end))
    }

    /** Ruby readings whose base lies within `start until end`. */
    fun rubiesIn(start: Int, end: Int): List<NovelRubySpan> = rubies.filter { it.start >= start && it.end <= end }
}

/** The style an element passes on: inline styles and inherited paragraph properties. */
internal data class NovelElementStyle(
    val inline: NovelInlineStyle,
    val align: NovelTextAlign,
    val indentEm: Float?,
    val heading: Int,
    val quote: Boolean,
    val preformatted: Boolean,
) {
    companion object {
        val ROOT = NovelElementStyle(NovelInlineStyle.PLAIN, NovelTextAlign.START, null, 0, false, false)
    }
}

/**
 * The CSS a novel reader honours: rules of type, class and id selectors (for descendant and child selectors only the
 * last compound selector is matched; rules with pseudo-classes or attribute selectors are ignored), applied by
 * specificity and source order, then the `style` attribute. Properties: `font-weight`, `font-style`, `font-size`,
 * `text-align`, `text-indent`, `text-decoration`, `vertical-align`. At-rules (`@media`, `@font-face`) are skipped.
 */
internal class NovelStyleSheet {
    private class Rule(
        val tag: String?,
        val classes: Set<String>,
        val id: String?,
        val specificity: Int,
        val order: Int,
        val declarations: List<Pair<String, String>>,
    )

    private val rules = mutableListOf<Rule>()

    fun add(css: String) {
        val source = COMMENT.replace(css, " ")
        var i = 0
        while (i < source.length) {
            val open = source.indexOf('{', i)
            if (open < 0) return
            val prelude = source.substring(i, open).trim()
            if (prelude.startsWith('@')) {
                // Statements such as @import end at ';'; blocks such as @media are skipped whole.
                val semicolon = source.indexOf(';', i)
                i = if (semicolon in i until open) semicolon + 1 else blockEnd(source, open)
                continue
            }
            val close = source.indexOf('}', open).let { if (it < 0) source.length else it }
            val declarations = parseDeclarations(source.substring(open + 1, close))
            prelude.split(',').forEach { selector -> parseSelector(selector, declarations)?.let(rules::add) }
            i = close + 1
        }
    }

    fun style(parent: NovelElementStyle, tag: String, element: NovelTagAttributes): NovelElementStyle {
        val classes = element.attribute("class")?.split(WHITESPACE)?.filter(String::isNotEmpty)?.toSet().orEmpty()
        val id = element.attribute("id")
        val builder = StyleBuilder(parent, tag)
        rules.filter { rule ->
            (rule.tag == null || rule.tag == tag) && classes.containsAll(rule.classes) && (rule.id == null || rule.id == id)
        }.sortedWith(compareBy({ it.specificity }, { it.order })).forEach { rule ->
            rule.declarations.forEach { (property, value) -> builder.apply(property, value) }
        }
        element.attribute("style")?.let { inline -> parseDeclarations(inline).forEach { (p, v) -> builder.apply(p, v) } }
        return builder.build()
    }

    private fun parseSelector(selector: String, declarations: List<Pair<String, String>>): Rule? {
        val compound = selector.trim().split(COMBINATOR).lastOrNull()?.takeIf(String::isNotEmpty) ?: return null
        if (':' in compound || '[' in compound) return null
        val match = COMPOUND.matchEntire(compound) ?: return null
        val tag = match.groupValues[1].lowercase().takeIf { it.isNotEmpty() && it != "*" }
        val parts = SELECTOR_PART.findAll(match.groupValues[2]).map { it.value }.toList()
        val classes = parts.filter { it.startsWith('.') }.map { it.substring(1) }.toSet()
        val id = parts.firstOrNull { it.startsWith('#') }?.substring(1)
        val specificity = (if (id != null) 100 else 0) + classes.size * 10 + (if (tag != null) 1 else 0)
        return Rule(tag, classes, id, specificity, rules.size, declarations)
    }

    private class StyleBuilder(private val parent: NovelElementStyle, tag: String) {
        private var bold = parent.inline.bold
        private var italic = parent.inline.italic
        private var underline = parent.inline.underline
        private var strike = parent.inline.strike
        private var vertical = 0
        private var scale = parent.inline.fontScale
        private var align = parent.align
        private var indent = parent.indentEm
        private var heading = parent.heading
        private var quote = parent.quote
        private var preformatted = parent.preformatted

        init {
            HEADING_SCALE[tag]?.let { headingScale ->
                heading = tag[1].digitToInt()
                bold = true
                scale = parent.inline.fontScale * headingScale
            }
            when (tag) {
                "b", "strong", "th" -> bold = true
                "i", "em", "cite", "var", "dfn" -> italic = true
                "u", "ins" -> underline = true
                "s", "strike", "del" -> strike = true
                "sup" -> { vertical = 1; scale = parent.inline.fontScale * 0.75f }
                "sub" -> { vertical = -1; scale = parent.inline.fontScale * 0.75f }
                "small" -> scale = parent.inline.fontScale * 0.83f
                "big" -> scale = parent.inline.fontScale * 1.2f
                "center" -> align = NovelTextAlign.CENTER
                "blockquote" -> quote = true
                "pre" -> preformatted = true
            }
            if (tag == "th") align = NovelTextAlign.CENTER
        }

        fun apply(property: String, value: String) {
            when (property) {
                "font-weight" -> when {
                    value == "bold" || value == "bolder" -> bold = true
                    value == "normal" || value == "lighter" -> bold = false
                    else -> value.toIntOrNull()?.let { bold = it >= 600 }
                }
                "font-style" -> italic = value == "italic" || value == "oblique"
                "font-size" -> fontSize(value)?.let { scale = it.coerceIn(0.5f, 3f) }
                "text-align" -> when (value) {
                    "center" -> align = NovelTextAlign.CENTER
                    "right", "end" -> align = NovelTextAlign.END
                    "left", "start" -> align = NovelTextAlign.START
                    "justify" -> align = NovelTextAlign.JUSTIFY
                }
                "text-indent" -> length(value)?.let { indent = it }
                "text-decoration", "text-decoration-line" -> {
                    if ("none" in value) { underline = false; strike = false }
                    if ("underline" in value) underline = true
                    if ("line-through" in value) strike = true
                }
                "vertical-align" -> when (value) {
                    "super" -> vertical = 1
                    "sub" -> vertical = -1
                    "baseline" -> vertical = 0
                }
            }
        }

        fun build() = NovelElementStyle(
            NovelInlineStyle(bold, italic, underline, strike, vertical, scale),
            align, indent, heading, quote, preformatted,
        )

        private fun fontSize(value: String): Float? {
            val base = parent.inline.fontScale
            FONT_SIZE_KEYWORDS[value]?.let { return it }
            return when {
                value == "smaller" -> base * 0.83f
                value == "larger" -> base * 1.2f
                value.endsWith("rem") -> value.dropLast(3).toFloatOrNull()
                value.endsWith("em") -> value.dropLast(2).toFloatOrNull()?.times(base)
                value.endsWith('%') -> value.dropLast(1).toFloatOrNull()?.div(100f)?.times(base)
                value.endsWith("px") -> value.dropLast(2).toFloatOrNull()?.div(16f)
                value.endsWith("pt") -> value.dropLast(2).toFloatOrNull()?.div(12f)
                else -> null
            }
        }

        /** A length in ems ([text-indent]); percentages depend on the page width and are ignored. */
        private fun length(value: String): Float? = when {
            value == "0" -> 0f
            value.endsWith("rem") -> value.dropLast(3).toFloatOrNull()
            value.endsWith("em") -> value.dropLast(2).toFloatOrNull()
            value.endsWith("px") -> value.dropLast(2).toFloatOrNull()?.div(16f)
            value.endsWith("pt") -> value.dropLast(2).toFloatOrNull()?.div(12f)
            else -> null
        }
    }

    private companion object {
        val COMMENT = Regex("/\\*[\\s\\S]*?\\*/")
        val WHITESPACE = Regex("\\s+")
        val COMBINATOR = Regex("\\s*[>+~]\\s*|\\s+")
        val COMPOUND = Regex("([A-Za-z][A-Za-z0-9-]*|\\*)?((?:[.#][A-Za-z0-9_-]+)*)")
        val SELECTOR_PART = Regex("[.#][A-Za-z0-9_-]+")
        val HEADING_SCALE = mapOf("h1" to 2f, "h2" to 1.5f, "h3" to 1.17f, "h4" to 1f, "h5" to 0.83f, "h6" to 0.67f)
        val FONT_SIZE_KEYWORDS = mapOf(
            "xx-small" to 0.6f, "x-small" to 0.75f, "small" to 0.89f, "medium" to 1f,
            "large" to 1.2f, "x-large" to 1.5f, "xx-large" to 2f,
        )

        fun blockEnd(source: String, open: Int): Int {
            var depth = 0
            for (index in open until source.length) {
                when (source[index]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) return index + 1
                }
            }
            return source.length
        }

        fun parseDeclarations(block: String): List<Pair<String, String>> = block.split(';').mapNotNull { declaration ->
            val colon = declaration.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            val property = declaration.substring(0, colon).trim().lowercase()
            val value = declaration.substring(colon + 1).replace("!important", "").trim().lowercase()
            if (property.isEmpty() || value.isEmpty()) null else property to value
        }
    }
}

/** Attribute access of a parsed tag, for selector matching. */
internal interface NovelTagAttributes {
    fun attribute(name: String): String?
}

/** A paragraph block formatted like [formatNovelParagraphText], with the book's styles of its lines. */
class NovelStyledParagraph(
    val text: String,
    val spans: List<NovelStyleSpan>,
    val lines: List<NovelStyledLine>,
)

/** Layout of one formatted line (`start until end`, indent included): alignment, heading and largest font scale. */
data class NovelStyledLine(
    val start: Int,
    val end: Int,
    val align: NovelTextAlign,
    val heading: Int,
    val fontScale: Float,
)

/**
 * Formats a reader's paragraph block whose lines appear in this text at or after [from], in order, as
 * [formatNovelParagraphText] would, but without the indent where the book styles a line as a heading, centred or
 * right-aligned text or sets no first-line indent. Returns the paragraph and the offset after its last line, or null
 * when a line is not found (text changed by replacement rules or translation), so the caller shows it plainly.
 */
fun NovelStyledText.formatParagraph(
    original: String,
    from: Int,
    indentEnabled: Boolean,
    spacingLines: Int,
): Pair<NovelStyledParagraph, Int>? {
    val separator = "\n".repeat(spacingLines.coerceIn(0, 3) + 1)
    val out = StringBuilder()
    val spans = mutableListOf<NovelStyleSpan>()
    val lines = mutableListOf<NovelStyledLine>()
    var cursor = from
    original.replace("\r\n", "\n").split(LINE_BREAKS).map(String::trim).filter(String::isNotEmpty).forEach { line ->
        val position = text.indexOf(line, cursor)
        if (position < 0) return null
        cursor = position + line.length
        if (out.isNotEmpty()) out.append(separator)
        val lineStart = out.length
        val paragraph = paragraphAt(position)
        if (indentEnabled && !isNovelChapterTitleLine(line) && paragraph?.takesIndent != false) {
            out.append(NOVEL_PARAGRAPH_INDENT)
        }
        val shift = out.length - position
        out.append(line)
        val lineSpans = spansIn(position, cursor)
        lineSpans.mapTo(spans) { it.copy(start = it.start + shift, end = it.end + shift) }
        lines += NovelStyledLine(lineStart, out.length, paragraph?.align ?: NovelTextAlign.START, paragraph?.heading ?: 0,
            maxOf(1f, lineSpans.maxOfOrNull { it.style.fontScale } ?: 1f))
    }
    if (lines.isEmpty()) return null
    return NovelStyledParagraph(out.toString(), spans, lines) to cursor
}

private val LINE_BREAKS = Regex("\\n+")
