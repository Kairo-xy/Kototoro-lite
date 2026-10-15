package org.skepsun.kototoro.settings.sources.groups

import android.content.pm.PackageInfo
import org.skepsun.kototoro.extensions.runtime.tachiyomi.TachiyomiApkClassification
import org.skepsun.kototoro.extensions.runtime.tachiyomi.TachiyomiApkClassifier
import org.skepsun.kototoro.extensions.runtime.tachiyomi.TachiyomiApkEcosystemSpecs

internal fun classifySourceGroupApk(info: PackageInfo): String? {
    val features = info.reqFeatures?.mapNotNull { it.name }.orEmpty()
    if ("tachiyomi.animeextension" in features) return "aniyomi"
    if ("ireader" in features) return "ireader"
    return TachiyomiApkEcosystemSpecs.all.asReversed().firstOrNull { spec ->
        TachiyomiApkClassifier.classify(info, spec) == TachiyomiApkClassification.Extension
    }?.ecosystemDir
}
