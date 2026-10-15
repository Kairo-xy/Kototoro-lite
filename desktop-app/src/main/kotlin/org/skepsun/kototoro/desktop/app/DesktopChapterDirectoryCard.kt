package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.ui.chapters.*
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons

/** One projection for reader and details cards, over the existing history/bookmark/download stores. */
internal fun desktopChapterCardData(
    chapter: SourceChapter,
    current: SourceChapter?,
    bookmarked: Set<Long>,
    downloaded: List<SourceChapter>,
) = ChapterCardData(
    title = desktopChapterTitle(chapter),
    description = listOfNotNull(chapter.numberLabel()?.let { "#$it" },
        chapter.scanlator?.takeIf(String::isNotBlank) ?: chapter.branch?.takeIf(String::isNotBlank)).joinToString(" • "),
    isCurrent = current != null && DesktopDownloadStore.sameChapter(chapter, current),
    isUnread = current == null || current.branch != chapter.branch ||
        chapterIsAfter(chapter.number, chapter.volume, current.number, current.volume),
    isBookmarked = chapter.id in bookmarked,
    isDownloaded = downloaded.any { DesktopDownloadStore.sameChapter(it, chapter) },
)

/** Shared Android cards with the desktop's existing single-chapter download action supplied as a slot. */
@Composable
internal fun DesktopChapterDirectoryCard(
    chapter: SourceChapter,
    data: ChapterCardData,
    isGrid: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    onDownload: (() -> Unit)? = null,
) {
    val actions: (@Composable RowScope.() -> Unit)? = onDownload?.let { download ->
        {
            IconButton(download, enabled = enabled, modifier = Modifier.testTag("download-chapter:${chapter.id}")) {
                Icon(ReaderPanelIcons.Download, AndroidStrings["download"])
            }
        }
    }
    if (isGrid) ChapterGridCard(data, false, onClick, enabled = enabled, modifier = modifier, actions = actions)
    else ChapterListCard(data, false, onClick, enabled = enabled, modifier = modifier, actions = actions)
}
