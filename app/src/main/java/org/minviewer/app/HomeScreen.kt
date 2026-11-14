package org.minviewer.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import android.webkit.WebView
import android.webkit.WebViewClient
import java.text.DateFormat
import java.util.Date
import java.io.File

class HomeScreen(
    private val activity: Activity,
    private val recent: RecentDocuments,
    private val openPicker: () -> Unit,
    private val openDocumentActions: (String?) -> Unit,
    private val openRecent: (RecentDocument) -> Unit
) : FrameLayout(activity) {
    private val ui = UiStyle(activity)
    private val library = DocumentLibraryView(activity, openPicker) { showSettings() }
    private val store = PdfInkStore(File(activity.filesDir,"pdf-notes"))
    private val notice = ui.label("",12f,color=ui.muted)
    private var notes = emptyList<PdfNoteEntry>()
    private var refreshGeneration = 0

    init {
        setBackgroundColor(ui.background)
        setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        addView(library, LayoutParams(-1, -1))
        library.statusArea.addView(notice)
        refresh()
    }

    fun saveState(state: android.os.Bundle) = library.saveState(state)
    fun restoreState(state: android.os.Bundle?) = library.restoreState(state)

    fun refresh() {
        val generation=++refreshGeneration
        showDocuments()
        PdfInkStore.io.execute {
            try {
                val loaded=store.all()
                activity.runOnUiThread { if(!activity.isDestroyed&&generation==refreshGeneration){notes=loaded;notice.visibility=View.GONE;showDocuments()} }
            } catch(error:java.io.IOException){
                activity.runOnUiThread {if(!activity.isDestroyed&&generation==refreshGeneration){notice.visibility=View.VISIBLE;notice.text="보관된 문서 오류: ${error.message}"}}
            }
        }
    }

    private fun showDocuments() {
        val history=recent.all()
        val noteUris=notes.associateBy { Uri.parse("content://${activity.packageName}.pdfnotes/${it.id}") }
        val stored=notes.map { entry ->
            val uri=Uri.parse("content://${activity.packageName}.pdfnotes/${entry.id}")
            history.firstOrNull { it.uri==uri }?.copy(name=entry.name) ?: RecentDocument(uri,entry.name,entry.modifiedAt)
        }
        library.setDocuments((stored+history.filter {it.uri !in noteUris}).sortedByDescending {it.openedAt}.map { item ->
            val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.openedAt))
            val note=noteUris[item.uri]
            val noteId=item.uri.takeIf{it.authority=="${activity.packageName}.pdfnotes"}?.lastPathSegment
            val name=item.name.removePrefix("[필기] ")
            LibraryDocument(item.uri, name, note?.error?:date, { openRecent(item.copy(name=name)) }, {
                if(noteId!=null)openDocumentActions(noteId)
                else ui.confirmDelete("최근 문서 목록에서 삭제할까요? 원본 파일은 삭제되지 않습니다.") { recent.remove(item.uri); refresh() }
            }, if(noteId!=null)"$name 관리"else"$name 목록에서 삭제")
        })
    }

    private fun showSettings() {
        val root=android.widget.LinearLayout(activity).apply {orientation=android.widget.LinearLayout.VERTICAL;setPadding(ui.dp(12),0,ui.dp(12),ui.dp(8))}
        val dialog=AlertDialog.Builder(activity).setTitle("설정").setView(android.widget.ScrollView(activity).apply {addView(root)}).setNegativeButton("닫기",null).create()
        fun section(title:String){root.addView(ui.label(title,12f,color=ui.muted).apply {setPadding(ui.dp(12),ui.dp(12),0,ui.dp(4))})}
        fun item(label:String,icon:String?,danger:Boolean=false,call:()->Unit){root.addView(ui.button(label,color=if(danger)ui.danger else ui.text){dialog.dismiss();call()}.apply {
            textSize=14f;minHeight=ui.dp(52);gravity=android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
            if(icon!=null){setCompoundDrawablesRelative(ui.icon(icon,if(danger)ui.danger else ui.text),null,null,null);compoundDrawablePadding=ui.dp(12)}
        },android.widget.LinearLayout.LayoutParams(-1,-2))}
        section("화면")
        item("화면 테마","설정") {
            AlertDialog.Builder(activity).setTitle("화면 테마")
                    .setSingleChoiceItems(arrayOf("시스템 설정", "라이트", "다크"), ThemePreferences.selected(activity)) { dialog, selected ->
                        dialog.dismiss(); ThemePreferences.select(activity, selected)
                    }.setNegativeButton("취소", null).show()
        }
        root.addView(ui.label(arrayOf("시스템 설정","라이트","다크")[ThemePreferences.selected(activity)],12f,color=ui.muted).apply {setPadding(ui.dp(48),0,0,ui.dp(6))})
        section("기기 내 데이터")
        item("백업 복원","백업"){openDocumentActions(null)}
        item("최근 기록 전체 지우기","선택 삭제",true){confirmClear()}
        root.addView(ui.label("원본과 보관된 사본·필기는 유지",12f,color=ui.muted).apply {setPadding(ui.dp(12),0,ui.dp(12),ui.dp(10))})
        item("오픈소스 라이선스",null){showLicenses()}
        dialog.show()
    }

    private fun confirmClear() {
        ui.confirmDelete("최근 열기 기록을 모두 지웁니다. 외부 원본과 앱에 보관한 사본·필기는 유지됩니다.","최근 기록을 모두 지울까요?","기록 모두 지우기") { recent.clear(); refresh() }
    }

    private fun showLicenses() {
        val labels = arrayOf("출처 및 소스 안내", "LibreOffice 및 제3자 고지", "rhwp 라이선스", "rhwp 의존성 고지", "수식 문자 대응표 고지", "libexttextcat 라이선스", "rhwp 전이 의존성 고지", "OpenSymbol 글꼴 고지", "기타 글꼴 라이선스", "Redland 계열 고지", "네이티브 구성 요소 고지", "한글 수식 글꼴 고지", "Rust 표준 라이브러리 고지", "PDFBox-Android 및 포함 자료 고지", "Pretendard UI 글꼴 고지")
        AlertDialog.Builder(activity).setTitle("오픈소스 라이선스").setItems(labels) { _, index ->
            if (index == 0) {
                AlertDialog.Builder(activity).setTitle(labels[index]).setMessage(
                    "LibreOffice 26.2.6.3: MPL 2.0 및 포함 구성 요소별 라이선스\n" +
                        "원본: https://github.com/LibreOffice/core/tree/libreoffice-26.2.6.3\n" +
                        "수정 사항 및 빌드 안내: https://github.com/ghminx/MIN-VIEWER\n\n" +
                        "rhwp 0.9.0: MIT, https://github.com/edwardkim/rhwp\n" +
                        "수식 문자 대응표: kordoc, MIT\n" +
                        "Kotlin 표준 라이브러리 및 JetBrains annotations: Apache License 2.0\n\n" +
                        "LibreOffice 상세 고지에는 포함된 제3자 구성 요소와 글꼴 고지가 포함되어 있습니다."
                ).setPositiveButton("닫기", null).show()
            } else {
                val path = when (index) {
                    1 -> "licenses/libreoffice/THIRD_PARTY.html"
                    2 -> "rhwp/LICENSE"
                    3 -> "rhwp/notices/THIRD_PARTY_LICENSES.md"
                    4 -> "licenses/kordoc.txt"
                    5 -> "licenses/libreoffice/libexttextcat.txt"
                    6 -> "licenses/rhwp-transitive-notices.txt"
                    7 -> "licenses/libreoffice/opensymbol.txt"
                    8 -> "licenses/libreoffice/font-notices.txt"
                    9 -> "licenses/libreoffice/redland-notice.txt"
                    10 -> "licenses/libreoffice/native-source-notices.txt"
                    11 -> "licenses/rhwp-font-notices.txt"
                    12 -> "licenses/rust-standard-library.html"
                    13 -> "licenses/pdfbox/notices.txt"
                    else -> "licenses/pretendard.txt"
                }
                val web = WebView(activity).apply {
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    settings.defaultTextEncodingName = "UTF-8"
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) = true
                    }
                    if (index == 6 || index == 10 || index == 12) {
                        loadUrl("file:///android_asset/$path")
                    } else {
                        val text = activity.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val page = if (index == 1) text else "<html><meta charset=\"utf-8\"><body><pre style=\"white-space:pre-wrap\">" +
                            android.text.TextUtils.htmlEncode(text) + "</pre></body></html>"
                        loadDataWithBaseURL(null, page, "text/html", "UTF-8", null)
                    }
                }
                AlertDialog.Builder(activity).setTitle(labels[index]).setView(web).setPositiveButton("닫기", null).create().apply {
                    setOnDismissListener { web.destroy() }
                    show()
                }
            }
        }.setNegativeButton("닫기", null).show()
    }

    class LogoView(activity: Activity) : View(activity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val ui = UiStyle(context as Activity)
            canvas.drawColor(ui.navy)
            val density = resources.displayMetrics.density
            canvas.scale(minOf(1f, width / (210 * density)), minOf(1f, width / (210 * density)))
            canvas.save()
            canvas.translate(20 * density, (height - 28 * density) / 2)
            canvas.scale(density * 0.35f, density * 0.35f)
            paint.color = Color.WHITE
            val shapes = listOf(
                floatArrayOf(0f,80f, 0f,0f, 19f,0f, 48f,40f, 77f,0f, 96f,0f, 96f,80f, 77f,80f, 77f,33f, 48f,71f, 19f,33f, 19f,80f),
                floatArrayOf(114f,0f, 133f,0f, 133f,80f, 114f,80f),
                floatArrayOf(151f,80f, 151f,0f, 170f,0f, 214f,51f, 214f,0f, 233f,0f, 233f,80f, 214f,80f, 170f,29f, 170f,80f)
            )
            shapes.forEach { points ->
                val path = Path().apply {
                    moveTo(points[0], points[1])
                    for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
                    close()
                }
                canvas.drawPath(path, paint)
            }
            paint.color = ui.navy
            canvas.drawRect(0f, 37f, 233f, 43f, paint)
            canvas.restore()
            paint.color = Color.WHITE
            paint.typeface = resources.getFont(R.font.pretendard_regular)
            paint.textSize = 20 * density
            canvas.drawText("VIEWER", 110 * density, height / 2f + 7 * density, paint)
        }
    }
}
