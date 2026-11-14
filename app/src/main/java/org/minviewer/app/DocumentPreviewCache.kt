package org.minviewer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors

/** 열린 문서의 작은 첫 페이지 표지 저장. 홈에서 문서 엔진 초기화 제외. */
object DocumentPreviewCache {
    private val io = Executors.newSingleThreadExecutor()
    private val memory = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private fun key(uri: Uri) = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    fun copy(context: Context, from: Uri, to: Uri) {
        io.execute {
            val bitmap=memory.get(key(from))?:BitmapFactory.decodeFile(File(context.cacheDir,"document-previews/${key(from)}.jpg").path)
            if(bitmap!=null)persist(File(context.cacheDir,"document-previews"),key(to),bitmap)
        }
    }
    fun record(context: Context, uri: Uri, page: Bitmap) {
        val scale = minOf(180f / page.width, 480f / page.height)
        val small = Bitmap.createScaledBitmap(page, (page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), true)
        val dir = File(context.cacheDir, "document-previews")
        val name = key(uri)
        io.execute { persist(dir,name,small) }
    }
    private fun persist(dir: File, name: String, small: Bitmap) {
        try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("미리보기 캐시 생성 실패")
            val atomic = AtomicFile(File(dir, "$name.jpg")); val out = atomic.startWrite()
            try { if (!small.compress(Bitmap.CompressFormat.JPEG, 82, out)) throw IOException("미리보기 압축 실패");atomic.finishWrite(out) }
            catch (error: IOException) { atomic.failWrite(out);throw error }
            memory.put(name, small)
            dir.listFiles()?.filter { it.extension == "jpg" }?.sortedByDescending { it.lastModified() }?.drop(24)?.forEach { it.delete() }
        } catch (_: IOException) { /* 읽기를 차단하지 않는 재생성 가능한 표지 캐시 */ }
    }
    fun bind(context: Context, uri: Uri, image: ImageView) {
        val name = key(uri);image.tag = name
        memory.get(name)?.let { image.setImageBitmap(it); return }
        val file = File(context.cacheDir,"document-previews/$name.jpg")
        io.execute {
            val bitmap = BitmapFactory.decodeFile(file.path) ?: return@execute
            memory.put(name, bitmap)
            image.post { if (image.isAttachedToWindow && image.tag == name) image.setImageBitmap(bitmap) }
        }
    }
}
