package org.minviewer.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import java.io.IOException
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class PdfNotesActivity : Activity() {
    companion object {
        private val outputName = Regex("note-(backup-[0-9a-f-]{36}\\.zip|export-[0-9a-f-]{36}\\.pdf)")
    }
    private val store by lazy { PdfInkStore(File(filesDir, "pdf-notes")) }
    private lateinit var status: TextView
    private lateinit var actions: LinearLayout
    private lateinit var heading: TextView
    private class Session {
        var owner: PdfNotesActivity? = null
        var items = emptyList<PdfNoteEntry>()
        var message = ""
        var busy = false
        var pickerOpen = false
        var pendingOutput: File? = null
        var outputTitle = "필기 문서.minviewer.zip"
        var outputMime = "application/zip"
        var mergeItem: PdfNoteEntry? = null
        var initialAction: String? = null
        @Volatile var canceled = false
        @Volatile var abandoned = false
    }
    private data class Result(val message: String, val output: File? = null, val title: String? = null, val mime: String = "application/zip", val mergeItem: PdfNoteEntry? = null)
    private lateinit var session: Session
    private val items get() = session.items
    private lateinit var cancelButton: Button
    private val dark by lazy { ThemePreferences.isDark(this) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.apply(this)
        theme.applyStyle(R.style.MinViewerDocumentActions,true)
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        session = (lastNonConfigurationInstance as? Session) ?: Session().apply {
            initialAction=if(savedInstanceState==null)intent.getStringExtra("note_action")else null
            savedInstanceState?.getString("pendingOutput")?.takeIf { it.matches(outputName) }?.let {
                pendingOutput = File(cacheDir, it).takeIf(File::isFile)
            }
            pickerOpen = savedInstanceState?.getBoolean("pickerOpen") ?: false
            outputTitle = savedInstanceState?.getString("outputTitle") ?: outputTitle
            outputMime = savedInstanceState?.getString("outputMime") ?: outputMime
            if (savedInstanceState?.getBoolean("busy") == true) message = "이전 작업이 중단되었습니다. 필요한 작업을 다시 실행해 주세요."
        }
        session.owner = this
        val ui = UiStyle(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20),dp(12),dp(20),dp(20))
        }
        val header=LinearLayout(this).apply {gravity=android.view.Gravity.CENTER_VERTICAL}
        heading=ui.label("문서 관리",18f,true).apply {maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END}
        header.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
        header.addView(ui.button("문서 관리 닫기","닫기"){leave()},LinearLayout.LayoutParams(dp(48),dp(48)))
        root.addView(header)
        status = ui.label("", 13f, color = ui.muted).apply { setPadding(0, dp(8), 0, dp(16)) }
        root.addView(status)
        actions=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        root.addView(actions)
        cancelButton = ui.button("작업 취소") { session.canceled = true; status.text = "취소하는 중" }
        root.addView(cancelButton)
        setContentView(android.widget.ScrollView(this).apply {addView(root)})
        window.setLayout(minOf(dp(480),resources.displayMetrics.widthPixels-dp(32)),-2)
        setFinishOnTouchOutside(false)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (!session.busy && !session.pickerOpen && session.pendingOutput == null) refresh()
    }

    private fun render() {
        val ui=UiStyle(this)
        val noteId=intent.getStringExtra("note_id")
        val item=items.firstOrNull {it.id==noteId}
        if(item!=null&&item.error==null&&!session.busy&&!session.pickerOpen&&session.pendingOutput==null){
            val initial=session.initialAction;session.initialAction=null
            if(initial=="export"){preparePdf(item);return}
            if(initial=="backup"){prepareBackup(item);return}
        }
        heading.text=item?.name?.removePrefix("[필기] ")?:if(noteId==null)"백업 복원"else"문서 관리"
        status.text = session.message.ifEmpty { item?.error ?: if(noteId==null)"문서와 필기가 포함된 MIN VIEWER 백업을 선택해 주세요."else if(item==null)"보관된 문서를 찾을 수 없습니다."else"원본 문서는 유지하고 사본을 내보내거나 백업할 수 있습니다." }
        status.setTextColor(if(session.message.contains("실패")||item?.error!=null) ui.danger else ui.muted)
        cancelButton.visibility = if (session.busy) View.VISIBLE else View.GONE
        actions.removeAllViews()
        fun action(label:String,call:()->Unit){actions.addView(ui.button(label){call()}.apply {
            isEnabled=!session.busy&&!session.pickerOpen
            background=android.graphics.drawable.LayerDrawable(arrayOf(ui.rounded(ui.surface,10,ui.line),background))
            if(label=="사본·필기 삭제")setTextColor(ui.danger)
        },LinearLayout.LayoutParams(-1,dp(48)).apply {bottomMargin=dp(8)})}
        if(noteId==null)action("백업 복원"){pickBackup()}
        if(item!=null){
            if(item.error==null){action("문서 열기"){open(item)};action("PDF 내보내기"){preparePdf(item)};action("백업"){prepareBackup(item)}}
            action("사본·필기 삭제"){delete(item)}
        }
        session.mergeItem?.let { item ->
            session.mergeItem = null
            AlertDialog.Builder(this).setTitle("기존 주석을 표시 형태로 병합할까요?")
                .setMessage("원본은 보존됩니다. 출력 사본에서는 필기와 겹치는 기존 주석의 표시를 유지하지만 해당 주석의 편집·동영상 재생 기능은 제외됩니다. 글자·이미지를 그림으로 바꾸지는 않습니다. 입력란 위의 필기는 지원하지 않습니다.")
                .setNegativeButton("취소", null).setPositiveButton("표시용 사본 만들기") { _, _ -> preparePdf(item, true) }.show()
        }
        val file = session.pendingOutput
        if (file != null && !session.pickerOpen && !session.busy) {
            session.pickerOpen = true
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(session.outputMime).putExtra(Intent.EXTRA_TITLE, session.outputTitle), 1)
        }
    }

    private fun work(message: String, action: () -> Result) {
        if (session.busy) return
        val current = session
        val storage = store
        current.busy = true; current.canceled = false; current.message = message; render()
        PdfInkStore.io.execute {
            var result = try { action() } catch (error: Exception) { Result("작업 실패: ${error.message}") }
            val loaded = try { storage.all() } catch (error: Exception) { result = result.copy(message = "${result.message}\n보관함 오류: ${error.message}"); current.items }
            Handler(Looper.getMainLooper()).post {
                current.busy = false
                if (current.abandoned) { result.output?.delete(); return@post }
                if (current.canceled && result.output != null) {
                    val cleaned = requireNotNull(result.output).delete()
                    result = Result(if (cleaned) "작업을 취소했습니다" else "작업 취소 · 임시 파일 정리 필요")
                }
                current.items = loaded; current.message = result.message; current.pendingOutput = result.output
                result.title?.let { current.outputTitle = it; current.outputMime = result.mime }
                if (!current.canceled) current.mergeItem = result.mergeItem
                current.owner?.render()
            }
        }
    }

    private fun refresh() {
        val storage = store
        val cache = cacheDir
        val keep = session.pendingOutput
        val previous = session.message
        work("문서 불러오는 중") {
            val cleaned = storage.cleanup()
            val files = cache.listFiles() ?: throw IOException("임시 폴더를 읽을 수 없습니다.")
            val cacheCleaned = files.filter { it.name.matches(outputName) && it != keep }.map { it.delete() }.all { it }
            Result(if (cleaned && cacheCleaned) previous else "임시 파일 정리가 필요합니다. 보관된 문서는 유지됩니다.")
        }
    }

    private fun delete(item: PdfNoteEntry) {
        if (session.busy || session.pickerOpen) return
        UiStyle(this).confirmDelete("앱에 보관된 문서 사본과 필기가 삭제됩니다. 외부 원본은 유지됩니다. 백업하지 않은 필기는 복구할 수 없습니다.",
            "보관 사본과 필기를 삭제할까요?", "사본·필기 삭제") {
            val storage = store; val history = RecentDocuments(applicationContext)
            val uri = Uri.parse("content://$packageName.pdfnotes/${item.id}")
            work("필기 문서 삭제 중") {
                val cleaned = storage.delete(item.id); history.remove(uri)
                Result(if (cleaned) "필기 문서 삭제 완료" else "보관함에서 삭제됨 · 일부 임시 파일 정리 필요")
            }
        }
    }

    private fun preparePdf(item: PdfNoteEntry, mergeAnnotations: Boolean = false) {
        val storage = store; val current = session; val cache = cacheDir
        val name = item.name.substringBeforeLast('.').replace(Regex("[\\\\/\\p{Cntrl}]"), "_").take(80) + "-annotated.pdf"
        work("PDF 준비 중 · 원본 글자·이미지를 보존하고 필기를 추가합니다") {
            val file = File(cache, "note-export-${java.util.UUID.randomUUID()}.pdf")
            try {
                storage.exportPdf(item.id, file, { current.canceled || current.abandoned }, mergeAnnotations)
                Result("저장 위치를 선택해 주세요 · 필기 재편집용 보관에는 백업을 사용해 주세요", file, name, "application/pdf")
            } catch (error: PdfInkExportNeedsMerge) {
                file.delete(); Result(error.message ?: "기존 주석 병합 확인 필요", mergeItem = item)
            } catch (error: Exception) { file.delete(); throw error }
        }
    }

    private fun prepareBackup(item: PdfNoteEntry) {
        val storage = store; val current = session; val cache = cacheDir
        val name = item.name.substringBeforeLast('.').replace(Regex("[\\\\/\\p{Cntrl}]"), "_").take(80) + ".minviewer.zip"
        work("백업 준비 중 · 원본 문서·필기 배경·필기를 함께 보관합니다") {
            val file = File(cache, "note-backup-${java.util.UUID.randomUUID()}.zip")
            if (!file.createNewFile()) throw IOException("백업 준비 파일을 만들 수 없습니다.")
            try {
                file.outputStream().use { storage.backup(item.id, it) { current.canceled || current.abandoned } }
                Result("저장 위치를 선택해 주세요", file, name)
            } catch (error: Exception) { file.delete(); throw error }
        }
    }

    private fun pickBackup() {
        session.pickerOpen = true
        render()
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")), 2)
    }

    @Deprecated("Android 파일 선택 결과 처리")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        session.pickerOpen = false
        val uri = data?.data
        val file = session.pendingOutput
        session.pendingOutput = null
        if (resultCode != RESULT_OK || uri == null) {
            val cleaned = file == null || file.delete()
            session.message = if (cleaned) "작업을 취소했습니다" else "작업 취소 · 임시 파일 정리 필요"
            render(); return
        }
        val resolver = applicationContext.contentResolver
        val current = session; val storage = store
        if (requestCode == 1) {
            if (file == null) { session.message = "준비 파일이 없습니다. 내보내기나 백업을 다시 실행해 주세요."; render(); return }
            val kind = if (session.outputMime == "application/pdf") "PDF" else "백업"
            work("$kind 저장 중") {
                try {
                    CreatedDocumentOutput.write(resolver, uri, file) { current.canceled || current.abandoned }
                    Result(if (file.delete()) "$kind 저장 완료" else "$kind 저장 완료 · 임시 파일 정리 필요")
                } catch (error: Exception) {
                    file.delete()
                    throw IOException("$kind 저장 실패: ${error.message}\n선택한 파일은 자동 삭제하지 않았습니다. 일부만 기록된 출력은 저장 위치에서 확인해 주세요.", error)
                }
            }
        } else if (requestCode == 2) {
            work("백업 검사·복원 중") {
                val input = resolver.openInputStream(uri) ?: throw IOException("백업 파일을 읽을 수 없습니다.")
                val restored = input.use { storage.restore(it) { current.canceled || current.abandoned } }
                RecentDocuments(applicationContext).add(Uri.parse("content://$packageName.pdfnotes/${restored.id}"),restored.name)
                Result("복원 완료: ${restored.name}")
            }
        }
    }

    private fun leave() { if (session.busy) { session.canceled = true; status.text = "취소하는 중" } else finish() }

    @Deprecated("Android 뒤로 이동 처리")
    override fun onBackPressed() { leave() }

    @Deprecated("Android 구성 변경 상태 유지")
    override fun onRetainNonConfigurationInstance(): Any = session

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingOutput", session.pendingOutput?.name)
        outState.putBoolean("pickerOpen", session.pickerOpen)
        outState.putBoolean("busy", session.busy)
        outState.putString("outputTitle", session.outputTitle)
        outState.putString("outputMime", session.outputMime)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (session.owner === this) session.owner = null
        if (isFinishing) {
            session.abandoned = true; session.canceled = true
            if (!session.busy) session.pendingOutput?.delete()
        }
        super.onDestroy()
    }

    private fun open(item: PdfNoteEntry) {
        if (session.busy || session.pickerOpen) return
        if (item.error != null) { AlertDialog.Builder(this).setMessage(item.error).setPositiveButton("닫기", null).show(); return }
        val uri = Uri.parse("content://$packageName.pdfnotes/${item.id}")
        val previous = RecentDocuments(this).all().firstOrNull { it.uri == uri }
        startActivity(Intent(this, PdfActivity::class.java).setData(uri).putExtra("name", item.name)
            .putExtra("restore_page", previous?.page ?: 0).putExtra("restore_mode", previous?.mode?.toIntOrNull() ?: 1)
            .putExtra("restore_zoom", previous?.zoom ?: 1f))
        finish()
    }

}
