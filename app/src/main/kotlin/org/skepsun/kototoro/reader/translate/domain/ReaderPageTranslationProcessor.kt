package org.skepsun.kototoro.reader.translate.domain

import android.net.Uri
import org.skepsun.kototoro.parsers.model.ContentPage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kototoro Lite: OCR and page translation have been removed.
 *
 * This placeholder keeps the one call the download worker still makes. It never translates anything and
 * always returns the original page, so no OCR or translation code runs.
 */
@Singleton
class ReaderPageTranslationProcessor @Inject constructor() {

    @Suppress("UNUSED_PARAMETER")
    suspend fun process(
        page: ContentPage,
        sourceUri: Uri,
        autoRecognizerLanguage: String? = null,
        forceEnabled: Boolean = false,
    ): Uri = sourceUri

    @Suppress("UNUSED_PARAMETER")
    suspend fun process(
        page: ContentPage,
        sourceUri: Uri,
        forceEnabled: Boolean,
    ): Uri = sourceUri
}
