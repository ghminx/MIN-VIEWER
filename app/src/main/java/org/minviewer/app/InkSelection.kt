package org.minviewer.app

import android.graphics.RectF
import kotlin.math.abs

/** 페이지 내부 0..1 좌표의 필기 선택과 일괄 변환 결과 유지. */
class InkSelection(val indices: Set<Int>, val documentStrokes: List<InkStroke>) {
    val page = documentStrokes[indices.first()].page
    val bounds: RectF = documentStrokes.filterIndexed { i, _ -> i in indices }.flatMap { it.points }.let { points ->
        RectF(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }

    fun moved(dx: Float, dy: Float): InkSelection {
        val x = dx.coerceIn(-bounds.left, 1f - bounds.right)
        val y = dy.coerceIn(-bounds.top, 1f - bounds.bottom)
        return InkSelection(indices, documentStrokes.mapIndexed { i, stroke ->
            if (i !in indices) stroke else stroke.copy(points = stroke.points.map {
                it.copy(x = (it.x + x).coerceIn(0f, 1f), y = (it.y + y).coerceIn(0f, 1f))
            })
        })
    }

    fun resized(factor: Float): InkSelection {
        val cx = bounds.centerX(); val cy = bounds.centerY()
        val limitX = if (bounds.width() == 0f) Float.MAX_VALUE else 2 * minOf(cx, 1 - cx) / bounds.width()
        val limitY = if (bounds.height() == 0f) Float.MAX_VALUE else 2 * minOf(cy, 1 - cy) / bounds.height()
        val scale = minOf(factor, limitX, limitY)
        if (scale == 1f) return this
        return InkSelection(indices, documentStrokes.mapIndexed { i, stroke ->
            if (i !in indices) stroke else stroke.copy(width = stroke.width * scale, points = stroke.points.map {
                it.copy(x = (cx + (it.x - cx) * scale).coerceIn(0f, 1f), y = (cy + (it.y - cy) * scale).coerceIn(0f, 1f))
            })
        })
    }

    companion object {
        fun enclose(strokes: List<InkStroke>, page: Int, polygon: List<InkPoint>): InkSelection? {
            if (polygon.size < 3) return null
            val start = polygon.first()
            // 시작점의 미세한 흔들림에 영향받지 않도록 가장 먼 점을 기준으로 사용
            val next = polygon.maxBy { (it.x - start.x) * (it.x - start.x) + (it.y - start.y) * (it.y - start.y) }
            if (polygon.all { abs((it.x - start.x) * (next.y - start.y) - (it.y - start.y) * (next.x - start.x)) < 0.00000001f }) return null
            fun contains(p: InkPoint): Boolean {
                var inside = false
                for (i in polygon.indices) {
                    val a = polygon[i]; val b = polygon[(i + 1) % polygon.size]
                    val cross = (p.x - a.x) * (b.y - a.y) - (p.y - a.y) * (b.x - a.x)
                    if (abs(cross) < 0.000001f && p.x in minOf(a.x, b.x)..maxOf(a.x, b.x) &&
                        p.y in minOf(a.y, b.y)..maxOf(a.y, b.y)) return true
                    if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
                }
                return inside
            }
            val selected = strokes.indices.filter { strokes[it].page == page && strokes[it].points.all(::contains) }.toSet()
            return if (selected.isEmpty()) null else InkSelection(selected, strokes)
        }
    }
}
