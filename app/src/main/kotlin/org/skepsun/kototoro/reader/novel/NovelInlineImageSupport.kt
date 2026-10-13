package org.skepsun.kototoro.reader.novel

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.text.style.ReplacementSpan
import kotlin.math.min

data class NovelInlineImageRequest(
    val imagePath: String,
    val epubFilePath: String?,
    val chapterPath: String?,
    val headers: Map<String, String>,
)

internal data class NovelImageMetrics(
    val width: Int,
    val height: Int,
) {
    fun fittedSize(maxWidth: Float, maxHeight: Float): Pair<Float, Float> {
        if (width <= 0 || height <= 0 || maxWidth <= 0f || maxHeight <= 0f) {
            return 0f to 0f
        }
        var targetWidth = min(width.toFloat(), maxWidth)
        var targetHeight = targetWidth * height / width.toFloat()
        if (targetHeight > maxHeight) {
            val scale = maxHeight / targetHeight
            targetWidth *= scale
            targetHeight *= scale
        }
        return targetWidth to targetHeight
    }
}

internal object NovelImageMetricsCache {
    private val metrics = mutableMapOf<String, NovelImageMetrics>()

    @Synchronized
    fun get(imagePath: String): NovelImageMetrics? = metrics[imagePath]

    @Synchronized
    fun put(imagePath: String, value: NovelImageMetrics) {
        metrics[imagePath] = value
    }
}

fun applyInlineImageSpans(
    text: CharSequence,
    inlineImagePaths: List<String>,
    bitmapProvider: (String) -> android.graphics.Bitmap?,
): CharSequence {
    if (inlineImagePaths.isEmpty()) {
        return text
    }
    val ssb = android.text.SpannableStringBuilder.valueOf(text)
    inlineImagePaths.forEachIndexed { index, imagePath ->
        val token = "[INLINE_IMAGE_$index]"
        var start = ssb.indexOf(token)
        while (start >= 0) {
            val end = start + token.length
            ssb.setSpan(
                NovelInlineImageSpan(imagePath, bitmapProvider),
                start,
                end,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            start = ssb.indexOf(token, end)
        }
    }
    return ssb
}

internal fun findInlineImagePathAt(
    layout: Layout,
    x: Float,
    y: Float,
): String? {
    val text = layout.text as? Spanned ?: return null
    if (text.isEmpty()) {
        return null
    }
    val line = layout.getLineForVertical(y.toInt().coerceAtLeast(0))
    val offset = layout.getOffsetForHorizontal(line, x.coerceAtLeast(0f))
    val candidateOffsets = intArrayOf(
        offset.coerceIn(0, text.length - 1),
        (offset - 1).coerceIn(0, text.length - 1),
        (offset + 1).coerceIn(0, text.length - 1),
    )
    return candidateOffsets.asSequence()
        .flatMap { candidate ->
            text.getSpans(candidate, candidate, NovelInlineImageSpan::class.java).asSequence()
        }
        .map { it.imagePath }
        .firstOrNull()
}

internal fun getReservedNovelImageHeight(
    imagePath: String,
    maxWidth: Float,
    maxHeight: Float,
    fallbackAspectRatio: Float = 0.75f,
): Float {
    val metrics = NovelImageMetricsCache.get(imagePath)
    if (metrics != null) {
        return metrics.fittedSize(maxWidth, maxHeight).second
    }
    return min(maxHeight, maxWidth * fallbackAspectRatio)
}

internal fun getNovelImageDisplayRect(
    imagePath: String,
    reservedWidth: Float,
    reservedHeight: Float,
    yPosition: Float,
    fallbackAspectRatio: Float = 0.75f,
): RectF {
    val metrics = NovelImageMetricsCache.get(imagePath)
    val (targetWidth, targetHeight) = if (metrics != null) {
        metrics.fittedSize(reservedWidth, reservedHeight)
    } else {
        reservedWidth to min(reservedHeight, reservedWidth * fallbackAspectRatio)
    }
    val left = (reservedWidth - targetWidth) / 2f
    val top = yPosition + (reservedHeight - targetHeight) / 2f
    return RectF(left, top, left + targetWidth, top + targetHeight)
}

internal class NovelInlineImageSpan(
    val imagePath: String,
    private val bitmapProvider: (String) -> android.graphics.Bitmap?,
) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        return computeInlineSize(paint).first.toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val (targetWidth, targetHeight) = computeInlineSize(paint)
        val bitmap = bitmapProvider(imagePath)
        val topPos = y + paint.fontMetrics.ascent + ((paint.fontMetrics.descent - paint.fontMetrics.ascent) - targetHeight) / 2f
        if (bitmap != null) {
            val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
            val dstRect = RectF(x, topPos, x + targetWidth, topPos + targetHeight)
            canvas.drawBitmap(bitmap, srcRect, dstRect, null)
        } else {
            val oldStyle = paint.style
            val oldColor = paint.color
            paint.style = Paint.Style.STROKE
            paint.color = (oldColor and 0x00FFFFFF) or (0x66000000)
            canvas.drawRect(x, topPos, x + targetWidth, topPos + targetHeight, paint)
            paint.style = oldStyle
            paint.color = oldColor
        }
    }

    private fun computeInlineSize(paint: Paint): Pair<Float, Float> {
        val lineHeight = (paint.fontMetrics.descent - paint.fontMetrics.ascent).coerceAtLeast(1f)
        val targetHeight = lineHeight * 0.92f
        val metrics = NovelImageMetricsCache.get(imagePath)
        if (metrics == null || metrics.width <= 0 || metrics.height <= 0) {
            return targetHeight to targetHeight
        }
        var targetWidth = targetHeight * metrics.width / metrics.height.toFloat()
        val maxWidth = lineHeight * 2.2f
        if (targetWidth > maxWidth) {
            targetWidth = maxWidth
        }
        return targetWidth to targetHeight
    }
}
