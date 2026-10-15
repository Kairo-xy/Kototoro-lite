package org.skepsun.kototoro.tracking.discovery

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PublicTrackingApiTest {
    @Test
    fun `bangumi preserves mirror query unicode media type and page offset`() = runTest {
        withClient({ request ->
            assertEquals("mirror.invalid", request.url.host)
            assertEquals("/api/v0/search/subjects", request.url.encodedPath)
            assertEquals("30", request.url.parameters["offset"])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("葬送 + & \" ? #", body["keyword"]?.jsonPrimitive?.content)
            assertEquals(2, body["filter"]?.jsonObject?.get("type")?.jsonArray?.single()?.jsonPrimitive?.int)
            respond("""{"data":[]}""")
        }) { client ->
            BangumiDiscoveryApi(client).search("葬送 + & \" ? #", 30, true, "https://mirror.invalid/api/")
        }
    }

    @Test
    fun `bangumi details map full cover summary tags scores and infobox authors`() = runTest {
        withClient({ request ->
            assertEquals("/v0/subjects/42", request.url.encodedPath)
            respond("""{"id":42,"name":"Original","name_cn":"中文标题","type":2,"summary":"简介",
                "images":{"large":"https://image.invalid/full.jpg"},"rating":{"score":8.7},
                "date":"2024-01-01","eps":28,"tags":[{"name":"冒险"}],
                "infobox":[{"key":"导演","value":[{"v":"导演甲"}]}]}""")
        }) { client ->
            val item = PublicTrackingApi(client).details(PublicTrackingSite.BANGUMI, 42, "VIDEO")
            assertEquals("中文标题", item.title)
            assertEquals(listOf("Original"), item.alternateTitles)
            assertEquals("https://image.invalid/full.jpg", item.coverUrl)
            assertEquals("简介", item.description)
            assertEquals(8.7f, item.score)
            assertEquals(listOf("导演甲"), item.authors)
            assertEquals(2024, item.year)
            assertEquals(28, item.totalChapters)
            assertEquals("VIDEO", item.mediaKind)
        }
    }

    @Test
    fun `anilist novel searches encode quotes as data and retain page and format`() = runTest {
        withClient({ request ->
            assertEquals("graphql.anilist.co", request.url.host)
            val query = Json.parseToJsonElement((request.body as TextContent).text).jsonObject["query"]!!.jsonPrimitive.content
            assertTrue(query.contains("page: 3"))
            assertTrue(query.contains("type: MANGA"))
            assertTrue(query.contains("format: NOVEL"))
            assertTrue(query.contains(JsonPrimitive("a\"b\\c").toString()))
            respond("""{"data":{"Page":{"media":[{"id":12,"title":{"native":"小说"},"format":"NOVEL",
                "meanScore":82,"description":"简介","coverImage":{"large":"https://img.invalid/12"}}]}}}""")
        }) { client ->
            val item = PublicTrackingApi(client).search(PublicTrackingSite.ANILIST, "a\"b\\c", "NOVEL", 3).single()
            assertEquals("小说", item.title)
            assertEquals("NOVEL", item.mediaKind)
            assertEquals(100f, item.scoreMax)
        }
    }

    @Test
    fun `kitsu search encodes reserved text characters and uses exact media type for details`() = runTest {
        withClient({ request ->
            if (request.url.encodedPath.endsWith("/17")) {
                assertEquals("/api/edge/anime/17", request.url.encodedPath)
                respond("""{"data":{"id":"17","attributes":{"canonicalTitle":"动画","slug":"anime-slug",
                    "averageRating":"79.5","posterImage":{"original":"https://img.invalid/17"}}},"included":[]}""")
            } else {
                assertEquals("空 格 + & ? #", request.url.parameters["filter[text]"])
                assertEquals("20", request.url.parameters["page[offset]"])
                assertEquals("/api/edge/manga", request.url.encodedPath)
                respond("""{"data":[]}""")
            }
        }) { client ->
            val api = PublicTrackingApi(client)
            api.search(PublicTrackingSite.KITSU, "空 格 + & ? #", "MANGA", 2)
            val item = api.details(PublicTrackingSite.KITSU, 17, "VIDEO")
            assertEquals("https://kitsu.io/anime/anime-slug", item.url)
            assertEquals(79.5f, item.score)
        }
    }

    @Test
    fun `errors malformed identities and cancellation remain failures instead of empty success`() = runTest {
        withClient({ respond("""{"errors":[{"message":"rate limited"}],"data":null}""") }) { client ->
            assertThrows(IllegalStateException::class.java) {
                kotlinx.coroutines.runBlocking { PublicTrackingApi(client).search(PublicTrackingSite.ANILIST, "test", "MANGA") }
            }
        }
        withClient({ respond("""{"id":43,"name":"Wrong identity"}""") }) { client ->
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking { PublicTrackingApi(client).details(PublicTrackingSite.BANGUMI, 42, "MANGA") }
            }
        }
        withClient({ throw CancellationException("cancelled") }) { client ->
            assertThrows(CancellationException::class.java) {
                kotlinx.coroutines.runBlocking { PublicTrackingApi(client).search(PublicTrackingSite.KITSU, "test", "MANGA") }
            }
        }
        withClient({ respond("unavailable", HttpStatusCode.ServiceUnavailable) }) { client ->
            assertThrows(io.ktor.client.plugins.ServerResponseException::class.java) {
                kotlinx.coroutines.runBlocking { PublicTrackingApi(client).details(PublicTrackingSite.BANGUMI, 42, "MANGA") }
            }
        }
    }

    private suspend fun withClient(handler: MockRequestHandler, block: suspend (HttpClient) -> Unit) {
        val engine = MockEngine(handler)
        val client = HttpClient(engine)
        try { block(client) } finally { client.close(); engine.close() }
    }
}
