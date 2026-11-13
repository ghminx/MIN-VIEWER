package org.minviewer.app

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

data class InkPoint(val x: Float, val y: Float, val pressure: Float, val time: Long)
data class InkStroke(val page: Int, val width: Float, val color: Int, val points: List<InkPoint>)
data class PdfInkNote(val id: String, val name: String, val pages: Int, val strokes: List<InkStroke>)

class PdfInkStore(private val root: File) {
    companion object { private val storageLock = Any() }
    private fun directory(id: String): File {
        require(UUID.fromString(id).toString() == id) { "잘못된 필기 문서 ID" }
        return File(root, id)
    }

    fun original(id: String) = File(directory(id), "original.pdf")

    fun create(input: InputStream, name: String, pages: Int): PdfInkNote {
        val id = UUID.randomUUID().toString()
        val dir = directory(id)
        if (!dir.mkdirs()) throw IOException("필기 문서 폴더를 만들 수 없습니다.")
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            original(id).outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    hash.update(buffer, 0, size); out.write(buffer, 0, size)
                }
                out.fd.sync()
            }
            File(dir, "sha256").writeText(hash.digest().joinToString("") { "%02x".format(it) })
            return PdfInkNote(id, name, pages, emptyList()).also { save(it) }
        } catch (error: Exception) {
            dir.deleteRecursively()
            throw error
        }
    }

    fun save(note: PdfInkNote) = synchronized(storageLock) {
        val json = JSONObject().put("version", 1).put("name", note.name).put("pages", note.pages)
        val strokes = JSONArray()
        note.strokes.forEach { stroke ->
            require(stroke.page in 0 until note.pages && stroke.width > 0 && stroke.width.isFinite())
            val points = JSONArray()
            stroke.points.forEach { p ->
                require(p.x.isFinite() && p.y.isFinite() && p.x in 0f..1f && p.y in 0f..1f && p.pressure.isFinite())
                points.put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble()).put(p.pressure.toDouble()).put(p.time))
            }
            require(stroke.points.isNotEmpty())
            strokes.put(JSONObject().put("page", stroke.page).put("width", stroke.width.toDouble()).put("color", stroke.color).put("points", points))
        }
        json.put("strokes", strokes)
        val file = AtomicFile(File(directory(note.id), "note.json"))
        val out = file.startWrite()
        try {
            out.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(out)
        } catch (error: Exception) { file.failWrite(out); throw error }
    }

    fun load(id: String): PdfInkNote = synchronized(storageLock) {
        val json = JSONObject(String(AtomicFile(File(directory(id), "note.json")).readFully(), Charsets.UTF_8))
        require(json.getInt("version") == 1) { "미지원 필기 데이터 버전" }
        val pages = json.getInt("pages")
        val array = json.getJSONArray("strokes")
        val note = PdfInkNote(id, json.getString("name"), pages, (0 until array.length()).map { i ->
            val s = array.getJSONObject(i); val p = s.getJSONArray("points")
            InkStroke(s.getInt("page"), s.getDouble("width").toFloat(), s.getInt("color"), (0 until p.length()).map { j ->
                val point = p.getJSONArray(j)
                InkPoint(point.getDouble(0).toFloat(), point.getDouble(1).toFloat(), point.getDouble(2).toFloat(), point.getLong(3))
            })
        })
        require(pages > 0 && note.strokes.all { s -> s.page in 0 until pages && s.width.isFinite() && s.width > 0 && s.points.isNotEmpty() && s.points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f && it.pressure.isFinite() } }) { "필기 데이터 손상" }
        note
    }
}
