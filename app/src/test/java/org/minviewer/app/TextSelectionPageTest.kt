package org.minviewer.app

import org.junit.Assert.*
import org.junit.Test

class TextSelectionPageTest {
    @Test fun dragSelectsSameTextInBothDirections() {
        val page = TextSelectionPage(listOf(
            TextGlyph("Hello", 0f, 0f, .4f, .1f, ""),
            TextGlyph("world", .5f, 0f, 1f, .1f, " "),
            TextGlyph("next", 0f, .2f, .4f, .3f, "\n")
        ))
        assertEquals("Hello world\nnext", page.select(0, 2).text)
        assertEquals(page.select(0, 2), page.select(2, 0))
        assertEquals(1, page.hit(.7f, .05f))
        assertEquals("world", page.select(1, 1).text)
    }
    @Test fun imageOnlyPageHasNoSelection() {
        assertEquals(-1, TextSelectionPage(emptyList()).hit(.2f, .2f))
    }
    @Test fun longPressSelectsWordAndRejectsWhitespace() {
        val glyphs = "Hello world!".mapIndexed { i,c -> TextGlyph(c.toString(),i*.04f,.2f,(i+1)*.04f,.25f,"") }
        val page = TextSelectionPage(glyphs)
        val range = page.wordAt(.28f,.22f)!!
        assertEquals("world", page.select(range.first,range.last).text)
        assertNull(page.wordAt(.9f,.8f))
        assertNull(page.wordAt(.22f,.22f))
    }
    @Test fun rotatedPdfGlyphBoundsFollowDisplayedPage() {
        assertEquals(GlyphBounds(40f,110f,60f,120f),pdfGlyphBounds(40f,480f,20f,10f,0,0,400f,600f))
        assertEquals(GlyphBounds(480f,40f,490f,60f),pdfGlyphBounds(40f,480f,20f,10f,0,90,400f,600f))
        assertEquals(GlyphBounds(340f,480f,360f,490f),pdfGlyphBounds(40f,480f,20f,10f,0,180,400f,600f))
        assertEquals(GlyphBounds(110f,340f,120f,360f),pdfGlyphBounds(40f,480f,20f,10f,0,270,400f,600f))
    }
    @Test fun firstTouchAllowsSmallFingerOffsetButNotDistantWhitespace() {
        val page=TextSelectionPage(listOf(TextGlyph("본문",.1f,.2f,.3f,.25f,"")))
        assertNotNull(page.wordAt(.15f,.195f,.01f,.01f))
        assertNull(page.wordAt(.15f,.1f,.01f,.01f))
    }
}
