package org.minviewer.app

/** 문서 타일의 중앙 맞춤과 같은 문서 지점을 유지하는 확대 원점 계산. */
internal class DocumentViewport(width: Int, height: Int, viewWidth: Int, viewHeight: Int) {
    val scale = minOf(viewWidth.toFloat() / width, viewHeight.toFloat() / height)
    val left = (viewWidth - width * scale) / 2f
    val top = (viewHeight - height * scale) / 2f

    fun origin(anchorX: Float, anchorY: Float, focusX: Float, focusY: Float): Pair<Int, Int> =
        (anchorX - (focusX - left) / scale).toInt() to (anchorY - (focusY - top) / scale).toInt()
}
