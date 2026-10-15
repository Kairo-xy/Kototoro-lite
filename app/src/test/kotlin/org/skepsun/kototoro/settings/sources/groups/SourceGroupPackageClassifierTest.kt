package org.skepsun.kototoro.settings.sources.groups

import android.content.pm.ApplicationInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SourceGroupPackageClassifierTest {
    @Test
    fun `normal novel APK is included as Tsundoku`() {
        val info = PackageInfo().apply {
            packageName = "eu.kanade.tachiyomi.extension.en.novel"
            reqFeatures = arrayOf(FeatureInfo().apply { name = "tachiyomi.novelextension" })
        }
        assertEquals("tsundoku", classifySourceGroupApk(info))
    }

    @Test
    fun `Mihon manifest metadata fallback is preserved`() {
        val metadata = mockk<Bundle> {
            every { containsKey("tachiyomi.extension.class") } returns true
            every { containsKey("tachiyomi.extension.factory") } returns false
        }
        val info = PackageInfo().apply {
            packageName = "eu.kanade.tachiyomi.extension.en.example"
            applicationInfo = ApplicationInfo().apply { metaData = metadata }
            reqFeatures = emptyArray()
        }
        assertEquals("mihon", classifySourceGroupApk(info))
    }
}
