package org.skepsun.kototoro.local.shared

/** What an imported file is read as; Android's `LocalImportSupport` classifies by extension the same way. */
enum class LocalFormat(val contentType: String) {
    /** CBZ / ZIP of images, optionally with Kototoro's `index.json`. */
    ARCHIVE("MANGA"),
    EPUB("NOVEL"),
    TXT("NOVEL"),
    /** A single media file; the platform checks codec/container support before importing. */
    VIDEO("VIDEO"),
    ;

    companion object {
        fun of(fileName: String): LocalFormat? = when (fileName.substringAfterLast('.', "").lowercase()) {
            "cbz", "zip" -> ARCHIVE
            "epub" -> EPUB
            "txt" -> TXT
            "mp4", "m4v", "mov", "mkv", "ts", "webm", "avi" -> VIDEO
            else -> null
        }
    }
}

internal object LocalFiles {
    private val imageTypes = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
        "gif" to "image/gif", "avif" to "image/avif", "bmp" to "image/bmp", "heic" to "image/heic",
        "heif" to "image/heif", "jxl" to "image/jxl",
    )

    fun isImage(name: String): Boolean = extension(name) in imageTypes

    fun mimeType(name: String): String = imageTypes[extension(name)] ?: "image/jpeg"

    fun extension(name: String): String = name.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    /** Hidden files and macOS resource forks that archive tools add next to the pages. */
    fun isJunk(path: String): Boolean = path.split('/').any { it.startsWith(".") || it == "__MACOSX" }

    /** File name without folders and extension, as the reader titles a chapter folder. */
    fun baseName(path: String): String = path.trimEnd('/').substringAfterLast('/').substringBeforeLast('.')
}

/** "page2" before "page10", as Android's AlphanumComparator orders pages and chapter folders. */
internal object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a[i]
            val y = b[j]
            if (x.isDigit() && y.isDigit()) {
                val startI = i
                val startJ = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val left = a.substring(startI, i).trimStart('0')
                val right = b.substring(startJ, j).trimStart('0')
                if (left.length != right.length) return left.length - right.length
                val digits = left.compareTo(right)
                if (digits != 0) return digits
            } else {
                val order = x.lowercaseChar().compareTo(y.lowercaseChar())
                if (order != 0) return order
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
