package org.skepsun.kototoro.reader.novel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kototoro Lite: automatic translation has been removed.
 *
 * This placeholder keeps the API the novel reader and the download worker still call. It never
 * translates anything: chapters produce no translation updates and book questions return an empty answer.
 */
@Singleton
class NovelTranslationProcessor @Inject constructor() {

    fun clearCache() = Unit

    @Suppress("UNUSED_PARAMETER")
    suspend fun askBook(
        bookTitle: String,
        chapterTitle: String,
        excerpt: String,
        question: String,
        contextBefore: String = "",
        contextAfter: String = "",
    ): String = ""

    @Suppress("UNUSED_PARAMETER")
    fun translateChapterFlow(
        chapterIndex: Int,
        content: String,
        sourceLang: String,
        targetLang: String,
        displayMode: NovelTranslationDisplayMode,
        bookKey: String? = null,
    ): Flow<NovelChapterTranslation> = emptyFlow()
}
