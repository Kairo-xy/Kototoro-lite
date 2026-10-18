package org.skepsun.kototoro.reader.domain

import android.net.Uri
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.skepsun.kototoro.parsers.model.ContentPage
import javax.inject.Inject

/**
 * Kototoro Lite: page translation (OCR + translation layer) has been removed.
 *
 * This class keeps the API the reader still calls so the reader code stays unchanged, but it never
 * schedules any work: pages are always shown as loaded and the translation state is always IDLE.
 */
@ActivityRetainedScoped
class ReaderPageEnhancementController @Inject constructor() {

    private val translationUpdates = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    private val translationStatusUpdates = MutableSharedFlow<TranslationLayerStateEvent>(extraBufferCapacity = 1)
    private val translationDebugLogUpdates = MutableSharedFlow<Long>(extraBufferCapacity = 1)

    fun setTranslationLanguageContext(translatedLanguage: String?, sourceLanguage: String?, branch: String?) = Unit

    data class PreparedPage(
        val displayUri: Uri,
        val translationSourceUri: Uri,
        val state: TranslationLayerState,
        val shouldScheduleTranslation: Boolean,
    )

    fun currentWorkSignature(): String = "translation-removed"

    fun observeTranslationUpdates(): Flow<Long> = translationUpdates

    fun observeTranslationStatusUpdates(): Flow<TranslationLayerStateEvent> = translationStatusUpdates

    fun observeTranslationDebugLogUpdates(): Flow<Long> = translationDebugLogUpdates

    fun getTranslationDebugLog(pageId: Long): String = ""

    @Suppress("UNUSED_PARAMETER")
    suspend fun resolveDisplayVariant(
        page: ContentPage,
        currentUri: Uri,
        showTranslated: Boolean,
    ): Uri? = currentUri

    @Suppress("UNUSED_PARAMETER")
    suspend fun preparePage(
        page: ContentPage,
        sourceUri: Uri,
        convertZipBitmap: suspend (Uri) -> Uri,
    ): PreparedPage = PreparedPage(
        displayUri = sourceUri,
        translationSourceUri = sourceUri,
        state = TranslationLayerState.IDLE,
        shouldScheduleTranslation = false,
    )

    @Suppress("UNUSED_PARAMETER")
    fun scheduleTranslation(
        page: ContentPage,
        sourceUri: Uri,
        scope: CoroutineScope,
        onRendered: () -> Unit,
    ) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun invalidateTranslationTask(pageId: Long) = Unit

    fun cancelAllTranslationTasks() = Unit

    @Suppress("UNUSED_PARAMETER")
    suspend fun invalidateTranslationCacheForPage(pageId: Long) = Unit

    suspend fun invalidateTranslationCaches() = Unit
}
