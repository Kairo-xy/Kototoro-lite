package org.skepsun.kototoro.tracking.discovery

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** IDs match Android's persisted ScrobblerService IDs. No account or list writes are performed here. */
enum class PublicTrackingSite(val id: Int, val title: String) {
    ANILIST(2, "AniList"), KITSU(4, "Kitsu"), BANGUMI(5, "Bangumi"),
}

data class TrackingMetadata(
    val service: Int,
    val remoteId: Long,
    val title: String,
    val alternateTitles: List<String> = emptyList(),
    val coverUrl: String? = null,
    val description: String? = null,
    val score: Float? = null,
    val scoreMax: Float = 10f,
    val tags: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
    val year: Int? = null,
    val totalChapters: Int? = null,
    val url: String,
    val mediaKind: String,
)

/** Platform-independent discovery, parsing and media identity. The platform owns the HTTP engine. */
class PublicTrackingApi(private val client: HttpClient) {
    private val bangumi = BangumiDiscoveryApi(client)

    suspend fun search(
        site: PublicTrackingSite,
        query: String,
        mediaKind: String,
        page: Int = 1,
    ): List<TrackingMetadata> {
        require(page > 0)
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        val anime = mediaKind == "VIDEO"
        return when (site) {
            PublicTrackingSite.BANGUMI -> parse(bangumi.search(text, (page - 1) * 10, anime))
                .array("data").map { bangumiItem(it.jsonObject, mediaKind, fullCover = false) }
            PublicTrackingSite.ANILIST -> {
                val format = if (mediaKind == "NOVEL") ", format: NOVEL" else ""
                val data = anilist("""query { Page(page: $page, perPage: 20) {
                    media(type: ${if (anime) "ANIME" else "MANGA"}, search: ${JsonPrimitive(text)},
                    sort: SEARCH_MATCH$format) { $ANILIST_SEARCH_FIELDS } } }""")
                data.obj("Page").array("media").map { anilistItem(it.jsonObject, mediaKind, fullCover = false) }
            }
            PublicTrackingSite.KITSU -> {
                val type = if (anime) "anime" else "manga"
                val data = client.prepareGet("https://kitsu.io/api/edge/$type") {
                    expectSuccess = true
                    url.parameters.append("filter[text]", text)
                    if (mediaKind == "NOVEL") url.parameters.append("filter[subtype]", "novel")
                    url.parameters.append("page[limit]", "20")
                    url.parameters.append("page[offset]", ((page - 1) * 20).toString())
                }.execute { parse(it.bodyAsText()) }
                data.array("data").map { kitsuItem(it.jsonObject, type, mediaKind, fullCover = false) }
            }
        }.filter { it.remoteId > 0 && it.title.isNotBlank() }
    }

    suspend fun details(site: PublicTrackingSite, id: Long, mediaKind: String): TrackingMetadata {
        require(id > 0)
        val item = when (site) {
            PublicTrackingSite.BANGUMI -> bangumiItem(parse(bangumi.details(id)), mediaKind)
            PublicTrackingSite.ANILIST -> anilistItem(anilist("query { Media(id: $id) { $ANILIST_FIELDS } }")
                .obj("Media"), mediaKind)
            PublicTrackingSite.KITSU -> {
                val type = if (mediaKind == "VIDEO") "anime" else "manga"
                val data = client.prepareGet("https://kitsu.io/api/edge/$type/$id") {
                    expectSuccess = true
                    url.parameters.append("include", "categories")
                }.execute { parse(it.bodyAsText()) }
                kitsuItem(data.obj("data"), type, mediaKind).copy(tags = data.array("included").mapNotNull {
                    it.jsonObject.takeIf { item -> item.text("type") == "categories" }?.obj("attributes")?.text("title")
                })
            }
        }
        require(item.remoteId == id && item.title.isNotBlank()) { "追踪网站返回了无效的作品详情" }
        return item
    }

    private suspend fun anilist(query: String): JsonObject {
        val result = client.preparePost("https://graphql.anilist.co") {
            expectSuccess = true
            header("Content-Type", "application/json")
            setBody(JsonObject(mapOf("query" to JsonPrimitive(query))).toString())
        }.execute { parse(it.bodyAsText()) }
        check(result.array("errors").isEmpty()) { "AniList 请求失败，请稍后重试" }
        return result["data"] as? JsonObject ?: error("AniList 返回了无效的数据")
    }

    private fun bangumiItem(json: JsonObject, kind: String, fullCover: Boolean = true): TrackingMetadata {
        val id = json.long("id") ?: 0
        val original = json.text("name")
        val images = json.obj("images")
        val info = json.array("infobox").mapNotNull { entry ->
            val obj = entry.jsonObject
            if (obj.text("key") !in listOf("作者", "原作", "导演")) null else obj["value"]
        }.flatMap { value -> when (value) {
            is JsonPrimitive -> listOfNotNull(value.contentOrNull)
            is JsonArray -> value.mapNotNull { (it as? JsonObject)?.text("v") }
            else -> emptyList()
        } }
        return TrackingMetadata(5, id, json.text("name_cn") ?: original.orEmpty(), listOfNotNull(original),
            (if (fullCover) images.text("large") else images.text("medium"))
                ?: images.text("common") ?: images.text("large"),
            json.text("summary"), json.obj("rating").float("score"),
            tags = json.array("tags").mapNotNull { it.jsonObject.text("name") }, authors = info.distinct(),
            year = json.text("date")?.take(4)?.toIntOrNull(), totalChapters = json.int("eps")?.takeIf { it > 0 },
            url = "https://bgm.tv/subject/$id", mediaKind = if (json.int("type") == 2) "VIDEO" else kind)
    }

    private fun anilistItem(json: JsonObject, kind: String, fullCover: Boolean = true): TrackingMetadata {
        val title = json.obj("title")
        val cover = json.obj("coverImage")
        return TrackingMetadata(2, json.long("id") ?: 0,
            title.text("userPreferred") ?: title.text("romaji") ?: title.text("native").orEmpty(),
            listOfNotNull(title.text("native"), title.text("english")),
            (if (fullCover) cover.text("extraLarge") else cover.text("medium")) ?: cover.text("large"),
            json.text("description"), json.float("meanScore"), 100f,
            json.array("genres").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            json.obj("staff").array("edges").mapNotNull { edge -> edge.jsonObject.takeIf {
                val role = it.text("role").orEmpty()
                role.contains("Story") || role.contains("Art") || role == "Original Creator" || role == "Director"
            }?.obj("node")?.obj("name")?.text("full") }.distinct(),
            json.obj("startDate").int("year"), json.int(if (kind == "VIDEO") "episodes" else "chapters"),
            json.text("siteUrl")
                ?: "https://anilist.co/${if (kind == "VIDEO") "anime" else "manga"}/${json.long("id")}",
            when {
                json.text("type") == "ANIME" -> "VIDEO"
                json.text("format") == "NOVEL" -> "NOVEL"
                else -> "MANGA"
            })
    }

    private fun kitsuItem(json: JsonObject, type: String, kind: String, fullCover: Boolean = true): TrackingMetadata {
        val attrs = json.obj("attributes")
        val titles = attrs.obj("titles")
        val cover = attrs.obj("posterImage")
        val id = json.long("id") ?: 0
        return TrackingMetadata(4, id, attrs.text("canonicalTitle") ?: titles.text("en_jp").orEmpty(),
            titles.values.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            (if (fullCover) cover.text("original") else cover.text("medium"))
                ?: cover.text("large") ?: cover.text("medium"), attrs.text("synopsis"),
            attrs.float("averageRating"), 100f, year = attrs.text("startDate")?.take(4)?.toIntOrNull(),
            totalChapters = attrs.int(if (type == "anime") "episodeCount" else "chapterCount"),
            url = "https://kitsu.io/$type/${attrs.text("slug") ?: id}",
            mediaKind = if (type == "anime") "VIDEO" else if (attrs.text("subtype") == "novel") "NOVEL" else kind)
    }

    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.array(key: String) = this[key] as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.long(key: String) = text(key)?.toLongOrNull()
    private fun JsonObject.int(key: String) = text(key)?.toIntOrNull()
    private fun JsonObject.float(key: String) = text(key)?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0 }

    private companion object {
        const val ANILIST_SEARCH_FIELDS = """id type format title { userPreferred romaji native english }
            coverImage { medium large } siteUrl meanScore"""
        const val ANILIST_FIELDS = """id type format title { userPreferred romaji native english }
            coverImage { extraLarge large } description(asHtml: false) siteUrl meanScore genres
            startDate { year } episodes chapters staff(perPage: 25) { edges { role node { name { full } } } }"""
    }
}
