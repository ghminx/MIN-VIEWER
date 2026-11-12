package org.minviewer.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.EditText
import android.text.InputType
import android.text.TextUtils
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
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
    private data class Cursor(val reader: PushbackReader, val charset: String, var nextPage: Int, var nextRow: List<String>?)
    private val worker = Executors.newSingleThreadExecutor()
    private var cursor: Cursor? = null
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var scroller: HorizontalScrollView
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var previous: Button
    private lateinit var next: Button
    private lateinit var encodingButton: Button
    private var page = 0
    private var encoding: String? = null
    private var zoom = 1f
    private var columns = 1
    private var statusBase = ""
    private var touchInDocument = false
    @Volatile private var generation = 0
    @Volatile private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.apply(this)
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (ThemePreferences.isDark(this)) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        page = savedInstanceState?.getInt("page") ?: intent.getIntExtra("restore_page", 0)
        zoom = (savedInstanceState?.getFloat("zoom") ?: intent.getFloatExtra("restore_zoom", 1f)).coerceIn(1f, 4f)
        encoding = savedInstanceState?.getString("encoding") ?: intent.getStringExtra("restore_encoding")
            ?.takeIf { it == "UTF-8" || it == "CP949" }
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(25, 45, 77))
        }
        val bottom = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(25, 45, 77))
        }
        fun addButton(parent: LinearLayout, label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { action() }
            parent.addView(this, LinearLayout.LayoutParams((56 * resources.displayMetrics.density).toInt(),
                (48 * resources.displayMetrics.density).toInt()))
        }
        addButton(toolbar, "뒤로") { finish() }
        toolbar.addView(TextView(this).apply {
            text = intent.getStringExtra("name") ?: intent.data?.lastPathSegment ?: "CSV 문서"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addButton(toolbar, "이동") {
            val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "행 번호" }
            android.app.AlertDialog.Builder(this).setTitle("행으로 이동").setView(input)
                .setNegativeButton("취소", null).setPositiveButton("확인") { _, _ ->
                    val row = input.text.toString().toLongOrNull()
                    if (row == null || row !in 1..Int.MAX_VALUE.toLong()) {
                        status.text = "CSV 오류: 1 이상의 행 번호를 입력해 주세요."
                    } else loadPage(((row - 1) / PAGE_SIZE).toInt(), ((row - 1) % PAGE_SIZE).toInt())
                }.show()
        }
        encodingButton = addButton(toolbar, "인코딩") {
            val options = arrayOf("UTF-8", "CP949")
            android.app.AlertDialog.Builder(this).setTitle("문자 인코딩").setItems(options) { _, index ->
                encoding = options[index]
                loadPage(0)
            }.show()
        }
        status = TextView(this).apply {
            text = "CSV를 불러오는 중입니다."
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
        }
        val notice = TextView(this).apply {
            visibility = View.GONE
            setPadding(12, 8, 12, 8)
        }
        status.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val error = s?.toString()?.startsWith("CSV 오류:") == true
                notice.text = if (error) s else ""
                notice.visibility = if (error) View.VISIBLE else View.GONE
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        previous = addButton(bottom, "이전") { if (page > 0) loadPage(page - 1) }
        bottom.addView(status, LinearLayout.LayoutParams(0, (48 * resources.displayMetrics.density).toInt(), 1f))
        next = addButton(bottom, "다음") { loadPage(page + 1) }
        list = ListView(this)
        scroller = HorizontalScrollView(this).apply { addView(list) }
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setZoom(zoom * kotlin.math.sqrt(detector.scaleFactor))
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                saveView()
            }
        }).apply { isQuickScaleEnabled = false }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
            addView(toolbar)
            addView(notice)
            addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(bottom)
        })
        loadPage(page)
    }

    private fun loadPage(index: Int, rowInPage: Int = 0) {
        val uri = intent.data ?: run { status.text = "CSV 오류: 파일이 없습니다."; return }
        val request = ++generation
        val previousHasNext = next.isEnabled
        previous.isEnabled = false
        next.isEnabled = false
        encodingButton.isEnabled = false
        status.text = "CSV를 불러오는 중입니다."
        val selectedEncoding = encoding
        worker.execute {
            try {
                val existing = cursor
                val reusable = existing != null && existing.nextPage == index && existing.charset == selectedEncoding
                val current = if (reusable) existing!! else {
                    existing?.reader?.close()
                    cursor = null
                    openCursor(uri, selectedEncoding).also { cursor = it }
                }
                if (!reusable) {
                    var skipped = 0L
                    val target = index.toLong() * PAGE_SIZE
                    while (skipped < target && !closed && request == generation) {
                        if (readRow(current.reader) == null) break
                        skipped++
                    }
                }
                val rows = ArrayList<List<String>>()
                current.nextRow?.let { rows.add(it) }
                current.nextRow = null
                while (rows.size < PAGE_SIZE && !closed && request == generation) {
                    rows.add(readRow(current.reader) ?: break)
                }
                if (closed || request != generation) {
                    current.reader.close()
                    cursor = null
                    return@execute
                }
                current.nextRow = if (rows.isNotEmpty()) readRow(current.reader) else null
                current.nextPage = index + 1
                val hasNext = current.nextRow != null
                if (!hasNext) {
                    current.reader.close()
                    cursor = null
                }
                val charset = current.charset
                if (!closed) runOnUiThread {
                    if (!closed && request == generation) {
                        if ((index > 0 && rows.isEmpty()) || (rows.isNotEmpty() && rowInPage >= rows.size)) {
                            status.text = "CSV 오류: 해당 행이 없습니다."
                            previous.isEnabled = page > 0
                            next.isEnabled = previousHasNext
                            encodingButton.isEnabled = true
                            return@runOnUiThread
                        }
                        page = index
                        encoding = charset
                        RecentDocuments(this@CsvActivity).add(uri, intent.getStringExtra("name") ?: "document")
                        columns = rows.maxOfOrNull { it.size } ?: 1
                        list.adapter = RowAdapter(rows)
                        applyZoom()
                        saveView()
                        list.setSelection(rowInPage.coerceAtMost(rows.lastIndex.coerceAtLeast(0)))
                        setResult(RESULT_OK, android.content.Intent().setData(uri).putExtra("name", intent.getStringExtra("name")))
                        previous.isEnabled = page > 0
                        next.isEnabled = hasNext
                        encodingButton.isEnabled = true
                        statusBase = if (rows.isEmpty()) "CSV · 빈 파일 · $charset" else "CSV · ${page * PAGE_SIZE + 1}-${page * PAGE_SIZE + rows.size}행 · $charset"
                        updateZoomStatus()
                    }
                }
            } catch (error: Exception) {
                try { cursor?.reader?.close() } catch (closeError: IOException) { error.addSuppressed(closeError) }
                cursor = null
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

    private fun openCursor(uri: Uri, selectedEncoding: String?): Cursor {
        val stream = BufferedInputStream(contentResolver.openInputStream(uri) ?: throw IOException("파일을 읽을 수 없습니다."))
        try {
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
            return Cursor(reader, charset, 0, null)
        } catch (error: Exception) {
            stream.close()
            throw error
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

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val location = IntArray(2)
            if (::scroller.isInitialized) scroller.getLocationOnScreen(location)
            touchInDocument = ::scroller.isInitialized && event.rawX >= location[0] && event.rawX < location[0] + scroller.width &&
                event.rawY >= location[1] && event.rawY < location[1] + scroller.height
        }
        if (touchInDocument && ::scaleDetector.isInitialized) {
            scaleDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) list.parent.requestDisallowInterceptTouchEvent(true)
        }
        return super.dispatchTouchEvent(event)
    }

    private fun cellWidth() = (160 * resources.displayMetrics.density * zoom).toInt().coerceAtLeast(1)

    private fun applyZoom() {
        list.layoutParams = android.widget.FrameLayout.LayoutParams(maxOf(scroller.width, columns * cellWidth()), -1)
        (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }

    private fun setZoom(value: Float) {
        val next = value.coerceIn(1f, 4f)
        if (next == zoom || list.adapter == null) return
        val first = list.firstVisiblePosition
        val top = list.getChildAt(0)?.top ?: 0
        val horizontalPosition = scroller.scrollX
        val ratio = next / zoom
        zoom = next
        applyZoom()
        list.setSelectionFromTop(first, (top * ratio).toInt())
        scroller.post { scroller.scrollTo((horizontalPosition * ratio).toInt(), 0) }
        updateZoomStatus()
    }

    private fun updateZoomStatus() {
        if (statusBase.isNotEmpty()) status.text = "$statusBase · ${(zoom * 100).toInt()}%"
    }

    private fun saveView() {
        val uri = intent.data ?: return
        val charset = encoding ?: return
        RecentDocuments(this).updateView(uri, page, charset, zoom)
    }

    private inner class RowAdapter(private val rows: List<List<String>>) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val row = (recycled as? LinearLayout ?: LinearLayout(this@CsvActivity)).apply { removeAllViews() }
            rows[position].forEach { cell ->
                row.addView(TextView(this@CsvActivity).apply {
                    text = cell
                    textSize = 14f * zoom
                    setPadding(8, 8, 8, 8)
                    setTextColor(Color.BLACK)
                    setBackgroundColor(Color.WHITE)
                }, LinearLayout.LayoutParams(cellWidth(), -2))
            }
            return row
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", page)
        outState.putString("encoding", encoding)
        outState.putFloat("zoom", zoom)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        closed = true
        generation++
        worker.execute { cursor?.reader?.close(); cursor = null }
        worker.shutdown()
        super.onDestroy()
    }

    companion object { private const val PAGE_SIZE = 100 }
}
