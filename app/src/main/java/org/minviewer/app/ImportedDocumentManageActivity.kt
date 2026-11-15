package org.minviewer.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

class ImportedDocumentManageActivity : Activity() {
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private val store by lazy {ImportedDocumentStore(File(filesDir,"imported-documents"))}
    private lateinit var status: android.widget.TextView
    private lateinit var actions: LinearLayout
    private var pending: File?=null
    private var busy=false
    @Volatile private var canceled=false
    private val id by lazy {requireNotNull(intent.getStringExtra("import_id"))}

    override fun onCreate(savedInstanceState:Bundle?) {
        ThemePreferences.apply(this);super.onCreate(savedInstanceState)
        val ui=UiStyle(this)
        status=ui.label("문서 확인 중",14f,color=ui.text)
        actions=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        setContentView(LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(ui.dp(20),ui.dp(32),ui.dp(20),ui.dp(20))
            addView(status);addView(actions)
        })
        worker.execute {
            try {
                val item=store.get(id)
                main.post {
                    if(isFinishing||isDestroyed)return@post
                    if(item.noteId!=null) {
                        @Suppress("DEPRECATION")
                        startActivityForResult(Intent(this,PdfNotesActivity::class.java).putExtra("note_id",item.noteId).putExtra("import_id",id),2)
                    } else render(item)
                }
            } catch(error:Exception) {main.post {if(!isFinishing&&!isDestroyed)status.text="보관 문서를 확인하지 못했습니다: ${error.message}"}}
        }
    }
    private fun render(item:ImportedDocumentStore.Entry) {
        val ui=UiStyle(this);status.text=item.name;actions.removeAllViews()
        fun button(label:String,call:()->Unit) {actions.addView(ui.button(label){if(!busy)call()},LinearLayout.LayoutParams(-1,ui.dp(48)))}
        button("문서 열기") {
            val uri=Uri.parse("content://$packageName.imports/${item.id}")
            startActivity(Intent(this,MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(uri).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
        button("백업") {prepareBackup(item)}
        button("사본 삭제") {
            ui.confirmDelete("앱에 보관한 사본을 삭제합니다. 외부 원본은 유지됩니다.","보관 사본을 삭제할까요?","사본 삭제") {
                busy=true;status.text="삭제 중"
                worker.execute {
                    try {store.delete(item.id);RecentDocuments(applicationContext).remove(Uri.parse("content://$packageName.imports/${item.id}"));main.post {if(!isFinishing&&!isDestroyed){setResult(RESULT_OK);finish()}}}
                    catch(error:Exception){main.post {if(!isFinishing&&!isDestroyed){busy=false;status.text="삭제 실패: ${error.message}"}}}
                }
            }
        }
        button("닫기") {finish()}
    }
    private fun prepareBackup(item:ImportedDocumentStore.Entry) {
        busy=true;canceled=false;status.text="백업 준비 중"
        worker.execute {
            var file:File?=null
            try {
                val backup=File.createTempFile("import-backup-",".zip",cacheDir)
                file=backup
                backup.outputStream().use {store.backup(item.id,it){canceled}}
                if(canceled){backup.delete();return@execute}
                pending=backup
                main.post {
                    if(isFinishing||isDestroyed){backup.delete();return@post}
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                        .putExtra(Intent.EXTRA_TITLE,item.name.substringBeforeLast('.')+".minviewer.zip"),1)
                }
            } catch(error:Exception){file?.delete();main.post {if(!isFinishing&&!isDestroyed){busy=false;status.text="백업 실패: ${error.message}"}}}
        }
    }
    @Deprecated("Android 파일 선택 결과 처리")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==2){setResult(resultCode);finish();return}
        if(requestCode!=1)return
        val source=pending;pending=null
        val uri=data?.data
        if(resultCode!=RESULT_OK||uri==null||source==null){source?.delete();busy=false;status.text="백업을 취소했습니다";return}
        status.text="백업 저장 중"
        worker.execute {
            try {CreatedDocumentOutput.write(applicationContext.contentResolver,uri,source){canceled};main.post {if(!isFinishing&&!isDestroyed){busy=false;status.text="백업 저장 완료"}}}
            catch(error:Exception){main.post {if(!isFinishing&&!isDestroyed){busy=false;status.text="백업 저장 실패: ${error.message}"}}}
            finally {source.delete()}
        }
    }
    @Deprecated("Android 뒤로 이동 처리") override fun onBackPressed() {if(busy)canceled=true else super.onBackPressed()}
    override fun onDestroy(){canceled=true;pending?.delete();worker.shutdown();super.onDestroy()}
}
