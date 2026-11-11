package org.minviewer.app

import kotlin.math.sqrt

internal fun documentRenderSize(width: Int, height: Int, targetWidth: Int): Pair<Int, Int> {
    val maxPixels = 4_194_304.0
    val scale = minOf(
        targetWidth.coerceIn(1, 2560).toDouble() / width,
        2560.0 / height,
        sqrt(maxPixels / (width.toDouble() * height)),
    )
    return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
}
