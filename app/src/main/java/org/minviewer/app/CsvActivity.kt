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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.AbsListView
import android.widget.TextView
import android.widget.EditText
import android.text.InputType
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
    private lateinit var controls: ViewerControls
    private var canLoadNext = false
    private var page = 0
    private var encoding: String? = null
    private var zoom = 1f
    private var columns = 1
    private var statusBase = ""
    private val windowRows = ArrayList<List<String>>()
    private var windowStartPage = 0
    private var windowEndPage = 0
    private var windowHasNext = false
    private var scrollArmed = false
    private var lastVisibleRow = 0
    private var loading = false
    private var pinchZoom = 1f
    private var pinchStartX = 0f
    private var pinchStartY = 0f
    private var pinchFocusX = 0f
    private var pinchFocusY = 0f
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
        controls = ViewerControls(this, intent.getStringExtra("name") ?: "CSV 문서", { finish() }, null,
            { showRowJump() },
            listOf("행으로 이동" to {showRowJump()}, "문자 인코딩" to {showEncoding()}), enabledMenuItem={!loading},onFit={zoomFromButton(1f/zoom)})
        controls.listButton.contentDescription="행으로 이동"
        controls.setLocationAction("행으로 이동"){showRowJump()}
        status=controls.status
        list = ListView(this).apply {
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, state: Int) {
                    if (state == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) {
                        scrollArmed = true
                        if (!loading && list.lastVisiblePosition >= list.count - 10 && windowHasNext) {
                            scrollArmed = false
                            loadPage(windowEndPage + 1, direction = 1)
                        }
                    }
                }

                override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {
                    if (loading || total == 0 || visible == 0) return
                    val currentPage = windowStartPage + first / PAGE_SIZE
                    if (currentPage != page) {
                        page = currentPage
                        canLoadNext = page < windowEndPage || windowHasNext
                        updateRangeStatus()
                        saveView()
                    }
                    if (scrollArmed && first + visible >= total - 10 && windowHasNext) {
                        scrollArmed = false
                        loadPage(windowEndPage + 1, direction = 1)
                    } else if (scrollArmed && first < lastVisibleRow && first <= 10 && windowStartPage > 0) {
                        scrollArmed = false
                        loadPage(windowStartPage - 1, direction = -1)
                    }
                    lastVisibleRow = first
                }
            })
        }
        scroller = HorizontalScrollView(this).apply { addView(list) }
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinchZoom = zoom
                pinchStartX = detector.focusX
                pinchStartY = detector.focusY
                pinchFocusX = pinchStartX
                pinchFocusY = pinchStartY
                val location = IntArray(2)
                list.getLocationInWindow(location)
                list.pivotX = pinchStartX - location[0]
                list.pivotY = pinchStartY - location[1]
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pinchZoom = (pinchZoom * kotlin.math.sqrt(detector.scaleFactor)).coerceIn(1f, 4f)
                pinchFocusX = detector.focusX
                pinchFocusY = detector.focusY
                list.scaleX = pinchZoom / zoom
                list.scaleY = pinchZoom / zoom
                list.translationX = pinchFocusX - pinchStartX
                list.translationY = pinchFocusY - pinchStartY
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                list.scaleX = 1f
                list.scaleY = 1f
                list.translationX = 0f
                list.translationY = 0f
                setZoom(pinchZoom, pinchStartX, pinchStartY, pinchFocusX, pinchFocusY)
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
            addView(controls)
            addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        loadPage(page)
    }

    private fun showRowJump() {
        if(loading)return
        val ui=UiStyle(this)
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(ui.dp(24),ui.dp(8),ui.dp(24),ui.dp(8))}
        root.addView(ui.label("행 번호",14f,true))
        val input=EditText(this).apply {inputType=InputType.TYPE_CLASS_NUMBER;contentDescription="행 번호";setSingleLine();minimumHeight=ui.dp(48)}
        root.addView(input,LinearLayout.LayoutParams(-1,-2))
        val error=ui.label("",12f,color=ui.danger).apply {visibility=View.GONE;setPadding(0,ui.dp(8),0,0)}
        root.addView(error)
        val dialog=android.app.AlertDialog.Builder(this).setTitle("행으로 이동").setView(root)
            .setNegativeButton("취소",null).setPositiveButton("이동",null).create()
        dialog.setOnShowListener {dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val row=input.text.toString().toLongOrNull()
            if(row==null||row !in 1..Int.MAX_VALUE.toLong()) {
                error.text="1 이상의 행 번호를 입력해 주세요.";error.visibility=View.VISIBLE;input.requestFocus()
            } else {dialog.dismiss();loadPage(((row-1)/PAGE_SIZE).toInt(),((row-1)%PAGE_SIZE).toInt())}
        }}
        dialog.show();input.requestFocus()
    }

    private fun showEncoding() {
        if(loading)return
        val options=arrayOf("UTF-8","CP949")
        android.app.AlertDialog.Builder(this).setTitle("문자 인코딩").setItems(options){_,index->encoding=options[index];loadPage(0)}.show()
    }

    private fun loadPage(index: Int, rowInPage: Int = 0, direction: Int = 0) {
        val uri = intent.data ?: run { status.text = "CSV 오류: 파일이 없습니다."; return }
        if (loading) return
        loading = true
        val request = ++generation
        val previousHasNext = canLoadNext
        canLoadNext = false
        if (direction == 0) status.text = "CSV를 불러오는 중입니다."
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
                        loading = false
                        if ((index > 0 && rows.isEmpty()) || (rows.isNotEmpty() && rowInPage >= rows.size)) {
                            status.text = "CSV 오류: 해당 행이 없습니다."
                            canLoadNext = previousHasNext
                            return@runOnUiThread
                        }
                        encoding = charset
                        RecentDocuments(this@CsvActivity).add(uri, intent.getStringExtra("name") ?: "document")
                        val first = list.firstVisiblePosition
                        val top = list.getChildAt(0)?.top ?: 0
                        var restorePosition: Int? = null
                        when (direction) {
                            1 -> {
                                windowRows.addAll(rows)
                                windowEndPage = index
                                windowHasNext = hasNext
                                if (windowRows.size > PAGE_SIZE * 3) {
                                    windowRows.subList(0, PAGE_SIZE).clear()
                                    windowStartPage++
                                    restorePosition = (first - PAGE_SIZE).coerceAtLeast(0)
                                }
                            }
                            -1 -> {
                                windowRows.addAll(0, rows)
                                windowStartPage = index
                                if (windowRows.size > PAGE_SIZE * 3) {
                                    val removeCount = (windowRows.size - (windowEndPage - windowStartPage) * PAGE_SIZE).coerceIn(1, PAGE_SIZE)
                                    windowRows.subList(windowRows.size - removeCount, windowRows.size).clear()
                                    windowEndPage--
                                    windowHasNext = true
                                }
                                restorePosition = first + rows.size
                            }
                            else -> {
                                page = index
                                windowStartPage = index
                                windowEndPage = index
                                windowHasNext = hasNext
                                windowRows.clear()
                                windowRows.addAll(rows)
                                scrollArmed = false
                                list.adapter = RowAdapter(windowRows)
                                list.setSelection(rowInPage.coerceAtMost(rows.lastIndex.coerceAtLeast(0)))
                            }
                        }
                        columns = windowRows.maxOfOrNull { it.size } ?: 1
                        applyZoom()
                        restorePosition?.let { list.setSelectionFromTop(it, top) }
                        saveView()
                        setResult(RESULT_OK, android.content.Intent().setData(uri).putExtra("name", intent.getStringExtra("name")))
                        canLoadNext = page < windowEndPage || windowHasNext
                        updateRangeStatus()
                        if (direction == 0 && rowInPage >= PAGE_SIZE - 10 && windowHasNext) {
                            list.post { if (!loading && windowEndPage == index && windowHasNext) loadPage(index + 1, direction = 1) }
                        }
                    }
                }
            } catch (error: Exception) {
                try { cursor?.reader?.close() } catch (closeError: IOException) { error.addSuppressed(closeError) }
                cursor = null
                if (!closed) runOnUiThread {
                    if (!closed && request == generation) {
                        loading = false
                        val message = if (error is java.nio.charset.CharacterCodingException) {
                            "문자 인코딩을 확인하고 UTF-8 또는 CP949를 선택해 주세요."
                        } else error.message ?: "파일을 읽을 수 없습니다."
                        status.text = "CSV 오류: $message"
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

    private fun zoomFromButton(factor:Float) {
        val location=IntArray(2);scroller.getLocationInWindow(location)
        val x=location[0]+scroller.width/2f;val y=location[1]+scroller.height/2f
        setZoom(zoom*factor,x,y,x,y)
    }

    private fun setZoom(value: Float, focusX: Float, focusY: Float, targetX: Float, targetY: Float) {
        val next = value.coerceIn(1f, 4f)
        if (next == zoom || list.adapter == null) return
        val first = list.firstVisiblePosition
        val top = list.getChildAt(0)?.top ?: 0
        val horizontalPosition = scroller.scrollX
        val ratio = next / zoom
        val location = IntArray(2)
        scroller.getLocationInWindow(location)
        val startX = focusX - location[0]
        val startY = focusY - location[1]
        val endX = targetX - location[0]
        val endY = targetY - location[1]
        zoom = next
        applyZoom()
        list.setSelectionFromTop(first, (endY - (startY - top) * ratio).toInt())
        scroller.post { scroller.scrollTo(((horizontalPosition + startX) * ratio - endX).toInt(), 0) }
        updateZoomStatus()
    }

    private fun updateZoomStatus() {
        if (statusBase.isNotEmpty()) status.text = "$statusBase · ${(zoom * 100).toInt()}%"
    }

    private fun updateRangeStatus() {
        val charset = encoding ?: return
        val offset = (page - windowStartPage) * PAGE_SIZE
        val count = (windowRows.size - offset).coerceIn(0, PAGE_SIZE)
        statusBase = if (windowRows.isEmpty()) "CSV · 빈 파일 · $charset" else "CSV · ${page * PAGE_SIZE + 1}-${page * PAGE_SIZE + count}행 · $charset"
        updateZoomStatus()
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
