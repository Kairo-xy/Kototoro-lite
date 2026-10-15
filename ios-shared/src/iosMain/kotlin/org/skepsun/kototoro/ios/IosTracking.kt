package org.skepsun.kototoro.ios

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.header
import io.ktor.client.plugins.defaultRequest
import kotlinx.coroutines.CancellationException
import org.skepsun.kototoro.core.db.entity.TrackingSiteItemEntity
import org.skepsun.kototoro.core.library.SourceTracking
import org.skepsun.kototoro.core.library.encodeTrackingStrings
import org.skepsun.kototoro.core.library.decodeTrackingStrings
import org.skepsun.kototoro.tracking.discovery.PublicTrackingApi
import org.skepsun.kototoro.tracking.discovery.PublicTrackingSite
import org.skepsun.kototoro.tracking.discovery.TrackingMetadata

data class IosTrackingSite(val id: Int, val title: String)

class IosTrackingItem internal constructor(
    internal val value: TrackingMetadata,
    internal val detailed: Boolean = false,
) {
    val key: String get() = "${value.service}:${value.remoteId}"
    val service: Int get() = value.service
    val remoteId: String get() = value.remoteId.toString()
    val siteTitle: String get() = trackingSiteTitle(service)
    val title: String get() = value.title
    val alternateTitles: List<String> get() = value.alternateTitles.filter { it != title }.distinct()
    val coverUrl: String? get() = value.coverUrl
    val summary: String? get() = value.description
    val authors: List<String> get() = value.authors
    val tags: List<String> get() = value.tags
    val year: Int? get() = value.year
    val score: Float? get() = value.score
    val scoreMax: Float get() = value.scoreMax
    val url: String get() = value.url
}

internal fun trackingSiteTitle(service: Int): String =
    PublicTrackingSite.entries.firstOrNull { it.id == service }?.title
        ?: when (service) {
            1 -> "Shikimori"
            3 -> "MyAnimeList"
            6 -> "MangaUpdates"
            7 -> "SIMKL"
            else -> "追踪网站"
        }

/** Native composition root; HTTP/parsing and binding/display rules live in the shared modules. */
class IosTracking internal constructor(
    private val library: IosLibrary,
    private val fetchDetails: suspend (PublicTrackingSite, Long, String) -> TrackingMetadata = { site, id, kind ->
        api.details(site, id, kind)
    },
) {
    private val store = SourceTracking(library.database)
    val sites: List<IosTrackingSite> = listOf(PublicTrackingSite.BANGUMI, PublicTrackingSite.ANILIST,
        PublicTrackingSite.KITSU).map { IosTrackingSite(it.id, it.title) }

    @Throws(Exception::class)
    suspend fun search(service: Int, query: String, mediaKind: String, page: Int): List<IosTrackingItem> = request {
        api.search(site(service), query, mediaKind, page).map(::IosTrackingItem)
    }

    @Throws(Exception::class)
    suspend fun details(item: IosTrackingItem, mediaKind: String): IosTrackingItem = request {
        IosTrackingItem(fetchDetails(site(item.service), item.value.remoteId, mediaKind), detailed = true)
    }

    @Throws(Exception::class)
    suspend fun bindings(content: IosSourceContent): List<IosTrackingItem> =
        store.bindings(content.value.id).map { IosTrackingItem(it.toMetadata(content.mediaKind)) }

    @Throws(Exception::class)
    suspend fun selected(content: IosSourceContent): IosTrackingItem? =
        store.selected(content.value.id)?.let { IosTrackingItem(it.toMetadata(content.mediaKind)) }

    @Throws(Exception::class)
    suspend fun decorate(content: IosSourceContent): IosSourceContent =
        IosSourceContent(content.value, store.selected(content.value.id))

    @Throws(Exception::class)
    suspend fun bind(content: IosSourceContent, item: IosTrackingItem) {
        // Search results must resolve before changing display authority; previews already hold complete details.
        val details = if (item.detailed) item.value else {
            request { fetchDetails(site(item.service), item.value.remoteId, content.mediaKind) }
        }
        require(details.mediaKind == content.mediaKind) { "作品类型不匹配，请选择相同类型的作品资料" }
        library.saveDetails(content)
        store.bind(content.value, details.toEntity())
    }

    @Throws(Exception::class)
    suspend fun select(content: IosSourceContent, item: IosTrackingItem?) =
        store.select(content.value, item?.service, item?.value?.remoteId)

    @Throws(Exception::class)
    suspend fun unbind(content: IosSourceContent, service: Int) = store.unbind(content.value, service)

    internal suspend fun selected(ids: List<Long>): Map<Long, TrackingSiteItemEntity> = store.selected(ids)

    private fun site(id: Int) = PublicTrackingSite.entries.firstOrNull { it.id == id }
        ?: error("此追踪网站尚不支持在线检索")

    private suspend fun <T> request(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HttpRequestTimeoutException) {
        throw IllegalStateException("连接超时，请重试或切换网站", error)
    } catch (error: ResponseException) {
        throw IllegalStateException("网站暂时无法响应（HTTP ${error.response.status.value}），请稍后重试", error)
    } catch (error: Exception) {
        throw IllegalStateException("暂时无法读取作品资料，请检查网络或切换网站", error)
    }

    private companion object {
        val api by lazy {
            PublicTrackingApi(HttpClient(Darwin) {
                expectSuccess = true
                install(HttpTimeout) { connectTimeoutMillis = 15_000; requestTimeoutMillis = 30_000 }
                defaultRequest { header("User-Agent", "Kototoro-iOS/1.0") }
            })
        }
    }
}

internal fun TrackingMetadata.toEntity(): TrackingSiteItemEntity {
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    return TrackingSiteItemEntity(
        service = service,
        remoteId = remoteId,
        title = title,
        altTitles = encodeTrackingStrings(alternateTitles),
        primaryTitle = null,
        secondaryTitle = null,
        rating = score,
        scoreMax = scoreMax,
        rank = null,
        summary = description,
        progressText = null,
        updatedAtText = null,
        tags = encodeTrackingStrings(tags),
        year = year,
        authors = encodeTrackingStrings(authors),
        coverUrl = coverUrl,
        totalEpisodes = totalChapters,
        publishDate = null,
        siteUrl = url,
        cachedAt = now,
        updatedAt = now,
    )
}

private fun TrackingSiteItemEntity.toMetadata(kind: String) = TrackingMetadata(service, remoteId, title,
    decodeTrackingStrings(altTitles), coverUrl, summary, rating, scoreMax ?: 10f, decodeTrackingStrings(tags),
    decodeTrackingStrings(authors), year, totalEpisodes, siteUrl.orEmpty(), kind)
