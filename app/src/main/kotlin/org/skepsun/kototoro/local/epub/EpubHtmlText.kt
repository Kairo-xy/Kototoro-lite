package org.skepsun.kototoro.local.epub

import org.skepsun.kototoro.reader.novel.novelHtmlToText

/**
 * Plain text of one EPUB content document, as the novel reader shows it.
 *
 * @param keepImages whether `<img>` becomes a `📷 [图片: src]` placeholder paragraph (content
 * documents) or is dropped (plain text extraction). The original src is kept so that relative paths
 * can be resolved afterwards.
 */
internal fun epubHtmlToText(html: String, keepImages: Boolean): String = novelHtmlToText(html, keepImages)
