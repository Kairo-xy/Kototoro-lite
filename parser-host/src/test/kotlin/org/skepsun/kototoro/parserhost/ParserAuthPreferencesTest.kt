package org.skepsun.kototoro.parserhost

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.SourceEndpoint
import org.skepsun.kototoro.core.source.SourceInvalidArgumentException
import org.skepsun.kototoro.core.source.SourcePreferenceEdit
import org.skepsun.kototoro.core.source.SourcePreferenceKind
import org.skepsun.kototoro.core.source.SourcePreferenceNode
import org.skepsun.kototoro.core.source.SourcePreferenceScreen
import org.skepsun.kototoro.core.source.SourcePreferenceUpdateStatus
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourcePreferences
import org.skepsun.kototoro.core.source.SourceProtocolClient
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.ContentParserAuthProvider
import java.nio.file.Path

/** Exercises the wrapped factory and the same JSON preference contract consumed by Swift. */
class ParserAuthPreferencesTest {
    @TempDir lateinit var root: Path
    private lateinit var platform: TestPlatform
    private lateinit var registry: ParserPluginRegistry
    private lateinit var runtime: ParserSourceRuntime

    @BeforeEach
    fun open() {
        platform = TestPlatform(root)
        registry = ParserPluginRegistry(platform)
        registry.load(Fixtures.kototoro, "kototoro-parsers")
        registry.load(Fixtures.kotatsu, "kotatsu-parsers-redo")
        registry.load(Fixtures.tsuki, "uma")
        runtime = ParserSourceRuntime(registry, platform)
    }

    @AfterEach
    fun close() {
        registry.close()
        platform.close()
    }

    private fun SourcePreferenceScreen.node(id: String): SourcePreferenceNode =
        (nodes + nodes.flatMap { it.children }).single { it.id == id }

    @Test
    fun `wrapped credentials and custom settings survive the JSON boundary`() = runBlocking {
        registry.withParser(SOURCE) { assertFalse(it.parser is ContentParserAuthProvider) }
        val client = SourceProtocolClient(SourceEndpoint(runtime)) { "settings-test" }
        var screen = client.getPreferences(SOURCE)
        assertEquals("未登录", screen.node("auth:status").summary)
        assertTrue(screen.node("auth:password").isSecret)
        assertFalse(screen.node("auth:login").enabled)
        assertEquals("https://fixture.example/account/login", screen.node("auth:browser").actionUrl)
        assertTrue(screen.node("config:domain").allowsCustomValue)
        assertEquals(SourcePreferenceKind.TEXT, screen.node("config:epub_translations").kind)
        assertEquals(SourcePreferenceKind.TOGGLE, screen.node("config:epub_parallel_translations").kind)
        assertEquals(SourcePreferenceKind.CHOICE, screen.node("config:preferred_language").kind)

        screen = client.updatePreference(
            SOURCE, screen.revision, "auth:username", SourcePreferenceValue.Text(" reader "),
        ).screen
        screen = client.updatePreference(
            SOURCE, screen.revision, "auth:password", SourcePreferenceValue.Text(PASSWORD),
        ).screen
        assertTrue(screen.node("auth:login").enabled)
        val result = client.updatePreference(SOURCE, screen.revision, "auth:login", SourcePreferenceValue.Action())
        assertEquals(SourcePreferenceUpdateStatus.ACCEPTED, result.status)
        assertEquals("已登录为: reader", result.screen.node("auth:status").summary)
        assertEquals(SourcePreferenceValue.Text(PASSWORD), result.screen.node("auth:password").value)
        val badPassword = client.updatePreference(
            SOURCE, result.screen.revision, "auth:password", SourcePreferenceValue.Text("wrong"),
        )
        val rejected = client.updatePreference(
            SOURCE, badPassword.screen.revision, "auth:login", SourcePreferenceValue.Action(),
        )
        assertEquals(SourcePreferenceUpdateStatus.REJECTED, rejected.status)
        assertEquals("已登录为: reader", rejected.screen.node("auth:status").summary)
        assertTrue(rejected.screen.node("auth:error").summary.contains("登录失败"))
        val cleared = client.updatePreference(
            SOURCE, rejected.screen.revision, "auth:clear_password", SourcePreferenceValue.Action(),
        )
        assertEquals(SourcePreferenceValue.Text(""), cleared.screen.node("auth:password").value)
        assertEquals("已登录为: reader", cleared.screen.node("auth:status").summary)
    }

    @Test
    fun `every parser architecture exposes delegated browser authorization`() = runBlocking {
        for (name in listOf(SOURCE, "FIXTURE_KOTATSU_ONLY", "FIXTURE_TSUKI_ONLY")) {
            val screen = runtime.getPreferences(name)
            assertEquals("未登录", screen.node("auth:status").summary, name)
            assertEquals("https://fixture.example/account/login", screen.node("auth:browser").actionUrl, name)
        }
        assertEquals(SourcePreferenceKind.TOGGLE,
            runtime.getPreferences("FIXTURE_KOTATSU_ONLY").node("config:disable_updates").kind)
    }

    @Test
    fun `credential and action types are validated without clearing saved values`() = runBlocking {
        val screen = runtime.getPreferences(SOURCE)
        assertThrows<SourceInvalidArgumentException> {
            runBlocking {
                runtime.updatePreference(SOURCE, screen.revision, "auth:username", SourcePreferenceValue.Toggle(true))
            }
        }
        assertThrows<SourceInvalidArgumentException> {
            runBlocking {
                runtime.updatePreference(SOURCE, screen.revision, "auth:login", SourcePreferenceValue.Text(""))
            }
        }
        val browserOnly = runtime.getPreferences("FIXTURE_TSUKI_ONLY")
        assertThrows<SourceInvalidArgumentException> {
            runBlocking {
                runtime.updatePreference(
                    browserOnly.source.name, browserOnly.revision, "auth:password", SourcePreferenceValue.Text("x"),
                )
            }
        }
    }

    @Test
    fun `persistence failures are reported for both saving and clearing credentials`() = runBlocking {
        registry.withParser(SOURCE) { handle ->
            val stored = platform.store.open(ParserSourceRuntime.configNamespace(SOURCE))
            val failing = object : SourcePreferences by stored {
                override fun edit(edit: SourcePreferenceEdit): Boolean { stored.edit(edit); return false }
            }
            val preferences = ParserPreferences(
                handle.info.source, emptyList(), ParserSourceConfig(failing), handle.parser,
            )
            val screen = preferences.screen()
            val saved = preferences.update(screen.revision, "auth:password", SourcePreferenceValue.Text(PASSWORD))
            assertEquals(SourcePreferenceUpdateStatus.PERSISTENCE_FAILED, saved.status)
            val cleared = preferences.update(
                saved.screen.revision, "auth:clear_password", SourcePreferenceValue.Action(),
            )
            assertEquals(SourcePreferenceUpdateStatus.PERSISTENCE_FAILED, cleared.status)
        }
    }

    @Test
    fun `login errors do not expose credentials and cancellation is propagated`() = runBlocking {
        var screen = runtime.getPreferences(SOURCE)
        screen = runtime.updatePreference(
            SOURCE, screen.revision, "auth:username", SourcePreferenceValue.Text("throw"),
        ).screen
        screen = runtime.updatePreference(
            SOURCE, screen.revision, "auth:password", SourcePreferenceValue.Text(PASSWORD),
        ).screen
        val failed = runtime.updatePreference(SOURCE, screen.revision, "auth:login", SourcePreferenceValue.Action())
        assertEquals(SourcePreferenceUpdateStatus.REJECTED, failed.status)
        assertFalse(failed.screen.node("auth:error").summary.contains(PASSWORD))
        assertEquals("未登录", failed.screen.node("auth:status").summary)
        screen = runtime.updatePreference(
            SOURCE, failed.screen.revision, "auth:username", SourcePreferenceValue.Text("cancel"),
        ).screen
        assertThrows<CancellationException> {
            runBlocking {
                runtime.updatePreference(SOURCE, screen.revision, "auth:login", SourcePreferenceValue.Action())
            }
        }
    }

    @Test
    fun `custom mirror values cannot contain URL credentials queries or fragments`() = runBlocking {
        var screen = runtime.getPreferences(SOURCE)
        val invalid = listOf("reader:password@mirror.test", "mirror.test?x=1", "mirror.test#part", "mirror.test\\path")
        for (domain in invalid) {
            val result = runtime.updatePreference(
                SOURCE, screen.revision, "config:domain", SourcePreferenceValue.Text(domain),
            )
            assertEquals(SourcePreferenceUpdateStatus.REJECTED, result.status, domain)
            screen = result.screen
        }
        val result = runtime.updatePreference(
            SOURCE, screen.revision, "config:domain", SourcePreferenceValue.Text("mirror.test:8443"),
        )
        assertEquals(SourcePreferenceUpdateStatus.ACCEPTED, result.status)
    }

    @Test
    fun `unavailable authorization is not presented as logged out`() = runBlocking {
        registry.withParser(SOURCE) { handle ->
            val parser = object : ContentParser by handle.parser {
                override val authorizationProvider = object : ContentParserAuthProvider {
                    override val authUrl = "https://fixture.example/login"
                    override suspend fun isAuthorized(): Boolean = error("offline")
                    override suspend fun getUsername(): String = error("unreachable")
                }
            }
            val config = ParserSourceConfig(platform.store.open(ParserSourceRuntime.configNamespace(SOURCE)))
            val screen = ParserPreferences(handle.info.source, emptyList(), config, parser).screen()
            assertTrue(screen.node("auth:status").summary.contains("无法确认"))
            assertNotNull(screen.node("auth:browser").actionUrl)
        }
    }

    @Test
    fun `a settings screen cannot execute a retired plugin after an update`() = runBlocking {
        val screen = runtime.getPreferences(SOURCE)
        registry.replace(Fixtures.kototoro, "kototoro-parsers") {}
        assertThrows<SourceInvalidArgumentException> {
            runBlocking {
                runtime.updatePreference(SOURCE, screen.revision, "auth:username", SourcePreferenceValue.Text("reader"))
            }
        }
    }

    companion object {
        private const val SOURCE = "FIXTURE_KOTOTORO_ONLY"
        private const val PASSWORD = " fixture password "
    }
}
