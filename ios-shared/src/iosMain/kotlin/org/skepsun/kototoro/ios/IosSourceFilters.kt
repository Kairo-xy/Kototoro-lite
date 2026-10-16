package org.skepsun.kototoro.ios

import org.skepsun.kototoro.core.source.SourceDescriptor
import org.skepsun.kototoro.core.source.SourceDynamicFilters
import org.skepsun.kototoro.core.source.SourceFilter
import org.skepsun.kototoro.core.source.SourceFilterChange
import org.skepsun.kototoro.core.source.SourceFilterKind
import org.skepsun.kototoro.core.source.SourceFilterNode
import org.skepsun.kototoro.core.source.SourceFilterOptions
import org.skepsun.kototoro.core.source.SourceFilterValue
import org.skepsun.kototoro.core.source.SourceTag
import org.skepsun.kototoro.core.source.SourceTriState

class IosFilterGroup internal constructor(val title: String, val tags: List<IosSourceTag>, val isExclusive: Boolean)

/**
 * A control value in Swift-friendly form; the owning control's kind decides which field is meaningful.
 * [tri] is 0 ignore / 1 include / 2 exclude, and a sort [index] below zero clears the selection.
 */
class IosFilterValue(val toggle: Boolean, val tri: Int, val index: Int, val ascending: Boolean, val text: String) {
    override fun equals(other: Any?) = other is IosFilterValue && toggle == other.toggle && tri == other.tri &&
        index == other.index && ascending == other.ascending && text == other.text
    override fun hashCode() = listOf(toggle, tri, index, ascending, text).hashCode()

    internal companion object {
        fun of(value: SourceFilterValue?): IosFilterValue = when (value) {
            is SourceFilterValue.Toggle -> IosFilterValue(value.value, 0, 0, false, "")
            is SourceFilterValue.TriState -> IosFilterValue(false, value.value.ordinal, 0, false, "")
            is SourceFilterValue.Choice -> IosFilterValue(false, 0, value.index, false, "")
            is SourceFilterValue.Sort -> IosFilterValue(false, 0, value.index, value.ascending, "")
            is SourceFilterValue.Text -> IosFilterValue(false, 0, 0, false, value.value)
            null -> IosFilterValue(false, 0, -1, false, "")
        }
    }
}

/** One native control of a Mihon/Aniyomi FilterList; values keep the extension's own indexes. */
class IosFilterControl internal constructor(node: SourceFilterNode, parent: String?) {
    val id: String = node.id
    val name: String = node.name
    /** header, separator, checkbox, tristate, select, sort, text, group or unsupported. */
    val kind: String = node.kind.name.lowercase()
    val values: List<String> = node.values
    val initial: IosFilterValue = IosFilterValue.of(node.state)
    /** The quick-category key of a checkbox/tri-state, matching the source's tag projection. */
    val tagKey: String? = if (node.kind == SourceFilterKind.CHECKBOX || node.kind == SourceFilterKind.TRISTATE) {
        "${parent?.let { "$it/" } ?: "top:"}${node.name}"
    } else null
    val children: List<IosFilterControl> = node.children.map {
        IosFilterControl(it, if (parent == null) node.name else "$parent/${node.name}")
    }
    internal val nodeKind: SourceFilterKind = node.kind

    internal fun flatten(): List<IosFilterControl> = listOf(this) + children.flatMap { it.flatten() }

    /** A saved draft can outlive source settings that change the control's available choices. */
    fun accepts(value: IosFilterValue): Boolean = change(value) != null

    internal fun change(value: IosFilterValue): SourceFilterChange? = SourceFilterChange(id, when (nodeKind) {
        SourceFilterKind.CHECKBOX -> SourceFilterValue.Toggle(value.toggle)
        SourceFilterKind.TRISTATE -> SourceFilterValue.TriState(SourceTriState.entries.getOrNull(value.tri) ?: return null)
        SourceFilterKind.SELECT -> SourceFilterValue.Choice(value.index.takeIf { it in values.indices } ?: return null)
        SourceFilterKind.SORT -> if (value.index < 0) null else
            SourceFilterValue.Sort(value.index.takeIf { it in values.indices } ?: return null, value.ascending)
        SourceFilterKind.TEXT -> SourceFilterValue.Text(value.text)
        else -> return null
    })
}

/** What a source lets the browse screen filter by; built from its descriptor and filter options. */
class IosFilterPanel internal constructor(
    descriptor: SourceDescriptor,
    internal val options: SourceFilterOptions,
    /** The order the listing really uses without a selection (Apple CMS browses newest first). */
    listingOrder: String = descriptor.defaultSortOrder,
    hiddenOrders: Set<String> = emptySet(),
    dynamic: SourceDynamicFilters? = null,
) {
    val sortOrders: List<String> = descriptor.sortOrders.filterNot { it in hiddenOrders }
    val defaultSortOrder: String = listingOrder
    val multipleTags: Boolean = descriptor.filterCapabilities.isMultipleTagsSupported
    val excludeTags: Boolean = descriptor.filterCapabilities.isTagsExclusionSupported
    val searchWithFilters: Boolean = descriptor.filterCapabilities.isSearchWithFiltersSupported
    val year: Boolean = descriptor.filterCapabilities.isYearSupported
    val yearRange: Boolean = descriptor.filterCapabilities.isYearRangeSupported
    val originalLocale: Boolean = descriptor.filterCapabilities.isOriginalLocaleSupported
    val author: Boolean = descriptor.filterCapabilities.isAuthorSearchSupported
    /** The extension's own control tree; when present it replaces the projected tag groups in the sheet. */
    val controls: List<IosFilterControl> = dynamic?.nodes.orEmpty().map { IosFilterControl(it, null) }
    val isDynamic: Boolean get() = controls.isNotEmpty()
    val groups: List<IosFilterGroup> = options.effectiveTagGroups.map { group ->
        IosFilterGroup(group.title, group.tags.map(::IosSourceTag), group.isExclusive)
    }
    private val controlsById = controls.flatMap { it.flatten() }.associateBy { it.id }
    /** Tags for the browse screen's quick bar: real genres only, not a dynamic source's select/sort/text values. */
    val quickTags: List<IosSourceTag> = run {
        val keys = controlsById.values.mapNotNull { it.tagKey }.toSet()
        groups.flatMap { it.tags }.filter { !isDynamic || it.key in keys }.distinctBy { it.key }
    }
    val states: List<String> = options.availableStates.toList()
    val contentRatings: List<String> = options.availableContentRating.toList()
    val types: List<String> = options.availableContentTypes.toList()
    val demographics: List<String> = options.availableDemographics.toList()
    val locales: List<String> = options.availableLocales.sorted()
    val isEmpty: Boolean get() = sortOrders.size <= 1 && groups.isEmpty() && states.isEmpty() && contentRatings.isEmpty() &&
        types.isEmpty() && demographics.isEmpty() && locales.isEmpty() && !year && !yearRange && !author && !isDynamic

    /** The control a quick-bar tag stands for, so the bar and the sheet edit the same native state. */
    fun control(tagKey: String): IosFilterControl? = controlsById.values.firstOrNull { it.tagKey == tagKey }

    fun controlById(id: String): IosFilterControl? = controlsById[id]

    internal fun tag(key: String): SourceTag? = options.effectiveTagGroups.firstNotNullOfOrNull { group ->
        group.tags.firstOrNull { it.key == key }
    } ?: options.availableTags.firstOrNull { it.key == key }
}

class IosFilterChange(val id: String, val value: IosFilterValue)

/** Swift-side selection by stable keys; Kotlin resolves them against the source's own tag objects. */
class IosFilterSelection(
    val order: String?,
    val tagKeys: List<String>,
    val excludedTagKeys: List<String>,
    val states: List<String>,
    val contentRatings: List<String>,
    val types: List<String>,
    val demographics: List<String>,
    val locale: String?,
    val originalLocale: String?,
    val year: Int,
    val yearFrom: Int,
    val yearTo: Int,
    val author: String?,
    /** Only controls the user moved away from the extension's defaults. */
    val dynamic: List<IosFilterChange>,
) {
    internal val needsPanel: Boolean
        get() = tagKeys.isNotEmpty() || excludedTagKeys.isNotEmpty() || dynamic.isNotEmpty()

    internal fun toFilter(panel: IosFilterPanel?, query: String?): SourceFilter? {
        val tags = tagKeys.mapNotNull { panel?.tag(it) }.toSet()
        val excluded = excludedTagKeys.mapNotNull { panel?.tag(it) }.toSet()
        val changes = dynamic.mapNotNull { panel?.controlById(it.id)?.change(it.value) }
        val filter = SourceFilter(query = query, tags = tags, tagsExclude = excluded, locale = locale,
            originalLocale = originalLocale, states = states.toSet(), contentRating = contentRatings.toSet(),
            types = types.toSet(), demographics = demographics.toSet(), year = year, yearFrom = yearFrom,
            yearTo = yearTo, author = author?.trim()?.takeIf(String::isNotEmpty), dynamicFilters = changes)
        return filter.takeUnless { it == SourceFilter() }
    }
}
