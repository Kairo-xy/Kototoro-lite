package org.skepsun.kototoro.reader.novel

/**
 * 小说翻译展示模式：
 * - TRANSLATION_ONLY：仅显示译文，替换原文
 * - BILINGUAL：双语对照，原文灰色小字 + 译文正常样式
 */
enum class NovelTranslationDisplayMode {
    TRANSLATION_ONLY,
    BILINGUAL,
}

/**
 * 段落类型
 */
enum class NovelParagraphType {
    TEXT,   // 普通文字段落，参与翻译
    IMAGE,  // 图片占位段落，跳过翻译
}

/**
 * 一个原始段落
 */
data class NovelParagraph(
    val index: Int,
    val type: NovelParagraphType,
    val originalText: String,
)

/**
 * 一章的翻译结果（可能是部分结果，每批完成后 emit 一次）
 *
 * @param chapterIndex  章节绝对索引
 * @param paragraphs    原始段落列表（含 IMAGE 段落）
 * @param translations  TEXT 段落的翻译结果 paragraphIndex -> translatedText
 * @param displayMode   展示模式
 * @param isComplete    是否为最终完成状态（false = 翻译中，仍在进行）
 */
data class NovelChapterTranslation(
    val chapterIndex: Int,
    val paragraphs: List<NovelParagraph>,
    val translations: Map<Int, String>,
    val displayMode: NovelTranslationDisplayMode,
    val isComplete: Boolean = false,
)
