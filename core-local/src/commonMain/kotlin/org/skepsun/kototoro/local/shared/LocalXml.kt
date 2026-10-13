package org.skepsun.kototoro.local.shared

/**
 * Just enough XML for EPUB packaging (container.xml, the OPF, toc.ncx and nav.xhtml): start tags with their attributes
 * and the text of simple elements. Namespace prefixes are ignored, so `opf:item` and `item` are the same element.
 */
internal object LocalXml {
    private val tagPattern = Regex("<([A-Za-z_][\\w.:-]*)((?:\\s+[^\\s=/>]+\\s*=\\s*(?:\"[^\"]*\"|'[^']*'))*)\\s*/?>")
    private val attributePattern = Regex("([^\\s=/>]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")

    class Tag(val name: String, val attributes: Map<String, String>, val start: Int, val end: Int) {
        operator fun get(name: String): String? = attributes[name]
    }

    fun tags(xml: String, name: String): List<Tag> = tagPattern.findAll(xml).mapNotNull { match ->
        val tagName = local(match.groupValues[1])
        if (!tagName.equals(name, ignoreCase = true)) return@mapNotNull null
        val attributes = attributePattern.findAll(match.groupValues[2]).associate {
            local(it.groupValues[1]).lowercase() to unescape(it.groupValues[2].ifEmpty { it.groupValues[3] })
        }
        Tag(tagName, attributes, match.range.first, match.range.last + 1)
    }.toList()

    /** Text inside each `<name>…</name>` (tags inside it removed), in document order. */
    fun texts(xml: String, name: String): List<String> {
        val pattern = Regex("<(?:[\\w.-]+:)?$name(?:\\s[^>]*)?>(.*?)</(?:[\\w.-]+:)?$name\\s*>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return pattern.findAll(xml).map { text(it.groupValues[1]) }.filter { it.isNotEmpty() }.toList()
    }

    fun text(fragment: String): String = unescape(fragment.replace(Regex("<[^>]*>"), " "))
        .replace(Regex("\\s+"), " ").trim()

    fun unescape(value: String): String = value.replace(Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);")) {
        when (val entity = it.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            else -> {
                val code = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
                code?.takeIf { c -> c in 1..0x10FFFF }?.let(::codePointToString) ?: it.value
            }
        }
    }

    fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun local(name: String) = name.substringAfter(':')

    private fun codePointToString(code: Int): String = if (code < 0x10000) code.toChar().toString() else {
        val offset = code - 0x10000
        charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
    }
}

/** Resolves an href inside the EPUB against the directory of the document that contains it. */
internal fun resolveHref(base: String, href: String): String {
    val target = decodePercent(href.substringBefore('#'))
    if (target.isEmpty()) return base
    val parts = (if (target.startsWith('/')) emptyList() else base.substringBeforeLast('/', "").split('/'))
        .filter { it.isNotEmpty() }.toMutableList()
    for (segment in target.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts += segment
        }
    }
    return parts.joinToString("/")
}

internal fun decodePercent(value: String): String {
    if ('%' !in value) return value
    val bytes = ArrayList<Byte>()
    var index = 0
    while (index < value.length) {
        val c = value[index]
        if (c == '%' && index + 2 < value.length) {
            val byte = value.substring(index + 1, index + 3).toIntOrNull(16)
            if (byte != null) { bytes += byte.toByte(); index += 3; continue }
        }
        bytes += c.toString().encodeToByteArray().toList()
        index++
    }
    return bytes.toByteArray().decodeToString()
}
