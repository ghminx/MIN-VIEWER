package org.minviewer.app

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import android.util.JsonReader
import java.util.concurrent.Executors
import android.util.JsonToken
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.OutputStream
import java.io.InterruptedIOException
import java.nio.charset.CodingErrorAction
import java.io.InputStreamReader
import java.io.StringReader
import java.security.DigestInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.CheckedInputStream
import java.util.zip.CRC32
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class InkPoint(val x: Float, val y: Float, val pressure: Float, val time: Long)
data class InkStroke @JvmOverloads constructor(val page: Int, val width: Float, val color: Int, val points: List<InkPoint>, val tool: String = "pen")
data class PdfInkNote(val id: String, val name: String, val pages: Int, val strokes: List<InkStroke>)
data class PdfNoteEntry(val id: String, val name: String, val pages: Int, val modifiedAt: Long, val error: String? = null)

class PdfInkStore(private val root: File) {
    companion object {
        private val storageLock = Any()
        val io = Executors.newSingleThreadExecutor()
        private val noteId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private const val PDF_LIMIT = 512L * 1024 * 1024
        private const val INK_LIMIT = 8L * 1024 * 1024
        private const val ARCHIVE_LIMIT = 1034L * 1024 * 1024
    }
    private fun directory(id: String): File {
        require(UUID.fromString(id).toString() == id) { "잘못된 필기 문서 ID" }
        return File(root, id)
    }

    fun original(id: String) = File(directory(id), "original.pdf")

    fun createPresentation(source: File, snapshot: File, name: String, pages: Int, engineBuild: String, canceled: () -> Boolean): PdfInkNote = synchronized(storageLock) {
        require(name.endsWith(".pptx", true) && source.length() in 1..PDF_LIMIT) { "지원하지 않는 PPTX 사본 또는 크기입니다." }
        require(snapshot.length() in 1..PDF_LIMIT && pages > 0) { "PPT 필기 배경의 크기 또는 페이지 수가 잘못되었습니다." }
        require(engineBuild.isNotBlank() && engineBuild.length <= 256)
        ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                require(renderer.pageCount == pages) { "슬라이드와 필기 배경의 페이지 수가 다릅니다." }
            }
        }
        // Office 단일 작업 큐의 준비 폴더만 정리하며 다른 프로세스의 복원 폴더와 분리
        if(root.exists()) {
            val files=root.listFiles()?:throw IOException("필기 보관함을 읽을 수 없습니다.")
            files.filter {it.name.startsWith(".presentation-")&&noteId.matches(it.name.removePrefix(".presentation-"))}.forEach {
                if(!it.deleteRecursively())throw IOException("중단된 PPT 필기 준비 파일을 정리할 수 없습니다.")
            }
        }
        val stage = File(root, ".presentation-${UUID.randomUUID()}")
        try {
            val note = snapshot.inputStream().use { PdfInkStore(stage).create(it, name, pages) }
            val dir = File(stage, note.id)
            val pptx = File(dir, "source.pptx")
            source.inputStream().use { input -> pptx.outputStream().use { output ->
                copyBounded(input, output, PDF_LIMIT, canceled); output.fd.sync()
            } }
            val metadata = JSONObject().put("format", "pptx").put("bytes", pptx.length())
                .put("sha256", hash(pptx, canceled)).put("engineBuild", engineBuild).put("policy", "all-slides-vector-pdf-v1")
            File(dir, "source.json").outputStream().use { it.write(metadata.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
            if (canceled()) throw InterruptedIOException("작업을 취소했습니다.")
            if (!dir.renameTo(directory(note.id))) throw IOException("필기 문서를 확정할 수 없습니다.")
            note
        } finally { stage.deleteRecursively() }
    }

    private fun presentationMetadata(dir: File, canceled: () -> Boolean): JSONObject? {
        val metadata = File(dir, "source.json")
        if (!metadata.isFile) {
            require(!File(dir, "source.pptx").exists()) { "PPTX 사본의 정보가 없습니다." }
            return null
        }
        checkJson(metadata, 16L * 1024, canceled)
        val json = JSONObject(metadata.readText(Charsets.UTF_8))
        val source = File(dir, "source.pptx")
        require(json.getString("format") == "pptx" && json.getString("policy") == "all-slides-vector-pdf-v1" &&
            json.getString("engineBuild").isNotBlank() && json.getString("engineBuild").length <= 256 &&
            source.length() in 1..PDF_LIMIT && source.length() == json.getLong("bytes") && hash(source, canceled) == json.getString("sha256")) { "PPTX 원본 사본이 없거나 변경되었습니다." }
        return json
    }

    @JvmOverloads
    fun exportPdf(id: String, target: File, canceled: () -> Boolean, mergeAnnotations: Boolean = false) = synchronized(storageLock) {
        require(!target.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "보관된 문서를 출력 대상으로 사용할 수 없습니다." }
        val note = load(id)
        val source = original(id)
        require(hash(source, canceled) == File(directory(id), "sha256").readText(Charsets.UTF_8)) { "원본 사본이 변경되어 내보낼 수 없습니다." }
        PdfInkExporter.write(source, note, target, canceled, mergeAnnotations)
    }

    fun cleanup(): Boolean = synchronized(storageLock) {
        if (!root.exists()) return@synchronized true
        val files = root.listFiles() ?: throw IOException("필기 보관함을 읽을 수 없습니다.")
        files.filter { (it.name.startsWith(".restore-") && noteId.matches(it.name.removePrefix(".restore-"))) ||
            (it.name.startsWith(".deleted-") && noteId.matches(it.name.removePrefix(".deleted-"))) }
            .map { it.deleteRecursively() }.all { it }
    }

    fun delete(id: String): Boolean = synchronized(storageLock) {
        val dir = directory(id)
        val trash = File(root, ".deleted-${UUID.randomUUID()}")
        if (!dir.renameTo(trash)) throw IOException("필기 문서를 삭제할 수 없습니다.")
        trash.deleteRecursively()
    }

    private fun copyBounded(input: InputStream, output: OutputStream, limit: Long, canceled: () -> Boolean): Long {
        var count = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            if (canceled()) throw InterruptedIOException("작업을 취소했습니다.")
            val size = input.read(buffer)
            if (size < 0) return count
            count += size
            require(count <= limit) { "문서 또는 백업 항목의 크기 제한을 초과했습니다." }
            output.write(buffer, 0, size)
        }
    }

    private fun hash(file: File, canceled: () -> Boolean): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (canceled()) throw InterruptedIOException("작업을 취소했습니다.")
                val size = input.read(buffer)
                if (size < 0) break
                hash.update(buffer, 0, size)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private fun checkJson(file: File, limit: Long, canceled: () -> Boolean) {
        require(file.length() <= limit) { "필기 정보의 크기 제한을 초과했습니다." }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        JsonReader(InputStreamReader(file.inputStream(), decoder)).use { checkJson(it, canceled) }
    }

    private fun checkJson(reader: JsonReader, canceled: () -> Boolean) {
        val fields = ArrayList<MutableSet<String>?>()
        var values = 0
        while (reader.peek() != JsonToken.END_DOCUMENT) {
            if (canceled()) throw InterruptedIOException("작업을 취소했습니다.")
            require(++values <= 750000) { "필기 정보의 항목 수 제한을 초과했습니다." }
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> { reader.beginObject(); fields.add(mutableSetOf()) }
                JsonToken.BEGIN_ARRAY -> { reader.beginArray(); fields.add(null) }
                JsonToken.END_OBJECT -> { reader.endObject(); fields.removeAt(fields.lastIndex) }
                JsonToken.END_ARRAY -> { reader.endArray(); fields.removeAt(fields.lastIndex) }
                JsonToken.NAME -> {
                    val name = reader.nextName()
                    require(name.length <= 256 && requireNotNull(fields.last()).add(name)) { "필기 정보에 중복되거나 잘못된 필드가 있습니다." }
                }
                JsonToken.STRING -> require(reader.nextString().length <= 4096) { "필기 정보의 문자열이 너무 깁니다." }
                JsonToken.NUMBER -> require(reader.nextString().length <= 64) { "필기 정보의 숫자가 너무 깁니다." }
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NULL -> reader.nextNull()
                else -> throw IOException("잘못된 필기 정보입니다.")
            }
            require(fields.size <= 16) { "필기 정보의 중첩 제한을 초과했습니다." }
        }
    }

    private fun checkZipDirectory(file: File) {
        val tail = ByteArray(minOf(file.length(), 65557L).toInt())
        RandomAccessFile(file, "r").use { it.seek(file.length() - tail.size); it.readFully(tail) }
        val data = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        for (i in tail.size - 22 downTo 0) {
            if (data.getInt(i) != 0x06054b50 || (data.getShort(i + 20).toInt() and 0xffff) != tail.size - i - 22) continue
            val size = data.getInt(i + 12).toLong() and 0xffffffffL
            val offset = data.getInt(i + 16).toLong() and 0xffffffffL
            // ZipFile 로딩 전 PDF 3개 또는 PPTX 4개 항목의 제한 확인
            require(data.getShort(i + 4).toInt() == 0 && data.getShort(i + 6).toInt() == 0 &&
                data.getShort(i + 8).toInt() in 3..4 && data.getShort(i + 10) == data.getShort(i + 8) &&
                size in 1..4096 && offset + size == file.length() - tail.size + i) { "백업 ZIP의 항목 수 또는 구조가 잘못되었습니다." }
            return
        }
        throw IOException("백업 ZIP의 끝부분이 없거나 손상되었습니다.")
    }

    @JvmOverloads
    fun backup(id: String, output: OutputStream, canceled: () -> Boolean = { false }) = synchronized(storageLock) {
        val ink = File(directory(id), "note.json")
        AtomicFile(ink).openRead().close()
        checkJson(ink, INK_LIMIT, canceled)
        load(id)
        val pdf = original(id)
        require(pdf.length() in 1..PDF_LIMIT) { "원본 PDF의 크기 제한을 초과했습니다." }
        val pdfHash = hash(pdf, canceled)
        require(pdfHash == File(directory(id), "sha256").readText(Charsets.UTF_8)) { "원본 사본이 변경되어 백업할 수 없습니다." }
        val presentation = presentationMetadata(directory(id), canceled)
        val manifest = JSONObject().put("format", "MIN_VIEWER_PDF_NOTE").put("version", if(presentation==null)1 else 2)
            .put("pdfBytes", pdf.length()).put("inkBytes", ink.length()).put("pdfSha256", pdfHash).put("inkSha256", hash(ink, canceled))
        if (presentation != null) manifest.put("presentation", presentation)
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
            val files = mutableListOf("original.pdf" to pdf, "note.json" to ink)
            if (presentation != null) files.add("source.pptx" to File(directory(id), "source.pptx"))
            files.forEach { (name, file) ->
                zip.putNextEntry(ZipEntry(name)); file.inputStream().use { copyBounded(it, zip, file.length(), canceled) }; zip.closeEntry()
            }
        }
    }

    @JvmOverloads
    fun restore(input: InputStream, canceled: () -> Boolean = { false }): PdfInkNote = synchronized(storageLock) {
        val stage = File(root, ".restore-${UUID.randomUUID()}")
        if (!stage.mkdirs()) throw IOException("복원 준비 폴더를 만들 수 없습니다.")
        try {
            val archive = File(stage, "backup.zip")
            input.use { source -> archive.outputStream().use { target -> copyBounded(source, target, ARCHIVE_LIMIT, canceled) } }
            checkZipDirectory(archive)
            val id = UUID.randomUUID().toString()
            val dir = File(stage, id)
            if (!dir.mkdir()) throw IOException("복원 문서 폴더를 만들 수 없습니다.")
            val limits = mapOf("manifest.json" to 16L * 1024, "original.pdf" to PDF_LIMIT, "note.json" to INK_LIMIT, "source.pptx" to PDF_LIMIT)
            ZipFile(archive).use { zip ->
                val seen = mutableSetOf<String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val limit = limits[entry.name] ?: throw IOException("백업에 허용되지 않은 파일이 있습니다.")
                    require(!entry.isDirectory && seen.add(entry.name) && entry.size in 0..limit) { "백업 항목이 중복되거나 잘못되었습니다." }
                    val crc = CRC32()
                    val size = CheckedInputStream(zip.getInputStream(entry), crc).use { source ->
                        File(dir, entry.name).outputStream().use { target ->
                            copyBounded(source, target, limit, canceled).also { target.fd.sync() }
                        }
                    }
                    require(size == entry.size && crc.value == entry.crc) { "백업 파일이 손상되었습니다." }
                }
                require(seen.containsAll(setOf("manifest.json", "original.pdf", "note.json"))) { "백업에 필요한 파일이 없습니다." }
            }
            val manifestFile = File(dir, "manifest.json")
            checkJson(manifestFile, 16L * 1024, canceled)
            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            val version = manifest.getInt("version")
            require(manifest.getString("format") == "MIN_VIEWER_PDF_NOTE" && version in 1..2) { "지원하지 않는 백업 형식입니다." }
            require((version == 2) == manifest.has("presentation") && (version == 2) == File(dir,"source.pptx").exists()) { "PPTX 백업 구성 정보가 다릅니다." }
            if (version == 2) {
                File(dir,"source.json").outputStream().use { it.write(manifest.getJSONObject("presentation").toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
                presentationMetadata(dir,canceled)
            }
            val pdf = File(dir, "original.pdf"); val ink = File(dir, "note.json")
            require(pdf.length() == manifest.getLong("pdfBytes") && ink.length() == manifest.getLong("inkBytes")) { "백업 크기 정보가 다릅니다." }
            val pdfHash = hash(pdf, canceled)
            require(pdfHash == manifest.getString("pdfSha256") && hash(ink, canceled) == manifest.getString("inkSha256")) { "백업의 해시가 일치하지 않습니다." }
            checkJson(ink, INK_LIMIT, canceled)
            val restored = PdfInkStore(stage).load(id)
            require(version == 1 || restored.name.endsWith(".pptx",true)) { "PPTX 필기 문서 이름이 잘못되었습니다." }
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use {
                    require(it.pageCount == restored.pages) { "PDF 페이지 수와 필기 정보가 다릅니다." }
                }
            }
            File(dir, "sha256").writeText(pdfHash, Charsets.UTF_8)
            if (!manifestFile.delete()) throw IOException("복원 준비 파일을 정리할 수 없습니다.")
            if (canceled()) throw InterruptedIOException("작업을 취소했습니다.")
            if (!dir.renameTo(directory(id))) throw IOException("복원 문서를 확정할 수 없습니다.")
            restored
        } finally {
            // 확정된 문서를 유지하고 잔여 준비 파일은 다음 보관함 실행에서 재정리
            stage.deleteRecursively()
        }
    }

    fun all(): List<PdfNoteEntry> = synchronized(storageLock) {
        if (!root.exists()) return@synchronized emptyList()
        val dirs = root.listFiles() ?: throw IOException("필기 보관함을 읽을 수 없습니다.")
        dirs.filter { it.isDirectory && noteId.matches(it.name) }.map { dir ->
            try {
                var name: String? = null
                var pages: Int? = null
                var version: Int? = null
                JsonReader(AtomicFile(File(dir, "note.json")).openRead().reader(Charsets.UTF_8)).use { reader ->
                    reader.beginObject()
                    while (reader.hasNext()) when (reader.nextName()) {
                        "name" -> name = reader.nextString()
                        "pages" -> pages = reader.nextInt()
                        "version" -> version = reader.nextInt()
                        else -> reader.skipValue()
                    }
                    reader.endObject()
                }
                require(version in 1..2 && requireNotNull(pages) > 0 && original(dir.name).isFile)
                PdfNoteEntry(dir.name, requireNotNull(name), requireNotNull(pages), File(dir, "note.json").lastModified())
            } catch (_: Exception) {
                PdfNoteEntry(dir.name, "손상된 필기 ${dir.name.take(8)}", 0, dir.lastModified(), "필기 정보를 읽을 수 없습니다.")
            }
        }.sortedByDescending { it.modifiedAt }
    }

    fun create(input: InputStream, name: String, pages: Int): PdfInkNote = synchronized(storageLock) {
        val id = UUID.randomUUID().toString()
        val dir = directory(id)
        if (!dir.mkdirs()) throw IOException("필기 문서 폴더를 만들 수 없습니다.")
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            original(id).outputStream().use { out ->
                val size = copyBounded(DigestInputStream(input, hash), out, PDF_LIMIT) { false }
                require(size > 0) { "원본 PDF가 비어 있습니다." }
                out.fd.sync()
            }
            File(dir, "sha256").writeText(hash.digest().joinToString("") { "%02x".format(it) })
            PdfInkNote(id, name, pages, emptyList()).also { save(it) }
        } catch (error: Exception) {
            dir.deleteRecursively()
            throw error
        }
    }

    fun save(note: PdfInkNote) = synchronized(storageLock) {
        require(original(note.id).isFile) { "필기 문서의 사본이 없습니다. 삭제된 문서는 다시 저장할 수 없습니다." }
        require(original(note.id).length() in 1..PDF_LIMIT) { "원본 PDF의 크기 제한(512MiB)을 초과했거나 비어 있습니다." }
        require(note.pages > 0) { "필기 문서의 페이지 수가 잘못되었습니다." }
        val json = JSONObject().put("version", 2).put("name", note.name).put("pages", note.pages)
        val strokes = JSONArray()
        note.strokes.forEach { stroke ->
            require(stroke.page in 0 until note.pages && stroke.width > 0 && stroke.width.isFinite())
            require(stroke.tool in setOf("pen", "highlighter"))
            val points = JSONArray()
            stroke.points.forEach { p ->
                require(p.x.isFinite() && p.y.isFinite() && p.x in 0f..1f && p.y in 0f..1f && p.pressure.isFinite())
                points.put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble()).put(p.pressure.toDouble()).put(p.time))
            }
            require(stroke.points.isNotEmpty())
            strokes.put(JSONObject().put("page", stroke.page).put("width", stroke.width.toDouble()).put("color", stroke.color).put("points", points).put("tool", stroke.tool))
        }
        json.put("strokes", strokes)
        val text = json.toString()
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= INK_LIMIT) { "필기 정보의 크기 제한을 초과했습니다." }
        JsonReader(StringReader(text)).use { checkJson(it) { false } }
        val file = AtomicFile(File(directory(note.id), "note.json"))
        val out = file.startWrite()
        try {
            out.write(bytes); file.finishWrite(out)
        } catch (error: Exception) { file.failWrite(out); throw error }
    }

    fun load(id: String): PdfInkNote = synchronized(storageLock) {
        val json = JSONObject(String(AtomicFile(File(directory(id), "note.json")).readFully(), Charsets.UTF_8))
        val version = json.getInt("version")
        require(version in 1..2) { "미지원 필기 데이터 버전" }
        val pages = json.getInt("pages")
        val array = json.getJSONArray("strokes")
        val note = PdfInkNote(id, json.getString("name"), pages, (0 until array.length()).map { i ->
            val s = array.getJSONObject(i); val p = s.getJSONArray("points")
            InkStroke(s.getInt("page"), s.getDouble("width").toFloat(), s.getInt("color"), (0 until p.length()).map { j ->
                val point = p.getJSONArray(j)
                InkPoint(point.getDouble(0).toFloat(), point.getDouble(1).toFloat(), point.getDouble(2).toFloat(), point.getLong(3))
            }, if (version == 1) "pen" else s.getString("tool"))
        })
        require(pages > 0 && note.strokes.all { s -> s.tool in setOf("pen", "highlighter") && s.page in 0 until pages && s.width.isFinite() && s.width > 0 && s.points.isNotEmpty() && s.points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f && it.pressure.isFinite() } }) { "필기 데이터 손상" }
        note
    }
}
