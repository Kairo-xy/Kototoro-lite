package org.skepsun.kototoro.reader.novel.compose

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import org.skepsun.kototoro.reader.novel.NovelDocumentBlock
import org.skepsun.kototoro.reader.novel.NovelStyledParagraph
import org.skepsun.kototoro.reader.novel.NovelStyledText
import org.skepsun.kototoro.reader.novel.NovelTextAlign
import org.skepsun.kototoro.reader.novel.formatParagraph

/**
 * The book's styles of the untranslated text blocks, by block key: each block is found in [styles] after the previous
 * one, so repeated lines keep their order. Blocks whose text the book does not contain (changed by replacement rules)
 * are left out and shown plainly.
 */
internal fun styleNovelBlocks(
    blocks: List<NovelDocumentBlock>,
    styles: NovelStyledText?,
    indentEnabled: Boolean,
    spacingLines: Int,
): Map<String, NovelStyledParagraph> {
    if (styles == null) return emptyMap()
    val result = HashMap<String, NovelStyledParagraph>()
    var cursor = 0
    blocks.forEach { block ->
        if (block !is NovelDocumentBlock.Text || block.translation != null || block.inlineImages.isNotEmpty()) return@forEach
        val (paragraph, next) = styles.formatParagraph(block.original, cursor, indentEnabled, spacingLines) ?: return@forEach
        result[block.key] = paragraph
        cursor = next
    }
    return result
}

/**
 * The paragraph with the book's inline styles and, per line, its alignment; lines with larger text (headings) get a
 * proportionally taller line height so the reader's fixed line height does not clip them.
 */
internal fun NovelStyledParagraph.toAnnotatedString(style: TextStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    lines.forEach { line ->
        if (line.align == NovelTextAlign.START && line.fontScale == 1f) return@forEach
        val textAlign = when (line.align) {
            NovelTextAlign.CENTER -> TextAlign.Center
            NovelTextAlign.END -> TextAlign.End
            NovelTextAlign.JUSTIFY -> TextAlign.Justify
            NovelTextAlign.START -> TextAlign.Start
        }
        addStyle(
            ParagraphStyle(
                textAlign = textAlign,
                lineHeight = if (style.lineHeight.isSpecified) style.lineHeight * line.fontScale else TextUnit.Unspecified,
            ),
            line.start,
            line.end,
        )
    }
    spans.forEach { span ->
        val value = span.style
        val decorations = listOfNotNull(
            TextDecoration.Underline.takeIf { value.underline },
            TextDecoration.LineThrough.takeIf { value.strike },
        )
        addStyle(
            SpanStyle(
                fontWeight = if (value.bold) FontWeight.Bold else null,
                fontStyle = if (value.italic) FontStyle.Italic else null,
                textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
                baselineShift = when (value.verticalPosition) {
                    1 -> BaselineShift.Superscript
                    -1 -> BaselineShift.Subscript
                    else -> null
                },
                fontSize = if (value.fontScale != 1f && style.fontSize.isSpecified) {
                    style.fontSize * value.fontScale
                } else {
                    TextUnit.Unspecified
                },
            ),
            span.start,
            span.end,
        )
    }
}
