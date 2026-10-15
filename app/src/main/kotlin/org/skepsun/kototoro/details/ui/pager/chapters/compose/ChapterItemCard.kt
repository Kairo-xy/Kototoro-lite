package org.skepsun.kototoro.details.ui.pager.chapters.compose

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.chapters.ChapterCardData
import org.skepsun.kototoro.core.ui.chapters.ChapterGridCard as SharedChapterGridCard
import org.skepsun.kototoro.core.ui.chapters.ChapterListCard as SharedChapterListCard
import org.skepsun.kototoro.details.ui.model.ChapterListItem

/** Android resource and TV-focus adapters for the shared chapter cards. */
@Composable
fun ChapterListCard(
    item: ChapterListItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) = SharedChapterListCard(
    item = item.cardData(),
    isSelected = isSelected,
    onClick = onClick,
    onLongClick = onLongClick,
    focusRequester = focusRequester,
    modifier = modifier,
    focusModifier = Modifier.tvFocusable(shape = RoundedCornerShape(8.dp), borderWidth = 2.dp, addFocusTarget = false),
)

@Composable
fun ChapterGridCard(
    item: ChapterListItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) = SharedChapterGridCard(
    item = item.cardData(),
    isSelected = isSelected,
    onClick = onClick,
    onLongClick = onLongClick,
    focusRequester = focusRequester,
    modifier = modifier,
    focusModifier = Modifier.tvFocusable(shape = RoundedCornerShape(8.dp), borderWidth = 3.dp, addFocusTarget = false),
)

@Composable
private fun ChapterListItem.cardData() = ChapterCardData(
    title = getTitle(LocalContext.current.resources),
    description = description,
    isCurrent = isCurrent,
    isUnread = isUnread,
    isBookmarked = isBookmarked,
    isDownloaded = isDownloaded,
    isNew = isNew,
)
