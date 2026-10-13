package org.skepsun.kototoro.reader.novel

data class ParsedNovelImages(
    val text: String,
    val blockImagePaths: List<String>,
    val inlineImagePaths: List<String>,
)

private val HTML_IMG_TAG_REGEX = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
private val HTML_IMG_SRC_REGEX = Regex("""(?:data-src|src)\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
private val HTML_INLINE_TEXT_ATTRS = listOf("alt", "title")
private val HTML_BLOCK_BOUNDARIES = listOf(
    "</p>", "<p", "</div>", "<div", "</li>", "<li", "</h1", "<h1", "</h2", "<h2", "<br", "\n",
)

fun parseNovelImages(text: String): ParsedNovelImages {
    val blockImagePaths = mutableListOf<String>()
    val inlineImagePaths = mutableListOf<String>()
    val imagePattern = Regex("""📷\s*\[图片:\s*([^\]]+)\]""")

    var processedText = text
    imagePattern.findAll(text).forEach { match ->
        val imagePath = match.groupValues[1].trim()
        blockImagePaths.add(imagePath)
        processedText = processedText.replaceFirst(
            match.value,
            "[IMAGE_PLACEHOLDER_${blockImagePaths.size - 1}]",
        )
    }

    processedText = processedText.replace(HTML_IMG_TAG_REGEX) { matchResult ->
        val tag = matchResult.value
        val src = HTML_IMG_SRC_REGEX.find(tag)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()
        val inlineText = extractInlineImageText(tag)
        when {
            src.isNotEmpty() && shouldRenderHtmlImageInline(processedText, matchResult.range) -> {
                inlineImagePaths.add(src)
                "[INLINE_IMAGE_${inlineImagePaths.size - 1}]"
            }

            src.isNotEmpty() -> {
                blockImagePaths.add(src)
                "[IMAGE_PLACEHOLDER_${blockImagePaths.size - 1}]"
            }

            inlineText != null -> inlineText
            else -> ""
        }
    }

    processedText = processedText
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p>"), "\n")
        .replace(Regex("(?i)<p[^>]*>"), "")
        .replace(Regex("<[^>]+>"), "")

    return ParsedNovelImages(
        text = processedText,
        blockImagePaths = blockImagePaths,
        inlineImagePaths = inlineImagePaths,
    )
}

private fun shouldRenderHtmlImageInline(text: String, range: IntRange): Boolean {
    val start = findPreviousBoundary(text, range.first)
    val end = findNextBoundary(text, range.last + 1)
    val segment = text.substring(start, end)
    val visibleText = segment
        .replaceFirst(HTML_IMG_TAG_REGEX, " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return visibleText.isNotEmpty()
}

private fun findPreviousBoundary(text: String, index: Int): Int {
    var result = 0
    for (marker in HTML_BLOCK_BOUNDARIES) {
        val pos = text.lastIndexOf(marker, startIndex = index.coerceAtLeast(0))
        if (pos >= 0) {
            result = maxOf(result, pos)
        }
    }
    return result
}

private fun findNextBoundary(text: String, index: Int): Int {
    var result = text.length
    for (marker in HTML_BLOCK_BOUNDARIES) {
        val pos = text.indexOf(marker, startIndex = index.coerceAtLeast(0))
        if (pos >= 0) {
            result = minOf(result, pos)
        }
    }
    return result
}

private fun extractInlineImageText(tag: String): String? {
    return HTML_INLINE_TEXT_ATTRS.asSequence()
        .mapNotNull { attr ->
            Regex("""$attr\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
                .find(tag)
                ?.groupValues
                ?.getOrNull(1)
        }
        .map { value ->
            value.replace(Regex("<[^>]+>"), "")
                .replace("&nbsp;", " ")
                .trim()
        }
        .firstOrNull { value ->
            value.isNotBlank() &&
                value.length <= 3 &&
                value.none { it == '\n' || it == '\r' }
        }
}
