package org.skepsun.kototoro.reader.novel

private val titleLinePattern = Regex("""^[《【].+[》】]$|^第[0-9一二三四五六七八九十百千零〇两]+[章节卷回部篇话集].*$""")

/** A short `《…》`/`【…】` line or a "第N章"-style heading, which stays flush and is styled as a title. */
fun isNovelChapterTitleLine(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isBlank()) return false
    if (trimmed.length > 40) return false
    return titleLinePattern.matches(trimmed)
}
