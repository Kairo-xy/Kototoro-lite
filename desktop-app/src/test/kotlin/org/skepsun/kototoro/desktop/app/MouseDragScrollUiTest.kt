package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.adaptive.TabletLayoutClass
import org.skepsun.kototoro.core.ui.feed.UpdatedContentCarousel
import org.skepsun.kototoro.core.ui.gestures.mouseDragScroll
import org.skepsun.kototoro.core.ui.home.HomeHeroCardSurface
import org.skepsun.kototoro.core.ui.home.HomeHeroPager
import org.skepsun.kototoro.core.ui.home.HomeScreenLayout
import org.skepsun.kototoro.core.ui.topbar.TopBarFilterRail
import org.skepsun.kototoro.core.ui.topbar.TopBarTabItem
import org.skepsun.kototoro.core.ui.topbar.TopBarTabsRail
import kotlin.math.abs

class MouseDragScrollUiTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `nested home scrolling separates axes and preserves clicks right buttons wheel and touch`() {
        runDesktopComposeUiTest(width = 520, height = 600) {
            lateinit var row: LazyListState
            lateinit var page: LazyListState
            var clicked = 0
            setContent {
                DesktopTheme {
                    row = rememberLazyListState()
                    page = rememberLazyListState()
                    HomeScreenLayout(false, true, true, true, hero = { _, _ -> },
                        listState = page, contentPadding = PaddingValues(top = 150.dp),
                        quickActions = {}, highlights = {
                            Column(Modifier.fillMaxWidth().height(1300.dp)) {
                                LazyRow(state = row, modifier = Modifier.fillMaxWidth()
                                    .mouseDragScroll(row, Orientation.Horizontal).testTag("rail")) {
                                    items((0..30).toList()) { item ->
                                        Box(Modifier.size(100.dp).background(Color.Blue).clickable { clicked++ }
                                            .testTag("card:$item"))
                                    }
                                }
                            }
                        })
                }
            }
            onNodeWithTag("rail").performMouseInput {
                moveTo(center); press()
                repeat(8) { advanceEventTime(16); moveBy(Offset(-25f, 1f)) }
                release()
            }
            waitForIdle()
            assertTrue(row.firstVisibleItemIndex > 0 || row.firstVisibleItemScrollOffset > 0)
            assertEquals(0, page.firstVisibleItemScrollOffset, "Horizontal dragging must not move the home page")
            assertEquals(0, clicked)
            val rowBefore = row.firstVisibleItemIndex to row.firstVisibleItemScrollOffset
            onNodeWithTag("rail").performMouseInput {
                moveTo(center); press()
                repeat(6) { advanceEventTime(16); moveBy(Offset(1f, -25f)) }
                release()
            }
            waitForIdle()
            assertTrue(page.firstVisibleItemIndex > 0 || page.firstVisibleItemScrollOffset > 0)
            assertEquals(rowBefore, row.firstVisibleItemIndex to row.firstVisibleItemScrollOffset,
                "A vertical gesture over a cover belongs to the parent")
            runOnIdle { runBlocking { page.scrollToItem(0); row.scrollToItem(0) } }
            onNodeWithTag("card:0").performMouseInput { moveTo(center); press(); moveBy(Offset(2f, 1f)); release() }
            assertEquals(1, clicked, "Motion below slop is still a click")
            onNodeWithTag("rail").performMouseInput {
                moveTo(center); press(MouseButton.Secondary); moveBy(Offset(-100f, 0f)); release(MouseButton.Secondary)
            }
            assertEquals(0, row.firstVisibleItemIndex)
            assertEquals(0, row.firstVisibleItemScrollOffset)
            assertEquals(1, clicked)
            onNodeWithTag("rail").performMouseInput { exit() }
            onNodeWithTag("rail").performTouchInput { swipeLeft() }
            waitForIdle()
            assertTrue(row.firstVisibleItemIndex > 0 || row.firstVisibleItemScrollOffset > 0,
                "Native touch scrolling still works")
            onNodeWithTag("rail").performMouseInput { moveTo(center); scroll(3f) }
            waitForIdle()
            assertTrue(page.firstVisibleItemIndex > 0 || page.firstVisibleItemScrollOffset > 0,
                "Native vertical wheel scrolling over a rail must still reach the page")
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `mouse scrolling respects RTL and reverse layouts and consumes boundary drags`() {
        for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            for (reversed in listOf(false, true)) runDesktopComposeUiTest(width = 400, height = 160) {
                lateinit var list: LazyListState
                var clicks = 0
                setContent {
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        list = rememberLazyListState()
                        LazyRow(state = list, reverseLayout = reversed,
                            modifier = Modifier.mouseDragScroll(list, Orientation.Horizontal, reverseScrolling = reversed)
                                .testTag("rail")) {
                            items((0..30).toList()) {
                                Box(Modifier.size(100.dp).clickable { clicks++ })
                            }
                        }
                    }
                }
                val forward = if ((direction == LayoutDirection.Rtl) xor reversed) 25f else -25f
                onNodeWithTag("rail").performMouseInput {
                    moveTo(center); press()
                    repeat(8) { advanceEventTime(16); moveBy(Offset(forward, 0f)) }
                    release()
                }
                waitForIdle()
                assertTrue(list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0)
                assertEquals(0, clicks)
                runOnIdle { runBlocking { list.scrollToItem(0) } }
                onNodeWithTag("rail").performMouseInput {
                    moveTo(center); press(); moveBy(Offset(-forward * 3, 0f)); release()
                }
                waitForIdle()
                assertEquals(0, list.firstVisibleItemScrollOffset)
                assertEquals(0, clicks, "Dragging at the start edge must not open a card")
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `mouse flicks fling and a new drag or disabled handler releases the scroll mutation`() {
        runDesktopComposeUiTest(width = 400, height = 160) {
            lateinit var list: LazyListState
            var enabled by mutableStateOf(true)
            setContent {
                list = rememberLazyListState()
                LazyRow(state = list, userScrollEnabled = enabled,
                    modifier = Modifier.mouseDragScroll(list, Orientation.Horizontal, enabled).testTag("rail")) {
                    items((0..100).toList()) { Box(Modifier.size(100.dp)) }
                }
            }
            mainClock.autoAdvance = false
            onNodeWithTag("rail").performMouseInput {
                moveTo(center); press()
                repeat(6) { advanceEventTime(16); moveBy(Offset(-30f, 0f)) }
                release()
            }
            mainClock.advanceTimeByFrame()
            runOnIdle { assertTrue(list.isScrollInProgress, "A mouse flick must keep moving after release") }
            val afterDrag = list.firstVisibleItemIndex * 100 + list.firstVisibleItemScrollOffset
            mainClock.advanceTimeBy(96)
            runOnIdle {
                assertTrue(list.firstVisibleItemIndex * 100 + list.firstVisibleItemScrollOffset > afterDrag)
            }
            onNodeWithTag("rail").performMouseInput { moveTo(center); press(); moveBy(Offset(40f, 0f)) }
            mainClock.advanceTimeByFrame()
            val held = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
            mainClock.advanceTimeBy(500)
            runOnIdle { assertEquals(held, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset) }
            runOnIdle { enabled = false }
            mainClock.advanceTimeByFrame()
            runOnIdle { assertFalse(list.isScrollInProgress, "Removing the handler must release its mutation") }
            onNodeWithTag("rail").performMouseInput { release() }
            mainClock.autoAdvance = true
            val disabled = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
            onNodeWithTag("rail").performMouseInput {
                moveTo(center); press(); moveBy(Offset(-100f, 0f)); release()
            }
            runOnIdle { assertEquals(disabled, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset) }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `shared hero mouse dragging snaps to pages and preserves card clicks`() {
        for (appearance in listOf(DesktopAppearance.LIGHT, DesktopAppearance.DARK)) {
            runDesktopComposeUiTest(width = 600, height = 240) {
                lateinit var pager: PagerState
                var clicked: Int? = null
                setContent {
                    DesktopTheme(appearance) {
                        pager = rememberPagerState(pageCount = { 8 })
                        HomeHeroPager((0..7).toList(), { it }, pager, TabletLayoutClass.COMPACT,
                            modifier = Modifier.testTag("hero")) { _, entry, modifier ->
                            HomeHeroCardSurface(onClick = { clicked = entry },
                                modifier = modifier.testTag("card:$entry"), background = {}) { Text("作品 $entry") }
                        }
                    }
                }
                onNodeWithTag("hero").performMouseInput {
                    moveTo(Offset(width * .75f, height / 2f)); press()
                    repeat(10) { advanceEventTime(16); moveBy(Offset(-28f, 0f)) }
                    release()
                }
                waitForIdle()
                assertTrue(pager.currentPage > 0)
                assertTrue(abs(pager.currentPageOffsetFraction) < .001f, "Hero must settle on a page after release")
                assertNull(clicked)
                onNodeWithTag("card:${pager.currentPage}").performMouseInput { click(center) }
                assertEquals(pager.currentPage, clicked)
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `shared update carousel and filter rails support mouse grab scrolling without selection`() {
        runDesktopComposeUiTest(width = 420, height = 600) {
            var clicks = 0
            val values = (1..20).toList()
            setContent {
                DesktopTheme {
                    Column {
                        UpdatedContentCarousel(values, { it }, { "作品 $it" }, { 2 }, { "新章节 $it" }, "更新", "全部",
                            onItemClick = { clicks++ }, onMoreClick = null, cover = { _, _ -> }, badges = { _, _ -> },
                            modifier = Modifier.testTag("updates"))
                        TopBarTabsRail(values.map { TopBarTabItem(it.toLong(), "分类 $it") }, 1, { clicks++ },
                            surface = {}, modifier = Modifier.testTag("tabs"))
                        TopBarFilterRail(values, { it }, { "内容来源 $it" }, { it == 1 }, { clicks++ },
                            surface = {}, modifier = Modifier.testTag("filters"))
                    }
                }
            }
            for (tag in listOf("updates", "tabs", "filters")) {
                val node = onNodeWithTag(tag)
                val rail = node.onChildren().filter(hasScrollAction()).fetchSemanticsNodes().firstOrNull()
                    ?: onAllNodes(hasScrollAction()).fetchSemanticsNodes().first { child ->
                        node.fetchSemanticsNode().boundsInRoot.contains(child.boundsInRoot.center)
                    }
                val before = rail.config[SemanticsProperties.HorizontalScrollAxisRange].value()
                node.performMouseInput {
                    // The carousel header is above its card list.
                    moveTo(Offset(width * .75f, height / 2f)); press()
                    repeat(8) { advanceEventTime(16); moveBy(Offset(-25f, 0f)) }
                    release()
                }
                waitForIdle()
                assertTrue(rail.config[SemanticsProperties.HorizontalScrollAxisRange].value() > before, "$tag must scroll")
                assertEquals(0, clicks, "$tag must not select a card or filter after dragging")
            }
        }
    }
}
