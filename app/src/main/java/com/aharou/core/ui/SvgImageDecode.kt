package com.aharou.core.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil.ImageLoader
import coil.decode.SvgDecoder
import com.aharou.core.util.FileLogger
import com.caverock.androidsvg.SVG
import java.io.InputStream
import kotlin.math.floor
import kotlin.math.sqrt

internal fun svgImageLoader(context: Context): ImageLoader {
    SVG.setInternalEntitiesEnabled(false)
    return ImageLoader.Builder(context).components { add(SvgDecoder.Factory()) }.build()
}

internal fun decodeSvgBitmap(
    maxEdge: Int,
    maxPixels: Long,
    open: () -> InputStream,
): ImageBitmap? = try {
    SVG.setInternalEntitiesEnabled(false)
    val svg = open().use { SVG.getFromInputStream(it) }
    val viewBox = svg.documentViewBox
    val width = svg.documentWidth.takeIf { it.isFinite() && it > 0f }
        ?: viewBox?.width()?.takeIf { it.isFinite() && it > 0f } ?: SVG_DEFAULT_WIDTH
    val height = svg.documentHeight.takeIf { it.isFinite() && it > 0f }
        ?: viewBox?.height()?.takeIf { it.isFinite() && it > 0f } ?: width
    if (viewBox == null) svg.setDocumentViewBox(0f, 0f, width, height)
    svg.setDocumentWidth("100%")
    svg.setDocumentHeight("100%")
    val (targetWidth, targetHeight) = svgBitmapSize(width.toDouble(), height.toDouble(), maxEdge, maxPixels)
    val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    try {
        svg.renderToCanvas(Canvas(bitmap))
        bitmap.asImageBitmap()
    } catch (e: OutOfMemoryError) {
        bitmap.recycle()
        throw e
    } catch (e: Exception) {
        bitmap.recycle()
        throw e
    }
} catch (e: OutOfMemoryError) {
    FileLogger.w("SvgImageDecode", "SVG decode ran out of memory", e)
    null
} catch (e: Exception) {
    FileLogger.w("SvgImageDecode", "SVG decode failed", e)
    null
}

internal fun svgBitmapSize(width: Double, height: Double, maxEdge: Int, maxPixels: Long): Pair<Int, Int> {
    require(width.isFinite() && height.isFinite() && width > 0 && height > 0)
    require(maxEdge > 0 && maxPixels > 0)
    val scale = minOf(1.0, maxEdge / maxOf(width, height), sqrt(maxPixels / (width * height)))
    var targetWidth = floor(width * scale).toInt().coerceIn(1, maxEdge)
    var targetHeight = floor(height * scale).toInt().coerceIn(1, maxEdge)
    if (targetWidth.toLong() * targetHeight > maxPixels) {
        if (targetWidth >= targetHeight) targetWidth = (maxPixels / targetHeight).toInt().coerceAtLeast(1)
        else targetHeight = (maxPixels / targetWidth).toInt().coerceAtLeast(1)
    }
    return targetWidth to targetHeight
}

private const val SVG_DEFAULT_WIDTH = 512f
