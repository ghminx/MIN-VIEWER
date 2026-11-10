package org.minviewer.app

import android.content.Context
import android.graphics.fonts.Font
import android.graphics.fonts.FontStyle
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

class UserFonts(private val context: Context) {
    data class Entry(val id: String, val families: List<String>, val weight: Int, val italic: Boolean) {
        val name: String get() = families.first()
        fun json() = JSONObject().put("id", id).put("families", JSONArray(families)).put("weight", weight).put("italic", italic)
    }

    private val directory get() = File(context.filesDir, "user-fonts").apply { mkdirs() }
    private val preferences get() = context.getSharedPreferences("user-fonts", Context.MODE_PRIVATE)

    fun all(): List<Entry> = preferences.all.mapNotNull { (id, value) ->
        if (!id.matches(Regex("[0-9a-f]{64}")) || value !is String || !file(id).isFile) return@mapNotNull null
        val data = JSONObject(value)
        val names = data.getJSONArray("families")
        Entry(id, (0 until names.length()).map { names.getString(it) }, data.getInt("weight"), data.getBoolean("italic"))
    }.sortedBy { it.name }

    fun file(id: String): File = File(directory, "$id.ttf")

    fun add(uri: Uri): Entry {
        val temporary = File.createTempFile("import-", ".font", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("글꼴 파일을 읽을 수 없습니다.")
            input.use { source -> temporary.outputStream().use { target ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 64L * 1024 * 1024) throw IOException("글꼴 파일은 64MB까지 추가할 수 있습니다.")
                    digest.update(buffer, 0, count)
                    target.write(buffer, 0, count)
                }
            } }
            val families = readFamilies(temporary)
            val style = try { Font.Builder(temporary).build().style } catch (_: IllegalArgumentException) {
                throw IOException("지원하지 않는 글꼴 파일입니다.")
            }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            val entry = Entry(id, families, style.weight, style.slant == FontStyle.FONT_SLANT_ITALIC)
            val destination = file(id)
            if (!destination.exists() && !temporary.renameTo(destination)) throw IOException("글꼴을 보관할 수 없습니다.")
            if (!preferences.edit().putString(id, entry.json().toString()).commit()) throw IOException("글꼴 목록을 저장할 수 없습니다.")
            return entry
        } finally {
            temporary.delete()
        }
    }

    fun remove(entry: Entry) {
        if (!file(entry.id).delete() && file(entry.id).exists()) throw IOException("글꼴 파일을 삭제할 수 없습니다.")
        if (!preferences.edit().remove(entry.id).commit()) throw IOException("글꼴 목록을 저장할 수 없습니다.")
    }

    private fun readFamilies(file: File): List<String> = RandomAccessFile(file, "r").use { font ->
        val length = font.length()
        if (length < 12) throw IOException("글꼴 파일이 올바르지 않습니다.")
        val signature = font.readInt()
        if (signature != 0x00010000 && signature != 0x4f54544f) throw IOException("TTF 또는 OTF 글꼴을 선택해 주세요.")
        val count = font.readUnsignedShort()
        if (count == 0 || count > 256 || 12L + count * 16 > length) throw IOException("글꼴 정보가 올바르지 않습니다.")
        font.seek(12)
        var table = -1L
        var size = 0L
        repeat(count) {
            val tag = font.readInt()
            font.readInt()
            val offset = font.readInt().toLong() and 0xffffffffL
            val tableSize = font.readInt().toLong() and 0xffffffffL
            if (tag == 0x6e616d65) { table = offset; size = tableSize }
        }
        if (table < 0 || size < 6 || table + size > length) throw IOException("글꼴 이름을 읽을 수 없습니다.")
        font.seek(table + 2)
        val records = font.readUnsignedShort()
        val strings = font.readUnsignedShort()
        if (records > 4096 || 6L + records * 12 > size || strings > size) throw IOException("글꼴 이름 정보가 올바르지 않습니다.")
        val candidates = mutableListOf<Pair<Int, String>>()
        repeat(records) {
            font.seek(table + 6L + it * 12)
            val platform = font.readUnsignedShort()
            font.readUnsignedShort()
            val language = font.readUnsignedShort()
            val nameId = font.readUnsignedShort()
            val bytes = font.readUnsignedShort()
            val offset = font.readUnsignedShort()
            if ((platform == 0 || platform == 3) && nameId in listOf(1, 16) && bytes in 2..512 && bytes % 2 == 0 && strings.toLong() + offset + bytes <= size) {
                font.seek(table + strings + offset)
                val data = ByteArray(bytes)
                font.readFully(data)
                val name = String(data, Charsets.UTF_16BE).trim()
                if (name.isNotEmpty() && name.none { c -> c.isISOControl() || c == '<' || c == '>' }) {
                    val priority = (if (nameId == 16) 0 else 2) + (if (language == 0x409) 0 else 1)
                    candidates.add(priority to name)
                }
            }
        }
        candidates.sortedBy { it.first }.map { it.second }.distinct().ifEmpty { throw IOException("글꼴 이름을 읽을 수 없습니다.") }
    }
}
