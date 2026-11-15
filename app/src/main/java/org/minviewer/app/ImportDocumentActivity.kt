package org.minviewer.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ProgressBar
import java.io.File
import java.util.concurrent.Executors

@Suppress("DEPRECATION")
fun startDocumentImport(activity:Activity,uri:Uri?,name:String?) {
    try {
        activity.startActivityForResult(Intent(activity,ImportDocumentActivity::class.java).setData(uri)
            .putExtra("name",name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),42)
    } catch(error:SecurityException) {
        AlertDialog.Builder(activity).setMessage("원본 파일에 접근할 수 없습니다. 파일을 다시 선택해 주세요.")
            .setPositiveButton("닫기",null).show()
    }
}

class ImportDocumentActivity : Activity() {
    private data class Match(val id:String,val name:String,val note:Boolean)
    private val worker = Executors.newSingleThreadExecutor()
    private val store by lazy { ImportedDocumentStore(File(filesDir, "imported-documents")) }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var canceled = false
    private var prepared: ImportedDocumentStore.Prepared? = null
    private lateinit var status: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.apply(this)
        super.onCreate(savedInstanceState)
        val ui = UiStyle(this)
        status = ui.label("문서를 보관하는 중", 16f, color=ui.text)
        val layout = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(ui.dp(24),ui.dp(40),ui.dp(24),ui.dp(24))
            addView(status)
            addView(ProgressBar(this@ImportDocumentActivity))
            addView(ui.button("취소") { cancelImport() })
        }
        setContentView(layout)
        val uri=intent.data
        val name=intent.getStringExtra("name")
        if(uri==null || name==null) {status.text="문서 정보를 확인할 수 없습니다.";return}
        worker.execute {
            var staged: ImportedDocumentStore.Prepared? = null
            try {
                val input=contentResolver.openInputStream(uri) ?: throw java.io.IOException("문서를 읽을 수 없습니다.")
                val result=input.use {store.prepare(it,name) { canceled }}
                staged=result
                val linked=store.all().mapNotNull {it.noteId}.toSet()
                val matches=result.matches.map {Match(it.id,it.name,false)} +
                    PdfInkStore(File(filesDir,"pdf-notes")).matchingSources(result.sha256,result.bytes) {canceled}
                        .filterNot {it.id in linked}.map {Match(it.id,it.name,true)}
                if(canceled || isFinishing || isDestroyed) {store.discard(result);return@execute}
                staged=null
                main.post {
                    if(canceled || isFinishing || isDestroyed) {store.discard(result);return@post}
                    prepared=result
                    if(matches.isEmpty())saveNew(result) else chooseDuplicate(result,matches)
                }
            } catch(error: Exception) {
                staged?.let(store::discard)
                if(!canceled)main.post {if(!isFinishing&&!isDestroyed)status.text="문서를 보관하지 못했습니다: ${error.message}"}
            }
        }
    }

    private fun chooseDuplicate(result: ImportedDocumentStore.Prepared, matches:List<Match>) {
        AlertDialog.Builder(this).setTitle("이미 보관된 문서")
            .setMessage("같은 내용의 문서가 있습니다. 기존 문서를 열거나 새 사본을 추가할 수 있습니다.")
            .setPositiveButton("기존 문서 열기") { _, _ ->
                if(matches.size==1)openExisting(result,matches[0])
                else {
                    val labels=matches.map { "${it.name} · ${it.id.takeLast(8)}" }.toTypedArray()
                    AlertDialog.Builder(this).setTitle("열 문서 선택").setItems(labels) { _, index -> openExisting(result,matches[index]) }
                        .setNegativeButton("취소") { _, _ -> cancelImport() }.setOnCancelListener { cancelImport() }.show()
                }
            }
            .setNeutralButton("새 사본 추가") { _, _ -> saveNew(result) }
            .setNegativeButton("취소") { _, _ -> cancelImport() }.setOnCancelListener { cancelImport() }.show()
    }

    private fun openExisting(result: ImportedDocumentStore.Prepared, entry: Match) {
        prepared=null
        worker.execute {
            try {
                store.discard(result)
                val imported=if(entry.note)null else store.get(entry.id)
                main.post {if(!isFinishing&&!isDestroyed) {
                    if(imported!=null)finishWith(imported) else {
                        finishWithUri(Uri.parse("content://$packageName.pdfnotes/${entry.id}"),entry.name)
                    }
                }}
            } catch(error:Exception) {
                main.post {if(!isFinishing&&!isDestroyed)status.text="보관 문서를 열지 못했습니다: ${error.message}"}
            }
        }
    }

    private fun saveNew(result: ImportedDocumentStore.Prepared) {
        prepared=null
        worker.execute {
            try {
                if(canceled) {store.discard(result);return@execute}
                val entry=store.commit(result)
                if(canceled) {store.delete(entry.id);return@execute}
                main.post {if(!isFinishing&&!isDestroyed)finishWith(entry)}
            } catch(error: Exception) {
                store.discard(result)
                main.post {if(!isFinishing&&!isDestroyed)status.text="문서를 보관하지 못했습니다: ${error.message}"}
            }
        }
    }

    private fun finishWith(entry: ImportedDocumentStore.Entry) {
        val authority=if(entry.noteId==null)"$packageName.imports" else "$packageName.pdfnotes"
        val id=entry.noteId ?: entry.id
        finishWithUri(Uri.parse("content://$authority/$id"),entry.name)
    }
    private fun finishWithUri(uri:Uri,name:String) {
        val recent=RecentDocuments(applicationContext)
        intent.data?.takeIf {it.authority!="$packageName.imports" && it.authority!="$packageName.pdfnotes"}?.let(recent::remove)
        recent.add(uri,name)
        setResult(RESULT_OK,Intent().setData(uri).putExtra("name",name))
        finish()
    }
    private fun cancelImport() {canceled=true;finish()}
    @Deprecated("Android 뒤로 이동 처리") override fun onBackPressed() {cancelImport()}
    override fun onDestroy() {
        canceled=true
        prepared?.let {value->worker.execute {store.discard(value)}}
        prepared=null
        worker.shutdown()
        super.onDestroy()
    }
}
