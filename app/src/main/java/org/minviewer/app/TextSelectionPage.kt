package org.minviewer.app

data class TextGlyph(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float, val prefix: String)
data class SelectedText(val text: String, val glyphs: List<TextGlyph>)

class TextSelectionPage(val glyphs: List<TextGlyph>) {
    fun wordAt(x: Float, y: Float, paddingX:Float=0f, paddingY:Float=0f): IntRange? {
        val i = hit(x,y)
        if (i < 0) return null
        val g = glyphs[i]
        if (x !in (g.left-paddingX)..(g.right+paddingX) || y !in (g.top-paddingY)..(g.bottom+paddingY) || g.text.isBlank()) return null
        fun word(index: Int) = glyphs[index].text.codePoints().allMatch { Character.isLetterOrDigit(it) || Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
        if (!word(i)) return i..i
        var start = i; var end = i
        while (start > 0 && glyphs[start].prefix.isEmpty() && word(start-1)) start--
        while (end < glyphs.lastIndex && glyphs[end+1].prefix.isEmpty() && word(end+1)) end++
        return start..end
    }
    fun hit(x: Float, y: Float): Int = glyphs.indices.minByOrNull { i ->
        val g = glyphs[i]
        val dx = maxOf(g.left - x, 0f, x - g.right)
        val dy = maxOf(g.top - y, 0f, y - g.bottom)
        dx * dx + dy * dy
    } ?: -1

    fun select(start: Int, end: Int): SelectedText {
        val chosen = glyphs.subList(minOf(start, end), maxOf(start, end) + 1)
        return SelectedText(chosen.mapIndexed { i, g -> (if (i == 0) "" else g.prefix) + g.text }.joinToString(""), chosen)
    }
}
