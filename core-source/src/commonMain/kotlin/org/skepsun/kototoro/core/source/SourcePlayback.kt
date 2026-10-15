package org.skepsun.kototoro.core.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The named episode group, distinct from the playable streams returned for an individual episode. */
val SourceChapter.playbackLine: String?
    get() = sourceData?.let { data ->
        runCatching { (Json.parseToJsonElement(data) as? JsonObject)?.get("line") as? JsonPrimitive }
            .getOrNull()?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
    } ?: branch?.takeIf(String::isNotBlank)
