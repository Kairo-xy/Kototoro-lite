package org.skepsun.kototoro.ios

import org.skepsun.kototoro.core.source.SourcePreferenceKind
import org.skepsun.kototoro.core.source.SourcePreferenceNode
import org.skepsun.kototoro.core.source.SourcePreferenceScreen
import org.skepsun.kototoro.core.source.SourcePreferenceUpdateStatus
import org.skepsun.kototoro.core.source.SourcePreferenceValue

/**
 * A source's own settings screen (extension preferences, parser config keys, the host's User-Agent row) as Swift
 * sees it. Updates go back with the screen's [revision], so a stale screen is rejected instead of writing over a
 * newer one.
 */
class IosPreferenceScreen internal constructor(internal val value: SourcePreferenceScreen) {
    val sourceName: String get() = value.source.name
    val revision: String get() = value.revision
    val nodes: List<IosPreferenceNode> get() = value.nodes.filter { it.visible }.map(::IosPreferenceNode)
}

class IosPreferenceNode internal constructor(internal val value: SourcePreferenceNode) {
    val id: String get() = value.id
    val key: String? get() = value.key
    val title: String get() = value.title
    val summary: String get() = value.summary
    /** INFO, TEXT, TOGGLE, CHOICE, MULTI_CHOICE, GROUP, ACTION or UNSUPPORTED. */
    val kind: String get() = value.kind.name
    val enabled: Boolean get() = value.enabled
    val isSecret: Boolean get() = value.isSecret
    val text: String get() = when (val current = value.value) {
        is SourcePreferenceValue.Text -> current.value
        is SourcePreferenceValue.Integer -> current.value.toString()
        is SourcePreferenceValue.LongInteger -> current.value.toString()
        else -> ""
    }
    val defaultText: String get() = (value.defaultValue as? SourcePreferenceValue.Text)?.value.orEmpty()
    val isOn: Boolean get() = (value.value as? SourcePreferenceValue.Toggle)?.value ?: false
    val selected: Set<String> get() = when (val current = value.value) {
        is SourcePreferenceValue.TextSet -> current.values
        is SourcePreferenceValue.Text -> setOf(current.value)
        is SourcePreferenceValue.Integer -> setOf(current.value.toString())
        else -> emptySet()
    }
    val choiceTitles: List<String> get() = value.choices.map { it.title }
    val choiceValues: List<String> get() = value.choices.map { it.value }
    val children: List<IosPreferenceNode> get() = value.children.filter { it.visible }.map(::IosPreferenceNode)

    /** The source's User-Agent row, which the settings page offers common presets for. */
    val isUserAgent: Boolean get() = value.kind == SourcePreferenceKind.TEXT &&
        (value.id == "host:user_agent" || value.key == "user_agent" || value.title.equals("User-Agent", ignoreCase = true))

    /** A choice keeps the value type the source declared (a few extensions store integers). */
    internal fun choiceValue(choice: String): SourcePreferenceValue =
        if (value.value is SourcePreferenceValue.Integer) choice.toIntOrNull()?.let(SourcePreferenceValue::Integer)
            ?: SourcePreferenceValue.Text(choice) else SourcePreferenceValue.Text(choice)
}

class IosPreferenceResult internal constructor(
    /** ACCEPTED, REJECTED or PERSISTENCE_FAILED. */
    val status: String,
    val screen: IosPreferenceScreen,
)

internal fun SourcePreferenceUpdateStatus.label() = name
