package org.minviewer.app

import android.graphics.Color
import android.util.AtomicFile
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import java.io.File
import java.io.FilterOutputStream
import java.io.InterruptedIOException

class PdfInkExportNeedsMerge : java.io.IOException("기존 주석과 필기가 겹칩니다. 기존 주석을 표시 형태로 병합한 사본이 필요합니다.")

/** 원본 페이지 스트림을 보존하고 필기를 벡터로 추가하는 PDF 사본 출력. */
internal object PdfInkExporter {
    fun write(source: File, note: PdfInkNote, target: File, canceled: () -> Boolean, mergeAnnotations: Boolean) {
        fun checkCanceled() { if (canceled()) throw InterruptedIOException("작업을 취소했습니다.") }
        checkCanceled()
        val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(target.parentFile)
        PDDocument.load(source, memory).use { document ->
            require(!document.isEncrypted) { "암호 PDF의 필기 내보내기는 지원하지 않습니다." }
            require(document.signatureDictionaries.isEmpty()) { "전자서명 PDF는 서명 보호를 위해 내보내지 않습니다." }
            require(document.numberOfPages == note.pages) { "원본 페이지 수와 필기 정보가 다릅니다." }
            for ((index, strokes) in note.strokes.groupBy { it.page }) {
                checkCanceled()
                val page = document.getPage(index)
                val box = page.cropBox
                val width = box.width; val height = box.height
                require(width.isFinite() && height.isFinite() && width > 0 && height > 0) { "잘못된 PDF 페이지 크기입니다." }
                fun point(p: InkPoint): Pair<Float, Float> {
                    val (x, y) = when (page.rotation) {
                        90 -> p.y * width to p.x * height
                        180 -> (1 - p.x) * width to p.y * height
                        270 -> (1 - p.y) * width to (1 - p.x) * height
                        else -> p.x * width to (1 - p.y) * height
                    }
                    return box.lowerLeftX + x to box.lowerLeftY + y
                }
                val obstructing = page.annotations.filter { annotation ->
                    annotation.subtype !in listOf("Link", "Popup") && !annotation.isHidden && !annotation.isNoView && strokes.any { stroke ->
                        val points = stroke.points.map(::point)
                        val margin = stroke.width * minOf(width, height) / 2
                        val rect = annotation.rectangle
                        rect != null && points.minOf { it.first } - margin <= rect.upperRightX &&
                            points.maxOf { it.first } + margin >= rect.lowerLeftX &&
                            points.minOf { it.second } - margin <= rect.upperRightY &&
                            points.maxOf { it.second } + margin >= rect.lowerLeftY
                    }
                }
                require(obstructing.none { it.subtype == "Widget" }) { "입력란 위의 필기 내보내기는 아직 지원하지 않습니다. 필기 백업은 가능합니다." }
                if (obstructing.isNotEmpty() && !mergeAnnotations) throw PdfInkExportNeedsMerge()
                // 원본 주석 편집·재생 기능을 잃는 병합은 사용자 선택 후 사본에서만 수행
                if (obstructing.isNotEmpty()) mergeAppearances(document, page, obstructing)
                PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                    stream.addRect(box.lowerLeftX, box.lowerLeftY, width, height); stream.clip()
                    stream.setLineCapStyle(1); stream.setLineJoinStyle(1)
                    for (stroke in strokes) {
                        checkCanceled()
                        stream.saveGraphicsState()
                        val state = PDExtendedGraphicsState().apply {
                            val alpha = Color.alpha(stroke.color) / 255f
                            strokingAlphaConstant = alpha; nonStrokingAlphaConstant = alpha
                            blendMode = if (stroke.tool == "highlighter") BlendMode.MULTIPLY else BlendMode.NORMAL
                        }
                        stream.setGraphicsStateParameters(state)
                        val red = Color.red(stroke.color); val green = Color.green(stroke.color); val blue = Color.blue(stroke.color)
                        stream.setStrokingColor(red / 255f, green / 255f, blue / 255f)
                        stream.setNonStrokingColor(red / 255f, green / 255f, blue / 255f)
                        val lineWidth = stroke.width * minOf(width, height)
                        stream.setLineWidth(lineWidth)
                        val (x, y) = point(stroke.points.first())
                        if (stroke.points.size == 1) {
                            val r = lineWidth / 2; val k = r * .5522848f
                            stream.moveTo(x + r, y)
                            stream.curveTo(x + r, y + k, x + k, y + r, x, y + r)
                            stream.curveTo(x - k, y + r, x - r, y + k, x - r, y)
                            stream.curveTo(x - r, y - k, x - k, y - r, x, y - r)
                            stream.curveTo(x + k, y - r, x + r, y - k, x + r, y)
                            stream.closePath(); stream.fill()
                        } else {
                            stream.moveTo(x, y)
                            for (i in 1 until stroke.points.size) {
                                if (i % 1024 == 0) checkCanceled()
                                val (px, py) = point(stroke.points[i]); stream.lineTo(px, py)
                            }
                            stream.stroke()
                        }
                        stream.restoreGraphicsState()
                    }
                }
            }
            checkCanceled()
            val atomic = AtomicFile(target)
            val output = atomic.startWrite()
            try {
                // PDFBox가 스트림을 닫더라도 AtomicFile의 동기화·완료 처리는 호출부에서 수행
                val checked = object : FilterOutputStream(output) {
                    override fun write(b: Int) { checkCanceled(); out.write(b) }
                    override fun write(b: ByteArray, off: Int, len: Int) { checkCanceled(); out.write(b, off, len) }
                    override fun close() { flush() }
                }
                document.save(checked)
                checkCanceled()
                atomic.finishWrite(output)
            } catch (error: Exception) { atomic.failWrite(output); throw error }
        }
    }

    private fun mergeAppearances(document: PDDocument, page: PDPage, visual: List<PDAnnotation>) {
        val annotations = page.annotations
        PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
            for (annotation in visual) {
                val appearance = requireNotNull(annotation.normalAppearanceStream) { "표시 정보가 없는 기존 주석을 병합할 수 없습니다." }
                val rect = requireNotNull(annotation.rectangle)
                val box = requireNotNull(appearance.bBox)
                val transform = appearance.matrix
                val corners = listOf(transform.transformPoint(box.lowerLeftX, box.lowerLeftY), transform.transformPoint(box.upperRightX, box.lowerLeftY),
                    transform.transformPoint(box.lowerLeftX, box.upperRightY), transform.transformPoint(box.upperRightX, box.upperRightY))
                val left = corners.minOf { it.x }; val bottom = corners.minOf { it.y }
                val width = corners.maxOf { it.x } - left; val height = corners.maxOf { it.y } - bottom
                require(width.isFinite() && height.isFinite() && width > 0 && height > 0) { "잘못된 주석 표시 크기입니다." }
                stream.saveGraphicsState()
                stream.transform(Matrix.getTranslateInstance(rect.lowerLeftX, rect.lowerLeftY))
                stream.transform(Matrix.getScaleInstance(rect.width / width, rect.height / height))
                stream.transform(Matrix.getTranslateInstance(-left, -bottom))
                stream.drawForm(appearance)
                stream.restoreGraphicsState()
            }
        }
        val removed = visual.map { it.cosObject }.toSet()
        page.annotations = annotations.filter { it.cosObject !in removed && !(it is PDAnnotationPopup && it.parent?.cosObject in removed) }
    }
}
