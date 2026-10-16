package org.skepsun.kototoro.ios

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceChapterContent
import org.skepsun.kototoro.core.source.SourceContent

/** Persist source-owned fields instead of reconstructing requests from Swift's presentation values. */
class IosDownloadArchive {
    @Throws(Exception::class)
    fun encode(content: IosSourceContent, chapter: IosSourceChapter): String {
        require(content.value.source.name == chapter.value.source.name) { "下载章节与作品来源不一致" }
        require(content.mediaKind in DOWNLOADABLE) { "当前仅支持漫画与小说章节下载" }
        // A thousand-chapter catalog must not be duplicated once per queued chapter.
        return Json.encodeToString(Request(content.value.copy(chapters = null), chapter.value))
    }

    @Throws(Exception::class)
    fun decode(json: String): IosDownloadRequest {
        val request = Json.decodeFromString<Request>(json)
        require(request.version == 1) { "下载任务版本尚不支持" }
        val content = IosSourceContent(request.content)
        val chapter = IosSourceChapter(request.chapter)
        require(request.content.source.name == request.chapter.source.name) { "下载章节与作品来源不一致" }
        require(content.mediaKind in DOWNLOADABLE) { "当前仅支持漫画与小说章节下载" }
        return IosDownloadRequest(content, chapter)
    }

    @Serializable
    private data class Request(val content: SourceContent, val chapter: SourceChapter, val version: Int = 1)

    private companion object {
        val DOWNLOADABLE = setOf("MANGA", "NOVEL")
    }
}

/** A downloaded novel chapter: its HTML and pictures with the headers the source requires, for offline reading. */
class IosChapterTextArchive {
    fun encode(text: IosChapterText): String = Json.encodeToString(Stored(text.value))

    @Throws(Exception::class)
    fun decode(json: String): IosChapterText {
        val stored = Json.decodeFromString<Stored>(json)
        require(stored.version == 1) { "离线章节版本尚不支持" }
        return IosChapterText(stored.content)
    }

    @Serializable
    private data class Stored(val content: SourceChapterContent, val version: Int = 1)
}

class IosDownloadRequest internal constructor(val content: IosSourceContent, val chapter: IosSourceChapter)
