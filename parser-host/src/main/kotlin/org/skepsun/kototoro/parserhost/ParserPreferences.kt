package org.skepsun.kototoro.parserhost

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.skepsun.kototoro.core.source.SourceInvalidArgumentException
import org.skepsun.kototoro.core.source.SourcePreferenceChoice
import org.skepsun.kototoro.core.source.SourcePreferenceKind
import org.skepsun.kototoro.core.source.SourcePreferenceNode
import org.skepsun.kototoro.core.source.SourcePreferenceScreen
import org.skepsun.kototoro.core.source.SourcePreferenceUpdate
import org.skepsun.kototoro.core.source.SourcePreferenceUpdateStatus
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.ContentParserAuthProvider
import org.skepsun.kototoro.parsers.ContentParserCredentialsAuthProvider
import org.skepsun.kototoro.parsers.config.ConfigKey
import java.util.UUID

/**
 * Settings screen of one parser source, derived from the keys it declares in `onCreateConfig`,
 * plus authentication options exposed by [ContentParser.authorizationProvider], including wrapped parsers.
 * A revision guards against a stale screen writing over a newer one.
 */
internal class ParserPreferences(
    private val source: SourceRef,
    keys: List<ConfigKey<*>>,
    private val config: ParserSourceConfig,
    private val parser: ContentParser? = null,
) {
    private val keys = keys.distinctBy { it.key }.associateBy { nodeId(it) }
    private var revision = UUID.randomUUID().toString()
    private var lastErrorMessage: String? = null
    private val authProvider: ContentParserAuthProvider? get() = parser?.authorizationProvider
    private val credentialsProvider: ContentParserCredentialsAuthProvider?
        get() = authProvider as? ContentParserCredentialsAuthProvider

    fun owns(parser: ContentParser): Boolean = this.parser === parser

    suspend fun screen(): SourcePreferenceScreen {
        val configNodes = keys.map { (id, key) -> node(id, key) }
        val authGroup = buildAuthGroup()
        val allNodes = if (authGroup != null) listOf(authGroup) + configNodes else configNodes
        return SourcePreferenceScreen(source, revision, allNodes)
    }

    suspend fun update(expectedRevision: String, nodeId: String, value: SourcePreferenceValue): SourcePreferenceUpdate {
        if (expectedRevision != revision) throw SourceInvalidArgumentException()

        when (nodeId) {
            KEY_AUTH_USERNAME -> {
                if (credentialsProvider == null) throw SourceInvalidArgumentException()
                val text = (value as? SourcePreferenceValue.Text)?.value ?: throw SourceInvalidArgumentException()
                return saveCredential(STORE_USERNAME, text.trim())
            }
            KEY_AUTH_PASSWORD -> {
                if (credentialsProvider == null) throw SourceInvalidArgumentException()
                val text = (value as? SourcePreferenceValue.Text)?.value ?: throw SourceInvalidArgumentException()
                return saveCredential(STORE_PASSWORD, text)
            }
            KEY_AUTH_LOGIN -> {
                val credentialsProvider = credentialsProvider ?: throw SourceInvalidArgumentException()
                if (value !is SourcePreferenceValue.Action) throw SourceInvalidArgumentException()
                val user = config.getString(STORE_USERNAME, "").trim()
                val pass = config.getString(STORE_PASSWORD, "")
                if (user.isEmpty() || pass.isEmpty()) {
                    lastErrorMessage = "用户名或密码不能为空"
                    revision = UUID.randomUUID().toString()
                    return SourcePreferenceUpdate(SourcePreferenceUpdateStatus.REJECTED, screen())
                }
                lastErrorMessage = null
                revision = UUID.randomUUID().toString()
                val loginSuccess = try {
                    credentialsProvider.login(user, pass)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Extension exceptions can contain submitted credentials or response tokens.
                    lastErrorMessage = "登录异常，请检查网络或使用网页登录"
                    false
                }
                return if (loginSuccess) {
                    lastErrorMessage = null
                    SourcePreferenceUpdate(SourcePreferenceUpdateStatus.ACCEPTED, screen())
                } else {
                    if (lastErrorMessage == null) lastErrorMessage = "登录失败，请检查账号密码"
                    SourcePreferenceUpdate(SourcePreferenceUpdateStatus.REJECTED, screen())
                }
            }
            KEY_AUTH_CLEAR_PASSWORD -> {
                if (credentialsProvider == null || value !is SourcePreferenceValue.Action) {
                    throw SourceInvalidArgumentException()
                }
                return saveCredential(STORE_PASSWORD, null)
            }
        }

        val key = keys[nodeId] ?: throw SourceInvalidArgumentException()
        val node = node(nodeId, key)
        val accepted = when (node.kind) {
            SourcePreferenceKind.TEXT -> (value as? SourcePreferenceValue.Text)?.value?.takeIf {
                key !is ConfigKey.Domain || ParserSourceConfig.isValidDomain(it.trim())
            }?.let { SourcePreferenceValue.Text(if (key is ConfigKey.Domain) it.trim() else it) }
            SourcePreferenceKind.TOGGLE -> value as? SourcePreferenceValue.Toggle
            // Domain presets are suggestions: any valid host (a private mirror) may be chosen as well.
            SourcePreferenceKind.CHOICE -> (value as? SourcePreferenceValue.Text)?.takeIf { selected ->
                node.choices.any { it.value == selected.value } ||
                    key is ConfigKey.Domain && ParserSourceConfig.isValidDomain(selected.value.trim())
            }?.let { if (key is ConfigKey.Domain) SourcePreferenceValue.Text(it.value.trim()) else it }
            else -> throw SourceInvalidArgumentException()
        }
        // The stored value changes the screen (and may change derived defaults); stale screens must re-read it.
        revision = UUID.randomUUID().toString()
        val status = when {
            accepted == null -> SourcePreferenceUpdateStatus.REJECTED
            config.put(key, accepted) -> SourcePreferenceUpdateStatus.ACCEPTED
            else -> SourcePreferenceUpdateStatus.PERSISTENCE_FAILED
        }
        return SourcePreferenceUpdate(status, screen())
    }

    private suspend fun saveCredential(key: String, value: String?): SourcePreferenceUpdate {
        lastErrorMessage = null
        revision = UUID.randomUUID().toString()
        val saved = if (value == null) config.remove(key) else config.putString(key, value)
        val status = if (saved) SourcePreferenceUpdateStatus.ACCEPTED else SourcePreferenceUpdateStatus.PERSISTENCE_FAILED
        return SourcePreferenceUpdate(status, screen())
    }

    private suspend fun <T> authResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun buildAuthGroup(): SourcePreferenceNode? {
        val authProvider = authProvider ?: return null
        val credentialsProvider = credentialsProvider

        val authorized = authResult { authProvider.isAuthorized() }
        val isAuth = authorized.getOrDefault(false)
        val username = if (isAuth) authResult { authProvider.getUsername() }.getOrNull() else null
        val statusSummary = when {
            isAuth && !username.isNullOrBlank() -> "已登录为: $username"
            isAuth -> "已登录"
            authorized.isFailure -> "暂时无法确认登录状态，请重试"
            else -> "未登录"
        }

        val children = mutableListOf<SourcePreferenceNode>()
        children += SourcePreferenceNode(
            id = KEY_AUTH_STATUS,
            key = null,
            title = "账号状态",
            summary = statusSummary,
            kind = SourcePreferenceKind.INFO,
            enabled = true,
            visible = true,
        )
        lastErrorMessage?.let { message ->
            children += SourcePreferenceNode(
                id = KEY_AUTH_ERROR,
                key = null,
                title = "登录结果",
                summary = message,
                kind = SourcePreferenceKind.INFO,
                enabled = true,
                visible = true,
            )
        }

        if (credentialsProvider != null) {
            val savedUser = config.getString(STORE_USERNAME, "")
            val savedPass = config.getString(STORE_PASSWORD, "")
            children += SourcePreferenceNode(
                id = KEY_AUTH_USERNAME,
                key = STORE_USERNAME,
                title = "用户名 / 邮箱",
                summary = savedUser.ifEmpty { "点击设置用户名或邮箱" },
                kind = SourcePreferenceKind.TEXT,
                enabled = true,
                visible = true,
                value = SourcePreferenceValue.Text(savedUser),
                defaultValue = SourcePreferenceValue.Text(""),
            )
            children += SourcePreferenceNode(
                id = KEY_AUTH_PASSWORD,
                key = STORE_PASSWORD,
                title = "密码",
                summary = if (savedPass.isEmpty()) "点击设置密码" else "已设置密码",
                kind = SourcePreferenceKind.TEXT,
                enabled = true,
                visible = true,
                value = SourcePreferenceValue.Text(savedPass),
                defaultValue = SourcePreferenceValue.Text(""),
                isSecret = true,
            )
            children += SourcePreferenceNode(
                id = KEY_AUTH_LOGIN,
                key = null,
                title = if (isAuth) "重新登录" else "登录",
                summary = if (isAuth) "点击使用当前保存的账号密码重新验证" else "点击使用上方账号密码登录",
                kind = SourcePreferenceKind.ACTION,
                enabled = savedUser.isNotBlank() && savedPass.isNotEmpty(),
                visible = true,
            )
            if (savedPass.isNotEmpty()) {
                children += SourcePreferenceNode(
                    id = KEY_AUTH_CLEAR_PASSWORD,
                    key = null,
                    title = "清除已保存的密码",
                    summary = "站点登录会话仍然保留",
                    kind = SourcePreferenceKind.ACTION,
                    enabled = true,
                    visible = true,
                )
            }
        }

        val authUrl = authResult { authProvider.authUrl }.getOrNull()?.toHttpUrlOrNull()
        if (authUrl != null && authUrl.username.isEmpty() && authUrl.password.isEmpty()) {
            children += SourcePreferenceNode(
                id = KEY_AUTH_BROWSER,
                key = null,
                title = "网页登录",
                summary = "在内置浏览器完成登录后，点完成以更新登录状态",
                kind = SourcePreferenceKind.ACTION,
                enabled = true,
                visible = true,
                actionUrl = authUrl.toString(),
            )
        }

        return SourcePreferenceNode(
            id = KEY_AUTH_GROUP,
            key = null,
            title = "账号与登录",
            summary = "",
            kind = SourcePreferenceKind.GROUP,
            enabled = true,
            visible = true,
            children = children,
        )
    }

    private fun node(id: String, key: ConfigKey<*>): SourcePreferenceNode {
        val current = config[key]
        fun text(value: String?) = SourcePreferenceValue.Text(value.orEmpty())
        fun node(title: String, kind: SourcePreferenceKind, value: SourcePreferenceValue?, default: SourcePreferenceValue?,
            choices: List<SourcePreferenceChoice> = emptyList(), summary: String = "") =
            SourcePreferenceNode(id, key.key, title, summary, kind, enabled = true, visible = true, value = value,
                defaultValue = default, choices = choices, allowsCustomValue = key is ConfigKey.Domain)
        fun toggle(title: String, default: Boolean) = node(title, SourcePreferenceKind.TOGGLE,
            SourcePreferenceValue.Toggle(current as Boolean), SourcePreferenceValue.Toggle(default))
        return when (key) {
            is ConfigKey.Domain -> if (key.presetValues.size > 1) {
                node("域名", SourcePreferenceKind.CHOICE, text(current as String), text(key.defaultValue),
                    key.presetValues.map { SourcePreferenceChoice(it, it) })
            } else node("域名", SourcePreferenceKind.TEXT, text(current as String), text(key.defaultValue))
            is ConfigKey.UserAgent -> node("User-Agent", SourcePreferenceKind.TEXT, text(current as String), text(key.defaultValue))
            is ConfigKey.Text -> node(key.title, SourcePreferenceKind.TEXT, text(current as String), text(key.defaultValue))
            is ConfigKey.ShowSuspiciousContent -> toggle("显示可疑内容", key.defaultValue)
            is ConfigKey.SplitByTranslations -> toggle("按译者拆分章节", key.defaultValue)
            is ConfigKey.InterceptCloudflare -> toggle("拦截 Cloudflare 验证", key.defaultValue)
            is ConfigKey.Toggle -> toggle(key.title, key.defaultValue)
            is ConfigKey.PreferredImageServer -> node("首选图片服务器", SourcePreferenceKind.CHOICE, text(current as String?),
                text(key.defaultValue),
                key.presetValues.map { (value, label) -> SourcePreferenceChoice(label ?: value ?: "自动", value.orEmpty()) })
            is ConfigKey.PreferredLanguage -> node(key.title, SourcePreferenceKind.CHOICE, text(current as String),
                text(key.defaultValue), key.presetValues.map { (value, label) -> SourcePreferenceChoice(label, value) })
        }
    }

    companion object {
        const val KEY_AUTH_STATUS = "auth:status"
        const val KEY_AUTH_ERROR = "auth:error"
        const val KEY_AUTH_USERNAME = "auth:username"
        const val KEY_AUTH_PASSWORD = "auth:password"
        const val KEY_AUTH_LOGIN = "auth:login"
        const val KEY_AUTH_CLEAR_PASSWORD = "auth:clear_password"
        const val KEY_AUTH_BROWSER = "auth:browser"
        const val KEY_AUTH_GROUP = "auth:group"
        const val STORE_USERNAME = "auth_username"
        const val STORE_PASSWORD = "auth_password"

        fun nodeId(key: ConfigKey<*>) = "config:${key.key}"
    }
}
