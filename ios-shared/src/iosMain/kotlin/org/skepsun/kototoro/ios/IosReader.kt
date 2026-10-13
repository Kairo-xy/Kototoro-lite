package org.skepsun.kototoro.ios

import org.skepsun.kototoro.reader.core.*

data class IosReaderPlacement(
    val index: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val segment: Int = 0,
)
data class IosReaderSlot(val index: Int, val anchor: Int, val placements: List<IosReaderPlacement>)
/** Stable chapter-local identity, independent of its current index in the scene window. */
data class IosReaderPage(val chapterId: String, val index: Int)
data class IosReaderProgress(val index: Int, val offset: Float)
data class IosReaderViewport(val x: Float, val y: Float)
data class IosReaderSize(val width: Int, val height: Int)
data class IosReaderRect(val left: Int, val top: Int, val width: Int, val height: Int)
data class IosReaderTile(
    val row: Int,
    val col: Int,
    val sampleSize: Int,
    val logicalLeft: Int,
    val logicalTop: Int,
    val logicalWidth: Int,
    val logicalHeight: Int,
    val decodeLeft: Int,
    val decodeTop: Int,
    val decodeWidth: Int,
    val decodeHeight: Int,
)

/**
 * How one paged slot looks during a turn ([PageTurnTransition]): [shift] moves it along the paging axis by a fraction
 * of the page in reading order (cancelling its scroll travel when it stays put), and a folding page draws its front on
 * the [keepX]/[keepY] side of the fold line through ([topX], [topY]) and ([bottomX], [bottomY]) with its back mirrored
 * across that line. Points are page-local and physical.
 */
data class IosPageTurn(
    val shift: Float,
    val alpha: Float,
    val shade: Float,
    val folding: Boolean,
    val topX: Float = 0f,
    val topY: Float = 0f,
    val bottomX: Float = 0f,
    val bottomY: Float = 0f,
    val keepX: Float = 0f,
    val keepY: Float = 0f,
)

/** Geometry and reading semantics stay in reader-core; SwiftUI owns pixels, gestures and native scrolling. */
class IosReader {
    private var scene: MutableReaderScene? = null
    private var width = 1
    private var height = 1
    private val sizes = mutableMapOf<Int, PageGeometryHint>()
    private val specLookup = mutableMapOf<Long, Pair<Int, Int>>()
    private var count = 0
    private var pageKeys: List<IosReaderPage> = emptyList()
    private var mode = 0
    private var doublePage = false
    private var cover = false
    private var fit = 0
    private var splitWide = false
    private var horizontalReversed = false
    /** Continuous modes' cross-axis zoom: the strip is laid out this many times wider (taller for mode 4). */
    private var zoom = 1f

    fun configure(count: Int, width: Int, height: Int, mode: Int, doublePage: Boolean, cover: Boolean, fit: Int) =
        configure(count, width, height, mode, doublePage, cover, fit, false)

    fun configure(
        count: Int,
        width: Int,
        height: Int,
        mode: Int,
        doublePage: Boolean,
        cover: Boolean,
        fit: Int,
        splitWide: Boolean,
    ) = configure(count, width, height, mode, doublePage, cover, fit, splitWide, false)

    fun configure(
        count: Int,
        width: Int,
        height: Int,
        mode: Int,
        doublePage: Boolean,
        cover: Boolean,
        fit: Int,
        splitWide: Boolean,
        horizontalReversed: Boolean,
    ) {
        require(count >= 0)
        this.count = count; this.width = width.coerceAtLeast(1); this.height = height.coerceAtLeast(1)
        this.mode = mode; this.doublePage = doublePage; this.cover = cover; this.fit = fit; this.splitWide = splitWide
        this.horizontalReversed = horizontalReversed
        sizes.keys.retainAll(0 until count)
        if (pageKeys.size != count) pageKeys = emptyList()
        rebuild()
    }

    fun chapterWindow(
        previous: List<IosReaderPage>, current: List<IosReaderPage>, next: List<IosReaderPage>, adjacentPageCount: Int,
    ): List<IosReaderPage> = ReaderChapterWindow.pages(previous, current, next, adjacentPageCount)

    /** Retains measured dimensions by identity when adjacent pages prepend or the active chapter changes. */
    fun setPages(pages: List<IosReaderPage>) {
        val measured = pageKeys.mapIndexedNotNull { index, key -> sizes[index]?.let { key to it } }.toMap()
        sizes.clear()
        pages.forEachIndexed { index, key -> measured[key]?.let { sizes[index] = it } }
        pageKeys = pages.toList()
        count = pages.size
        rebuild()
    }

    /**
     * Zooms a continuous strip by laying it out again at [zoom] × the viewport's cross axis, so pages decode and tile
     * for the size they are shown at and the scroll view pans the extra width natively. Offsets in [progress] and
     * [scrollOrigin] stay in unzoomed pixels, so saved positions do not depend on the zoom. Paged modes ignore it.
     */
    fun setZoom(zoom: Float) {
        val value = zoom.coerceIn(MINIMUM_ZOOM, MAXIMUM_ZOOM)
        if (value == this.zoom) return
        this.zoom = value
        if (mode >= 3) rebuild()
    }

    fun reset() { sizes.clear(); specLookup.clear(); pageKeys = emptyList(); scene = null; count = 0 }

    fun setImageSize(index: Int, width: Int, height: Int) {
        updateImageSize(index, width, height, null)
    }

    /** Returns the compensated continuous viewport when a late image size changes the strip layout. */
    fun setImageSize(index: Int, width: Int, height: Int, viewportX: Float, viewportY: Float): IosReaderViewport? =
        updateImageSize(index, width, height, ReaderViewport(
            FloatRect.fromLtwh(viewportX, viewportY, this.width.toFloat(), this.height.toFloat()),
        ))

    private fun updateImageSize(index: Int, width: Int, height: Int, viewport: ReaderViewport?): IosReaderViewport? {
        if (index !in 0 until count || width <= 0 || height <= 0) return null
        val hint = PageGeometryHint.Exact(width, height)
        if (sizes[index] == hint) return null
        sizes[index] = hint
        if (splitWide && !doublePage && mode < 3) {
            rebuild()
        } else {
            val compensation = scene?.updatePageHint(PageId(index.toLong()), hint, viewport)
            if (mode >= 3 && compensation != null) {
                val bounds = compensation.compensatedViewport.bounds
                return IosReaderViewport(bounds.left, bounds.top)
            }
        }
        return null
    }

    private fun rebuild() {
        specLookup.clear()
        val pages = (0 until count).map { PageId(it.toLong()) to (sizes[it] ?: PageGeometryHint.Estimated(0.7f)) }
        val direction = if (mode == 1 || mode == 4 && horizontalReversed) SceneReadingDirection.RIGHT_TO_LEFT else
            if (mode == 2 || mode == 3) SceneReadingDirection.TOP_TO_BOTTOM else SceneReadingDirection.LEFT_TO_RIGHT
        scene = when (mode) {
            3 -> VerticalReaderScene(zoomed(width), height, pages)
            4 -> HorizontalReaderScene(zoomed(height), width, pages, readingDirection = direction)
            else -> {
                val specs = mutableListOf<PagedPageSpec>()
                val chapterIds = pageKeys.map { it.chapterId }.distinct().withIndex().associate { it.value to it.index.toLong() }
                pages.forEachIndexed { index, (id, hint) ->
                    val key = pageKeys.getOrNull(index)
                    val chapterId = key?.let { chapterIds.getValue(it.chapterId) } ?: 0L
                    val chapterIndex = key?.index ?: index
                    if (splitWide && !doublePage && hint is PageGeometryHint.Exact && WidePagePolicy.isWidePage(hint)) {
                        val halfHint = PageGeometryHint.Exact((hint.width / 2).coerceAtLeast(1), hint.height)
                        val firstSeg = if (mode == 1) PageSegment.RIGHT_HALF else PageSegment.LEFT_HALF
                        val secondSeg = if (mode == 1) PageSegment.LEFT_HALF else PageSegment.RIGHT_HALF
                        val firstCode = if (firstSeg == PageSegment.LEFT_HALF) 1 else 2
                        val secondCode = if (secondSeg == PageSegment.LEFT_HALF) 1 else 2
                        val secondId = PageId((count + index).toLong())
                        specLookup[id.value] = index to firstCode
                        specLookup[secondId.value] = index to secondCode
                        specs += PagedPageSpec(id, halfHint, chapterId, chapterIndex, segment = firstSeg)
                        specs += PagedPageSpec(secondId, halfHint, chapterId, chapterIndex, segment = secondSeg)
                    } else {
                        specLookup[id.value] = index to 0
                        specs += PagedPageSpec(id, hint, chapterId, chapterIndex)
                    }
                }
                PagedReaderScene(width, height,
                    PagedSpreadConfig(doublePage, cover, direction, zoomMode = when (fit) {
                        1 -> ZoomMode.FIT_WIDTH
                        2 -> ZoomMode.FIT_HEIGHT
                        3 -> ZoomMode.KEEP_START
                        else -> ZoomMode.FIT_CENTER
                    }),
                    specs)
            }
        }
    }

    private fun zoomed(extent: Int): Int = (extent * zoom).toInt().coerceAtLeast(1)

    fun slots(): List<IosReaderSlot> {
        val current = scene ?: return emptyList()
        return if (current is PagedReaderScene) current.allSlots.map { slot ->
            val anchor = specLookup[slot.progressAnchorPageId.value]?.first ?: slot.progressAnchorPageId.value.toInt()
            IosReaderSlot(slot.slotIndex, anchor, slot.placements.map {
                val b = it.boundsInSlot
                val (pageIndex, segment) = specLookup[it.pageId.value] ?: (it.pageId.value.toInt() to 0)
                IosReaderPlacement(pageIndex, b.left, b.top, b.width, b.height, segment)
            })
        } else current.pageGeometries.mapIndexed { index, geometry ->
            val b = geometry.sceneBounds
            IosReaderSlot(index, geometry.pageId.value.toInt(), listOf(
                IosReaderPlacement(index, b.left, b.top, b.width, b.height, 0)))
        }
    }

    fun progress(x: Float, y: Float): IosReaderProgress {
        val frame = scene?.resolve(ReaderViewport(FloatRect.fromLtwh(x, y, width.toFloat(), height.toFloat())))
        val rawId = frame?.progress?.activePageId?.value ?: 0L
        val pageIndex = specLookup[rawId]?.first ?: rawId.toInt()
        return IosReaderProgress(pageIndex, (frame?.progress?.intraPageOffsetPx ?: 0f) / continuousZoom)
    }

    /** The last page in reading order that the viewport at ([x], [y]) shows, or -1 when it shows none. */
    fun lastVisible(x: Float, y: Float): Int {
        val frame = scene?.resolve(ReaderViewport(FloatRect.fromLtwh(x, y, width.toFloat(), height.toFloat())))
        val rawId = frame?.progress?.lastVisiblePageId?.value ?: return -1
        return specLookup[rawId]?.first ?: rawId.toInt()
    }

    fun decodeSize(width: Int, height: Int): IosReaderSize {
        val resolved = ImageDecodeSize.resolve(IntSize(width, height), MAXIMUM_PIXELS, MAXIMUM_DIMENSION)
        return IosReaderSize(resolved.width, resolved.height)
    }

    fun isTiled(width: Int, height: Int): Boolean =
        width > 4096 || height > 4096 || width.toLong() * height > MAXIMUM_PIXELS

    fun edgeSampleSize(width: Int, height: Int): Int =
        ReaderEdgeDetection.sampleSize(width, height)

    fun contentBounds(
        width: Int,
        height: Int,
        sampleSize: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        pixels: IntArray,
    ): IosReaderRect? {
        if (width <= 0 || height <= 0 || sampleSize <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) return null
        if (pixels.size < bitmapWidth * bitmapHeight) return null
        val rect = ReaderEdgeDetection.contentBounds(width, height, sampleSize, bitmapWidth, bitmapHeight) { out, x, y, w, h ->
            for (row in 0 until h) {
                val srcOffset = (y + row) * bitmapWidth + x
                val dstOffset = row * w
                pixels.copyInto(out, destinationOffset = dstOffset, startIndex = srcOffset, endIndex = srcOffset + w)
            }
        } ?: return null
        return IosReaderRect(rect.left, rect.top, rect.width, rect.height)
    }

    fun visibleTiles(
        x: Float,
        y: Float,
        index: Int,
        encodedWidth: Int,
        encodedHeight: Int,
        displayScale: Float,
    ): List<IosReaderTile> = visibleTiles(x, y, index, encodedWidth, encodedHeight, displayScale, 1f)

    /** Pixel density affects sampling only; camera zoom independently determines the visible source region. */
    fun visibleTiles(
        x: Float,
        y: Float,
        index: Int,
        encodedWidth: Int,
        encodedHeight: Int,
        displayScale: Float,
        zoomScale: Float,
    ): List<IosReaderTile> {
        if (index !in 0 until count || encodedWidth <= 0 || encodedHeight <= 0) return emptyList()
        val current = scene ?: return emptyList()
        val density = displayScale.coerceAtLeast(1f)
        val cameraScale = zoomScale.coerceAtLeast(1f)
        val node = if (current is PagedReaderScene) {
            val slot = current.allSlots.firstOrNull { s ->
                s.placements.any { (specLookup[it.pageId.value]?.first ?: it.pageId.value.toInt()) == index }
           }
            slot?.visibleContentNodes(cameraScale, x, y)?.firstOrNull {
                (specLookup[it.pageId.value]?.first ?: it.pageId.value.toInt()) == index
            }
        } else {
            val frame = current.resolve(ReaderViewport(FloatRect.fromLtwh(x, y, width.toFloat(), height.toFloat())))
            frame.visibleNodes.firstOrNull { it.pageId.value.toInt() == index }
        } ?: return emptyList()
        val pixelScale = density * if (current is PagedReaderScene) cameraScale else 1f
        return VisibleImageTiles.resolve(node, IntSize(encodedWidth, encodedHeight), pixelScale).map { spec ->
            IosReaderTile(
                row = spec.key.row,
                col = spec.key.col,
                sampleSize = spec.sampleSize,
                logicalLeft = spec.logicalRect.left,
                logicalTop = spec.logicalRect.top,
                logicalWidth = spec.logicalRect.width,
                logicalHeight = spec.logicalRect.height,
                decodeLeft = spec.decodeRegion.left,
                decodeTop = spec.decodeRegion.top,
                decodeWidth = spec.decodeRegion.width,
                decodeHeight = spec.decodeRegion.height,
            )
        }
    }

    /**
     * The turn of [slot] while the pager rests on or travels from [anchorSlot] to [position] (slots in reading order).
     * [style] is Android's `ReaderAnimation` ordinal (`NONE`, `DEFAULT`, `ADVANCED`, `SIMULATION`); [mirrored] is
     * right-to-left reading, where pages fold from the physical left edge. [downY] is where the finger went down, as a
     * fraction of the page across the paging axis.
     */
    fun pageTurn(
        style: Int,
        slot: Int,
        position: Float,
        anchorSlot: Int,
        scrolling: Boolean,
        width: Float,
        height: Float,
        vertical: Boolean,
        mirrored: Boolean,
        downY: Float = 0.5f,
    ): IosPageTurn {
        val turn = when (style) {
            2 -> PageTurnStyle.COVER
            3 -> PageTurnStyle.CURL
            else -> return IosPageTurn(0f, 1f, 0f, false)
        }
        val nearest = kotlin.math.round(position).toInt()
        val motion = PagedMotionSnapshot(
            currentSlot = nearest, settledSlot = anchorSlot, targetSlot = nearest,
            offsetFraction = position - nearest, isScrollInProgress = scrolling,
        )
        // Reading-order semantics: SwiftUI mirrors the pager for right-to-left reading itself.
        val input = PagedTransitionResolver.resolve(motion, slot, SceneReadingDirection.LEFT_TO_RIGHT)
        val transform = PageTurnTransition.transformFor(turn, input, vertical, isReversed = false)
        val shift = when (turn) {
            PageTurnStyle.COVER -> if (PageTurnTransition.activeCoverPinnedSlot(motion) == slot) -input.pageOffset else 0f
            else -> transform.translationFactor
        }
        val geometry = PageTurnTransition.curlGeometry(
            width = width,
            height = height,
            transform = transform,
            downFraction = if (vertical) PagePoint(downY, 0.5f) else PagePoint(0.5f, downY),
            isVertical = vertical,
            isReversed = mirrored && !vertical,
        ) ?: return IosPageTurn(shift, transform.alpha, transform.revealedPageShade, false)
        val front = geometry.frontPath
        return IosPageTurn(
            shift = shift,
            alpha = transform.alpha,
            shade = transform.revealedPageShade,
            folding = true,
            topX = geometry.topCurlOffset.x,
            topY = geometry.topCurlOffset.y,
            bottomX = geometry.bottomCurlOffset.x,
            bottomY = geometry.bottomCurlOffset.y,
            // The front polygon starts at the page corner the fold travels towards; its centre is always kept.
            keepX = front.sumOf { it.x.toDouble() }.toFloat() / front.size,
            keepY = front.sumOf { it.y.toDouble() }.toFloat() / front.size,
        )
    }

    fun clampPan(value: Float, contentMin: Float, contentMax: Float, viewport: Float, scale: Float): Float =
        value.coerceIn(PagedPanBoundsResolver.resolvePanRange(contentMin, contentMax, viewport, scale.coerceAtLeast(1f)))

    fun autoScrollDelay(speed: Float): Long = ReaderAutoScroll.pageSwitchDelayMs(speed)

    fun scrollOrigin(index: Int, offset: Float): Float = scene?.resolveViewportOriginForPage(
        PageId(index.toLong()), if (mode == 3) height.toFloat() else width.toFloat(), offset * continuousZoom,
    )?.coerceIn(0f, scrollLimit()) ?: 0f

    fun scrollLimit(): Float = ((scene?.totalSceneExtent ?: 0f) - if (mode == 3) height else width).coerceAtLeast(0f)

    private val continuousZoom: Float get() = if (mode >= 3) zoom else 1f

    fun autoScrollDistance(speed: Float, elapsedMillis: Int): Float =
        elapsedMillis.coerceAtLeast(0).toFloat() * ReaderAutoScroll.speedMultiplier(speed) / 32f

    companion object {
        const val MAXIMUM_PIXELS = 4L * 1024 * 1024
        const val MAXIMUM_DIMENSION = 16384
        const val MINIMUM_ZOOM = 1f
        const val MAXIMUM_ZOOM = 4f
    }
}
