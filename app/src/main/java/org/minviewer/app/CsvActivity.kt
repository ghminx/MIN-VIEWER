package org.minviewer.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.PushbackReader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors

class CsvActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var previous: Button
    private lateinit var next: Button
    private lateinit var encodingButton: Button
    private var page = 0
    private var encoding: String? = null
    @Volatile private var generation = 0
    @Volatile private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = savedInstanceState?.getInt("page") ?: 0
        encoding = savedInstanceState?.getString("encoding")
        val toolbar = LinearLayout(this)
        fun addButton(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            setOnClickListener { action() }
            toolbar.addView(this, if (resources.configuration.screenWidthDp >= 600)
                LinearLayout.LayoutParams((96 * resources.displayMetrics.density).toInt(), -2)
            else LinearLayout.LayoutParams(0, -2, 1f))
        }
        addButton("뒤로") { finish() }
        previous = addButton("이전") { if (page > 0) loadPage(page - 1) }
        next = addButton("다음") { loadPage(page + 1) }
        encodingButton = addButton("인코딩") {
            val options = arrayOf("UTF-8", "CP949")
            android.app.AlertDialog.Builder(this).setTitle("문자 인코딩").setItems(options) { _, index ->
                encoding = options[index]
                loadPage(0)
            }.show()
        }
        status = TextView(this).apply { text = "CSV를 불러오는 중입니다." }
        list = ListView(this)
        val scroller = HorizontalScrollView(this).apply { addView(list) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
            addView(toolbar)
            addView(status)
            addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        loadPage(page)
    }

    private fun loadPage(index: Int) {
        val uri = intent.data ?: run { status.text = "CSV 오류: 파일이 없습니다."; return }
        val request = ++generation
        previous.isEnabled = false
        next.isEnabled = false
        encodingButton.isEnabled = false
        status.text = "CSV를 불러오는 중입니다."
        val selectedEncoding = encoding
        worker.execute {
            try {
                val input = BufferedInputStream(contentResolver.openInputStream(uri) ?: throw IOException("파일을 읽을 수 없습니다."))
                input.use { stream ->
                    stream.mark(32769)
                    val sample = ByteArray(32768)
                    var used = 0
                    while (used < sample.size) {
                        val count = stream.read(sample, used, sample.size - used)
                        if (count < 0) break
                        used += count
                    }
                    stream.reset()
                    val charset = selectedEncoding ?: detectEncoding(sample, used)
                    val decoder = Charset.forName(charset).newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    val reader = PushbackReader(InputStreamReader(stream, decoder), 1)
                    val first = reader.read()
                    if (first != -1 && first != '\ufeff'.code) reader.unread(first)
                    val rows = ArrayList<List<String>>()
                    var row = 0
                    var hasNext = false
                    while (!closed && request == generation) {
                        val cells = readRow(reader) ?: break
                        if (row >= index * PAGE_SIZE) {
                            if (rows.size == PAGE_SIZE) { hasNext = true; break }
                            rows.add(cells)
                        }
                        row++
                    }
                    if (!closed) runOnUiThread {
                        if (!closed && request == generation) {
                            page = index
                            encoding = charset
                            val columns = rows.maxOfOrNull { it.size } ?: 1
                            val width = (160 * resources.displayMetrics.density).toInt()
                            list.layoutParams = android.widget.FrameLayout.LayoutParams(maxOf(resources.displayMetrics.widthPixels, columns * width), -1)
                            list.adapter = RowAdapter(rows, width)
                            setResult(RESULT_OK, android.content.Intent().setData(uri).putExtra("name", intent.getStringExtra("name")))
                            previous.isEnabled = page > 0
                            next.isEnabled = hasNext
                            encodingButton.isEnabled = true
                            status.text = if (rows.isEmpty()) "CSV · 빈 파일 · $charset" else "CSV · ${page * PAGE_SIZE + 1}-${page * PAGE_SIZE + rows.size}행 · $charset"
                        }
                    }
                }
            } catch (error: Exception) {
                if (!closed) runOnUiThread {
                    if (!closed && request == generation) {
                        val message = if (error is java.nio.charset.CharacterCodingException) {
                            "문자 인코딩을 확인하고 UTF-8 또는 CP949를 선택해 주세요."
                        } else error.message ?: "파일을 읽을 수 없습니다."
                        status.text = "CSV 오류: $message"
                        encodingButton.isEnabled = true
                        previous.isEnabled = page > 0
                    }
                }
            }
        }
    }

    private fun detectEncoding(bytes: ByteArray, size: Int): String {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val result = decoder.decode(ByteBuffer.wrap(bytes, 0, size), CharBuffer.allocate(size), false)
        return if (result.isError) "CP949" else "UTF-8"
    }

    private fun readRow(reader: PushbackReader): List<String>? {
        val cells = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var afterQuote = false
        var seen = false
        var size = 0
        while (true) {
            val value = reader.read()
            if (value == -1) {
                if (quoted) throw IOException("닫히지 않은 따옴표가 있습니다.")
                if (!seen && cells.isEmpty() && cell.isEmpty()) return null
                cells.add(cell.toString())
                return cells
            }
            val ch = value.toChar()
            seen = true
            if (++size > 131072) throw IOException("한 행이 너무 깁니다. 최대 131072자까지 지원합니다.")
            if (quoted) {
                if (ch == '"') {
                    val following = reader.read()
                    if (following == '"'.code) cell.append('"') else {
                        quoted = false
                        afterQuote = true
                        if (following != -1) reader.unread(following)
                    }
                } else cell.append(ch)
            } else if (ch == ',') {
                cells.add(cell.toString())
                if (cells.size >= 512) throw IOException("열이 너무 많습니다. 최대 512열까지 지원합니다.")
                cell.setLength(0)
                afterQuote = false
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r') {
                    val following = reader.read()
                    if (following != -1 && following != '\n'.code) reader.unread(following)
                }
                cells.add(cell.toString())
                return cells
            } else if (ch == '"' && cell.isEmpty() && !afterQuote) {
                quoted = true
            } else {
                if (afterQuote || ch == '"') throw IOException("따옴표 형식이 잘못되었습니다.")
                cell.append(ch)
            }
        }
    }

    private inner class RowAdapter(private val rows: List<List<String>>, private val cellWidth: Int) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val row = (recycled as? LinearLayout ?: LinearLayout(this@CsvActivity)).apply { removeAllViews() }
            rows[position].forEach { cell ->
                row.addView(TextView(this@CsvActivity).apply {
                    text = cell
                    textSize = 14f
                    setPadding(8, 8, 8, 8)
                    setTextColor(Color.BLACK)
                    setBackgroundColor(Color.WHITE)
                }, LinearLayout.LayoutParams(cellWidth, -2))
            }
            return row
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", page)
        outState.putString("encoding", encoding)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        closed = true
        generation++
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object { private const val PAGE_SIZE = 100 }
}
