package org.minviewer.app

import android.app.Activity
import android.graphics.pdf.PdfDocument
import android.os.ParcelFileDescriptor
import com.caverock.androidsvg.SVG
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToInt

/** 화면과 같은 한글 SVG의 글자·도형을 PDF Canvas에 직접 기록. */
class HwpPdfSnapshot(
    private val activity: Activity,
    private val count: Int,
    private val readPage: (Int, (String) -> Unit) -> Unit,
    private val complete: (Result<File>) -> Unit
) {
    val directory=File(activity.cacheDir,"hwp-ink-${UUID.randomUUID()}")
    var sourceDigest=""
        private set
    private var page=0
    @Volatile private var closed=false
    private val pdf=PdfDocument()

    fun start() {
        PdfInkStore.io.execute {
            try {
                if(closed)return@execute
                val previous=activity.cacheDir.listFiles()?:throw IOException("필기 준비 캐시를 읽을 수 없습니다.")
                previous.filter {it.name.matches(Regex("hwp-ink-[0-9a-f-]{36}|hwp-source-[0-9]+\\.hwpx?"))}.forEach {
                    if(!it.deleteRecursively())throw IOException("중단된 한글 필기 준비 파일을 정리할 수 없습니다.")
                }
                if(!directory.mkdir())throw IOException("한글 필기 준비 폴더를 만들 수 없습니다.")
                activity.runOnUiThread {requestPage()}
            }catch(error:Exception){activity.runOnUiThread {finish(Result.failure(error))}}
        }
    }

    private fun requestPage() {
        if(closed)return
        readPage(page){value->
            if(closed)return@readPage
            PdfInkStore.io.execute {
                try {
                    if(closed)return@execute
                    val data=JSONObject(value)
                    if(data.has("error"))throw IOException(data.getString("error"))
                    val width=data.getDouble("width");val height=data.getDouble("height")
                    require(width.isFinite()&&height.isFinite()&&width in 1.0..100000.0&&height in 1.0..100000.0)
                    val digest=data.getString("digest")
                    require(digest.matches(Regex("[0-9a-f]{64}"))&&(sourceDigest.isEmpty()||sourceDigest==digest)){"한글 원본의 식별 정보가 달라졌습니다."}
                    sourceDigest=digest
                    val markup=data.getString("svg")
                    require(!Regex("<(?:filter|foreignObject)\\b").containsMatchIn(markup)){"이 문서의 SVG 효과는 PDF 출력에서 보존할 수 없습니다."}
                    val svg=SVG.getFromString(markup)
                    val info=PdfDocument.PageInfo.Builder((width*.75).roundToInt(),(height*.75).roundToInt(),page).create()
                    svg.setDocumentWidth(info.pageWidth.toFloat())
                    svg.setDocumentHeight(info.pageHeight.toFloat())
                    val sheet=pdf.startPage(info)
                    try {svg.renderToCanvas(sheet.canvas)}
                    finally {pdf.finishPage(sheet)}
                    page++
                    if(page<count)activity.runOnUiThread{requestPage()}else {
                        val output=File(directory,"background.pdf")
                        val raw=File(directory,"canvas.pdf")
                        raw.outputStream().use {pdf.writeTo(it);it.fd.sync()}
                        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(activity.applicationContext)
                        PdfGradientCompatibility.normalize(raw,output)
                        ParcelFileDescriptor.open(output,ParcelFileDescriptor.MODE_READ_ONLY).use {fd->android.graphics.pdf.PdfRenderer(fd).use {require(it.pageCount==count){"한글 문서와 PDF 페이지 수가 다릅니다."}}}
                        activity.runOnUiThread {finish(Result.success(output))}
                    }
                }catch(error:Exception){activity.runOnUiThread {finish(Result.failure(error))}}
            }
        }
    }
    private fun finish(result:Result<File>){if(closed)return;closed=true;pdf.close();complete(result)}
    fun close(){if(!closed){closed=true;PdfInkStore.io.execute {pdf.close()}}}
}
