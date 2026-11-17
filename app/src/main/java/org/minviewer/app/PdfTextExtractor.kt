package org.minviewer.app

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import java.io.InputStream
import java.io.IOException

internal object PdfTextExtractor {
    fun read(input: InputStream, page: Int, password: String): TextSelectionPage {
        PDDocument.load(input, password, MemoryUsageSetting.setupTempFileOnly()).use { document ->
            if (!document.currentAccessPermission.canExtractContent()) throw IOException("이 문서는 텍스트 복사를 허용하지 않습니다.")
            val paper = document.getPage(page)
            val rotated = paper.rotation % 180 != 0
            val width = if (rotated) paper.cropBox.height else paper.cropBox.width
            val height = if (rotated) paper.cropBox.width else paper.cropBox.height
            val glyphs = mutableListOf<TextGlyph>()
            var previous:TextPosition?=null
            val stripper = object : PDFTextStripper() {
                override fun writeString(text: String, positions: MutableList<TextPosition>) {
                    for (p in positions) {
                        if (p.unicode.isEmpty()) continue
                        val bounds=pdfGlyphBounds(p.textMatrix.translateX,p.textMatrix.translateY,p.widthDirAdj,p.heightDir,p.dir.toInt(),paper.rotation,paper.cropBox.width,paper.cropBox.height)
                        val last=previous
                        val prefix = when {
                            last == null -> ""
                            kotlin.math.abs(p.yDirAdj-last.yDirAdj)>maxOf(p.heightDir,last.heightDir)*.6f -> "\n"
                            p.xDirAdj-last.xDirAdj-last.widthDirAdj>p.widthOfSpace*.4f && !last.unicode.endsWith(" ") -> " "
                            else -> ""
                        }
                        glyphs.add(TextGlyph(p.unicode,bounds.left/width,bounds.top/height,bounds.right/width,bounds.bottom/height,prefix))
                        previous=p
                    }
                }
            }
            stripper.sortByPosition = true
            stripper.startPage = page + 1
            stripper.endPage = page + 1
            stripper.getText(document)
            return TextSelectionPage(glyphs)
        }
    }
}
