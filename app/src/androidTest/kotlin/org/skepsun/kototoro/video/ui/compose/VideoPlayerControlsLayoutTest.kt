package org.skepsun.kototoro.video.ui.compose

import android.graphics.Rect
import android.graphics.Bitmap
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.dev.IdleProbeActivity

/** Uses real layout/accessibility bounds, including clipping by scroll containers. */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class VideoPlayerControlsLayoutTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun narrowToolbarKeepsChapterNavigationVisible() = verifyControls(widthDp = 320, fontScale = 1f)

    @Test
    fun largeFontKeepsCoreActionsVisibleAndClickable() = verifyControls(widthDp = 360, fontScale = 1.5f)

    @Test
    fun wideToolbarKeepsCoreActionsVisibleAndClickable() = verifyControls(widthDp = 840, fontScale = 1f)

    private fun verifyControls(widthDp: Int, fontScale: Float) {
        hiltRule.inject()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val actions = CopyOnWriteArrayList<VideoPlayerAction>()
        // Shell launch also works on devices that block startActivitySync from background instrumentation.
        val command = "am start -W --windowingMode 1 -n " +
            "${context.packageName}/${IdleProbeActivity::class.java.name} --ez scene_recovery true"
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes()
        }
        var activity: IdleProbeActivity? = null
        val launchDeadline = SystemClock.uptimeMillis() + 5_000
        while (activity == null && SystemClock.uptimeMillis() < launchDeadline) {
            instrumentation.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<IdleProbeActivity>().firstOrNull()
            }
            if (activity == null) SystemClock.sleep(100)
        }
        val fixture = requireNotNull(activity) { "Toolbar fixture did not resume" }
        try {
            instrumentation.runOnMainSync {
                val activity = fixture
                val metrics = activity.resources.displayMetrics
                val density = minOf(metrics.density, metrics.widthPixels.toFloat() / widthDp)
                activity.setContent {
                    MaterialTheme {
                        CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                            Box(Modifier.width(widthDp.dp).background(Color.Black)) {
                                VideoPlayerBottomControls(
                                    state = VideoPlayerControlState(
                                        hasPreviousChapter = true,
                                        hasNextChapter = true,
                                        qualityLabel = "1080p",
                                        chapterGroupLabel = "A very long chapter group label",
                                    ),
                                    onAction = actions::add,
                                )
                            }
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            val expected = listOf(
                "Previous chapter" to VideoPlayerAction.PreviousChapter,
                "Next chapter" to VideoPlayerAction.NextChapter,
                "Lock controls" to VideoPlayerAction.ToggleScreenLock,
                context.getString(R.string.rotate_screen) to VideoPlayerAction.ToggleFullscreen,
            )
            expected.forEach { (description, action) ->
                val deadline = SystemClock.uptimeMillis() + 5_000
                var node: AccessibilityNodeInfo? = null
                while (node == null && SystemClock.uptimeMillis() < deadline) {
                    node = findNode(instrumentation.uiAutomation.rootInActiveWindow, description)
                    if (node == null) SystemClock.sleep(100)
                }
                val bounds = Rect()
                node?.getBoundsInScreen(bounds)
                assertTrue("$description is clipped at ${widthDp}dp / font $fontScale: $bounds", bounds.width() >= 20)
                assertTrue("$description must accept clicks", node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                assertTrue("$description must invoke its original action", actions.contains(action))
            }
            listOf("Chapters", "1.00x").forEach { description ->
                var node = findNode(instrumentation.uiAutomation.rootInActiveWindow, description)
                while (node != null && !node.isClickable) node = node.parent
                assertTrue("$description must be accessible", node != null)
                assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                assertTrue("$description must dispatch its action", actions.any {
                    when (description) {
                        "Chapters" -> it is VideoPlayerAction.OpenChapterSelection
                        else -> it is VideoPlayerAction.OpenPlaybackSpeed
                    }
                })
            }
            assertNull(
                "The bottom toolbar must not duplicate the top options menu",
                findNode(instrumentation.uiAutomation.rootInActiveWindow, context.getString(R.string.options)),
            )
            // Wait for the system launch transition before capturing the composed toolbar.
            SystemClock.sleep(600)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                try {
                    File(context.cacheDir, "video-toolbar-$widthDp-$fontScale.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            instrumentation.runOnMainSync { fixture.finish() }
        }
    }

    private fun findNode(node: AccessibilityNodeInfo?, description: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val label = node.contentDescription?.toString()
        if (label == description || node.text?.toString() == description ||
            description == "Chapters" && label?.startsWith("Chapters,") == true
        ) return node
        for (index in 0 until node.childCount) {
            findNode(node.getChild(index), description)?.let { return it }
        }
        return null
    }
}
