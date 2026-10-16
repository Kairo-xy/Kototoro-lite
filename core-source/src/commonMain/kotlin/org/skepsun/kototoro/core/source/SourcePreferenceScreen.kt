package org.skepsun.kototoro.core.source

import kotlinx.serialization.Serializable

@Serializable
enum class SourcePreferenceKind { INFO, TEXT, TOGGLE, CHOICE, MULTI_CHOICE, GROUP, ACTION, UNSUPPORTED }

@Serializable
data class SourcePreferenceChoice(val title: String, val value: String)

@Serializable
data class SourcePreferenceNode(
    val id: String,
    val key: String?,
    val title: String,
    val summary: String,
    val kind: SourcePreferenceKind,
    val enabled: Boolean,
    val visible: Boolean,
    val value: SourcePreferenceValue? = null,
    val defaultValue: SourcePreferenceValue? = null,
    val choices: List<SourcePreferenceChoice> = emptyList(),
    val children: List<SourcePreferenceNode> = emptyList(),
    val isSecret: Boolean = false,
    /** ACTION nodes may open the source's login page without executing an update. */
    val actionUrl: String? = null,
    /** Domain choices are suggestions; the source also accepts a custom mirror. */
    val allowsCustomValue: Boolean = false,
)

/** Revision identifies live native control objects, not merely a hash of their displayed labels. */
@Serializable
data class SourcePreferenceScreen(val source: SourceRef, val revision: String, val nodes: List<SourcePreferenceNode>)

@Serializable
enum class SourcePreferenceUpdateStatus { ACCEPTED, REJECTED, PERSISTENCE_FAILED }

@Serializable
data class SourcePreferenceUpdate(val status: SourcePreferenceUpdateStatus, val screen: SourcePreferenceScreen)
