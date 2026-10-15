package org.skepsun.kototoro.core.ui.home

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.skepsun.kototoro.core.ui.adaptive.TabletLayoutClass
import org.skepsun.kototoro.core.ui.gestures.mouseDragScroll

/** Android's hero pager geometry and card motion, used by both hosts. */
@Composable
fun <T> HomeHeroPager(
    entries: List<T>,
    key: (T) -> Any,
    pagerState: PagerState,
    layoutClass: TabletLayoutClass,
    modifier: Modifier = Modifier,
    tvPresentation: Boolean = false,
    holdSelectedCardStill: Boolean = false,
    pagerModifier: Modifier = Modifier,
    content: @Composable (page: Int, entry: T, cardModifier: Modifier) -> Unit,
) {
    if (entries.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val edgePadding = HomeScreenHorizontalPadding
        val spacing = if (tvPresentation) 16.dp else 6.dp
        val cardWidth = if (tvPresentation) {
            minOf(440.dp, maxWidth * 0.78f).coerceAtMost((maxWidth - edgePadding * 2).coerceAtLeast(0.dp))
        } else homeHeroCardWidth(maxWidth, edgePadding, spacing, layoutClass)
        val contentPadding = PaddingValues(horizontal = edgePadding)
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val padPx = with(density) { contentPadding.calculateLeftPadding(direction).toPx() }
        val stepPx = with(density) { (cardWidth + spacing).toPx() }
        val viewportPx = with(density) { maxWidth.toPx() }
        val cardPx = with(density) { cardWidth.toPx() }
        val selectedIndex = pagerState.currentPage.coerceIn(0, entries.lastIndex)
        val fling = PagerDefaults.flingBehavior(pagerState)
        HorizontalPager(
            state = pagerState,
            pageSize = PageSize.Fixed(cardWidth),
            pageSpacing = spacing,
            beyondViewportPageCount = 1,
            contentPadding = contentPadding,
            flingBehavior = fling,
            key = { page -> entries.getOrNull(page)?.let(key) ?: "home_hero_pending_$page" },
            modifier = Modifier.fillMaxWidth().then(pagerModifier)
                .mouseDragScroll(pagerState, Orientation.Horizontal, flingBehavior = fling),
        ) { page ->
            entries.getOrNull(page)?.let { entry ->
                content(page, entry, Modifier.zIndex(if (page == selectedIndex) 1f else 0f).graphicsLayer {
                    val rawOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                    val signedOffset = rawOffset.coerceIn(-1f, 1f)
                    val left = padPx - rawOffset * stepPx
                    val right = left + cardPx
                    val focus = when {
                        right <= 0f || left >= viewportPx -> 0f
                        left >= 0f && right <= viewportPx -> 1f
                        left < 0f -> (right / cardPx).coerceIn(0f, 1f)
                        else -> ((viewportPx - left) / cardPx).coerceIn(0f, 1f)
                    }
                    val horizontalOrigin = when {
                        signedOffset < -0.02f -> 0f
                        signedOffset > 0.02f -> 1f
                        else -> 0.5f
                    }
                    // Shared-element flights require the selected cover to remain at its measured bounds.
                    val visualFocus = if (page == selectedIndex && holdSelectedCardStill) 1f else focus
                    scaleX = 0.9f + 0.1f * visualFocus
                    scaleY = 0.9f + 0.1f * visualFocus
                    alpha = 0.64f + 0.36f * visualFocus
                    transformOrigin = TransformOrigin(horizontalOrigin, 0.5f)
                })
            }
        }
    }
}
