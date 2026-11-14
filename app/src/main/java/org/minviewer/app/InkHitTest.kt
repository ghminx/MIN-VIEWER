package org.minviewer.app

import kotlin.math.min

object InkHitTest {
    fun hits(stroke: InkStroke, from: InkPoint, to: InkPoint, width: Float, height: Float, radius: Float): Boolean {
        fun distance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = bx - ax; val dy = by - ay
            val length = dx * dx + dy * dy
            val t = if (length == 0f) 0f else ((px - ax) * dx + (py - ay) * dy).div(length).coerceIn(0f, 1f)
            val x = px - ax - t * dx; val y = py - ay - t * dy
            return x * x + y * y
        }
        fun cross(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        val ax = from.x * width; val ay = from.y * height
        val bx = to.x * width; val by = to.y * height
        val threshold = radius + stroke.width * min(width, height) / 2
        for (i in stroke.points.indices) {
            val p = stroke.points[i]; val q = stroke.points[(i + 1).coerceAtMost(stroke.points.lastIndex)]
            val cx = p.x * width; val cy = p.y * height; val dx = q.x * width; val dy = q.y * height
            val crossing = cross(ax, ay, bx, by, cx, cy) * cross(ax, ay, bx, by, dx, dy) <= 0 &&
                cross(cx, cy, dx, dy, ax, ay) * cross(cx, cy, dx, dy, bx, by) <= 0 &&
                maxOf(minOf(ax, bx), minOf(cx, dx)) <= minOf(maxOf(ax, bx), maxOf(cx, dx)) &&
                maxOf(minOf(ay, by), minOf(cy, dy)) <= minOf(maxOf(ay, by), maxOf(cy, dy))
            if (crossing || minOf(distance(ax, ay, cx, cy, dx, dy), distance(bx, by, cx, cy, dx, dy),
                    distance(cx, cy, ax, ay, bx, by), distance(dx, dy, ax, ay, bx, by)) <= threshold * threshold) return true
        }
        return false
    }
}
