package org.skepsun.kototoro.ios.shims

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

/** OkHttp over the real URLSession bridge on macOS: only the network stage moves, OkHttp keeps the semantics. */
class NativeTransportTest {
    private lateinit var server: HttpServer
    private val seen = CopyOnWriteArrayList<Map<String, List<String>>>()
    private val large = Random(7).nextBytes(3 * 1024 * 1024 + 17)
    private lateinit var base: String

    @BeforeEach fun start() {
        assumeTrue(System.getProperty("kototoro.native.library") != null)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        fun route(path: String, handler: (HttpExchange) -> Unit) = server.createContext(path) { exchange ->
            seen += exchange.requestHeaders.mapKeys { it.key.lowercase() }
            try { handler(exchange) } finally { exchange.close() }
        }
        route("/text") { it.respond(200, "你好 native".toByteArray(), "Content-Type" to "text/plain; charset=utf-8", "X-Name" to "café") }
        route("/large") { it.respond(200, large) }
        route("/cookies") {
            it.responseHeaders.add("Set-Cookie", "a=1; Path=/")
            it.responseHeaders.add("Set-Cookie", "b=2; Path=/; Expires=Wed, 21 Oct 2037 07:28:00 GMT; HttpOnly")
            it.respond(200, "ok".toByteArray())
        }
        route("/redirect") {
            it.responseHeaders.add("Set-Cookie", "hop=yes; Path=/")
            it.responseHeaders.add("Location", "/text")
            it.respond(302, ByteArray(0))
        }
        route("/gzip") {
            val packed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { gz -> gz.write(large) } }.toByteArray()
            it.respond(200, packed, "Content-Encoding" to "gzip")
        }
        route("/echo") { it.respond(200, it.requestBody.readBytes(), "Content-Type" to (it.requestHeaders.getFirst("Content-Type") ?: "")) }
        route("/slow") { Thread.sleep(3000); it.respond(200, "late".toByteArray()) }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach fun stop() { if (::server.isInitialized) server.stop(0) }

    private fun HttpExchange.respond(code: Int, body: ByteArray, vararg headers: Pair<String, String>) {
        headers.forEach { (name, value) -> responseHeaders.add(name, value) }
        sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) responseBody.use { it.write(body) }
    }

    private val jar = object : CookieJar {
        val cookies = CopyOnWriteArrayList<Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { this.cookies.removeAll { c -> cookies.any { it.name == c.name } }; this.cookies += cookies }
        override fun loadForRequest(url: HttpUrl) = cookies.filter { it.matches(url) }
    }
    private val client = OkHttpClient.Builder().cookieJar(jar).build()
    private fun get(path: String, client: OkHttpClient = this.client) = client.newCall(Request.Builder().url(base + path).build()).execute()

    @Test fun `text body headers and non-ASCII values come through URLSession`() {
        val before = NativeTransport.REQUESTS.get()
        get("/text").use { response ->
            assertEquals(200, response.code)
            assertEquals("你好 native", response.body.string())
            assertEquals("café", response.header("X-Name"))
            assertEquals("text/plain; charset=utf-8", response.header("Content-Type"))
        }
        assertEquals(before + 1, NativeTransport.REQUESTS.get())
    }

    @Test fun `large bodies stream intact across small reads`() {
        get("/large").use { response ->
            val source = response.body.source()
            val out = ByteArrayOutputStream()
            val step = ByteArray(1000)
            while (true) { val n = source.inputStream().read(step); if (n < 0) break; out.write(step, 0, n) }
            assertArrayEquals(large, out.toByteArray())
        }
    }

    @Test fun `reads after the end keep reporting the end`() {
        get("/text").use { response ->
            val source = response.body.source()
            assertEquals("你好 native", source.readUtf8())
            assertTrue(source.exhausted())
            assertEquals(-1, source.read(okio.Buffer(), 10))
        }
    }

    @Test fun `each Set-Cookie reaches the cookie jar and cookies are sent back by OkHttp`() {
        get("/cookies").use { assertEquals(200, it.code) }
        assertEquals(setOf("a", "b"), jar.cookies.map { it.name }.toSet())
        val b = jar.cookies.single { it.name == "b" }
        assertTrue(b.httpOnly)
        assertTrue(b.persistent)
        get("/text").use { it.body.string() }
        val sent = seen.last()["cookie"].orEmpty().joinToString(";")
        assertTrue("a=1" in sent && "b=2" in sent, sent)
    }

    @Test fun `redirects are followed by OkHttp so each hop's cookies are kept`() {
        val before = NativeTransport.REQUESTS.get()
        get("/redirect").use { response ->
            assertEquals(200, response.code)
            assertEquals("你好 native", response.body.string())
            assertEquals(302, response.priorResponse?.code)
        }
        assertEquals(before + 2, NativeTransport.REQUESTS.get())
        assertTrue(jar.cookies.any { it.name == "hop" })
        assertTrue(seen.last()["cookie"].orEmpty().joinToString(";").contains("hop=yes"))
    }

    @Test fun `gzip decoded by URLSession is not decoded again`() {
        get("/gzip").use { response ->
            assertArrayEquals(large, response.body.bytes())
            assertNull(response.header("Content-Encoding"))
        }
    }

    @Test fun `request bodies and content type are sent`() {
        val body = "名字=值".toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
        client.newCall(Request.Builder().url("$base/echo").post(body).build()).execute().use { response ->
            assertEquals("名字=值", response.body.string())
            assertEquals("application/x-www-form-urlencoded; charset=utf-8", response.header("Content-Type"))
        }
    }

    @Test fun `network interceptors still run`() {
        val tagged = client.newBuilder().addNetworkInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("X-Network", "yes").build())
        }.build()
        get("/text", tagged).use { it.body.string() }
        assertEquals(listOf("yes"), seen.last()["x-network"])
    }

    @Test fun `cancel stops a waiting call promptly`() {
        val call = client.newCall(Request.Builder().url("$base/slow").build())
        Thread { Thread.sleep(300); call.cancel() }.start()
        val start = System.nanoTime()
        assertThrows(IOException::class.java) { call.execute().use { it.body.string() } }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2000)
    }

    @Test fun `call timeout cancels through OkHttp`() {
        val quick = client.newBuilder().callTimeout(500, TimeUnit.MILLISECONDS).build()
        assertThrows(IOException::class.java) { get("/slow", quick).use { it.body.string() } }
    }

    @Test fun `custom TLS trust keeps OkHttp's own connection`() {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
        val custom = client.newBuilder().sslSocketFactory(tls.socketFactory, trustAll).build()
        val before = NativeTransport.REQUESTS.get()
        get("/text", custom).use { assertEquals("你好 native", it.body.string()) }
        assertEquals(before, NativeTransport.REQUESTS.get())
    }
}
