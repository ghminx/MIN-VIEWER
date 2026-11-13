package org.minviewer.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.LoadParams
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.EditText
import android.text.InputType
import java.io.File
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@android.annotation.TargetApi(35)
private object PdfSearchApi35 {
    fun match(page: PdfRenderer.Page, query: String, index: Int): List<RectF>? {
        val matches = page.searchText(query)
        if (index >= matches.size) return null
        return matches[index].bounds.map { RectF(it) }
    }
}

@android.annotation.TargetApi(35)
private object PdfPasswordApi35 {
    fun open(descriptor: ParcelFileDescriptor, password: String): PdfRenderer =
        PdfRenderer(descriptor, LoadParams.Builder().setPassword(password).build())
}

private class PdfPasswordRequired : IOException()

class PdfActivity : Activity() {
    companion object { private var oldTemporaryFilesCleaned = false }
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32), ThreadPoolExecutor.DiscardOldestPolicy())
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val thumbnails = object : LruCache<Int, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.allocationByteCount
    }
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var horizontal: HorizontalScrollView
    private lateinit var contentRow: LinearLayout
    private var thumbnailPanel: GridView? = null
    private lateinit var nextSearch: Button
    private var renderer: PdfRenderer? = null
    private var temporaryFile: File? = null
    private var pageCount = 0
    private var pageIndex = 0
    private var mode = 1
    private var restorePanel = false
    private var zoom = 1f
    private var pageOffsetX = 0f
    private var pageOffsetY = 0f
    private var panStartX = 0f
    private var panStartY = 0f
    private var pinchZoom = 1f
    private var pinchFocusX = 0f
    private var pinchFocusY = 0f
    private var pinchStartFocusX = 0f
    private var pinchStartFocusY = 0f
    private var searchMessage = ""
    private var highlightedPage = -1
    private var highlightedBounds = emptyList<RectF>()
    private var highlightedWidth = 1
    private var highlightedHeight = 1
    private var highlightedMatch = 0
    private var searchQuery = ""
    private var pendingTarget: Int? = null
    private var navigationGeneration = 0
    @Volatile private var renderGeneration = 0
    private var touchX = 0f
    private var touchY = 0f
    private var multiTouch = false
    private var touchInDocument = false
    private lateinit var scaleDetector: ScaleGestureDetector
    @Volatile private var searchGeneration = 0
    @Volatile private var searching = false
    @Volatile private var closed = false
    private var openedSuccessfully = false
    private var persistenceReady = false
    private val recent by lazy { RecentDocuments(this) }
    private var lastSavedView: Triple<Int, Int, Float>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.apply(this)
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (ThemePreferences.isDark(this)) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        pageIndex = savedInstanceState?.getInt("page") ?: intent.getIntExtra("restore_page", 0)
        mode = savedInstanceState?.getInt("mode") ?: intent.getIntExtra("restore_mode", 1)
        restorePanel = savedInstanceState?.getBoolean("thumbnailPanel") ?: false
        zoom = savedInstanceState?.getFloat("zoom") ?: intent.getFloatExtra("restore_zoom", 1f)
        pageOffsetX = savedInstanceState?.getFloat("pageOffsetX") ?: 0f
        pageOffsetY = savedInstanceState?.getFloat("pageOffsetY") ?: 0f
        nextSearch = Button(this).apply {
            text = "다음 찾기"; textSize = 12f; minWidth = 0; setPadding(0, 0, 0, 0); isEnabled = false
            setOnClickListener { if (searchQuery.isNotEmpty() && highlightedPage >= 0) findMatch(searchQuery, highlightedPage, highlightedMatch + 1) }
        }
        val controls = ViewerControls(this, intent.getStringExtra("name") ?: "PDF", { finish() }, { searchDocument() },
            { showThumbnails() }, { movePage(-1) }, { movePage(1) }, { setZoom(zoom / 1.5f) }, { setZoom(zoom * 1.5f) },
            listOf("연속 보기" to { setMode(0) }, "한쪽 보기" to { setMode(1) }, "두쪽 보기" to { setMode(2) },
                "다음 검색 결과" to { if (nextSearch.isEnabled) nextSearch.performClick() }))
        status = controls.status
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinchZoom = zoom
                pinchFocusX = detector.focusX
                pinchFocusY = detector.focusY
                pinchStartFocusX = detector.focusX
                pinchStartFocusY = detector.focusY
                val location = IntArray(2)
                list.getLocationInWindow(location)
                list.pivotX = detector.focusX - location[0]
                list.pivotY = detector.focusY - location[1]
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (mode == 1) {
                    setPageZoom(zoom * kotlin.math.sqrt(detector.scaleFactor), pinchFocusX, pinchFocusY, detector.focusX, detector.focusY)
                    pinchFocusX = detector.focusX
                    pinchFocusY = detector.focusY
                    return true
                }
                pinchZoom = (pinchZoom * kotlin.math.sqrt(detector.scaleFactor)).coerceIn(1f, 4f)
                pinchFocusX = detector.focusX
                pinchFocusY = detector.focusY
                list.scaleX = pinchZoom / zoom
                list.scaleY = pinchZoom / zoom
                list.translationX = pinchFocusX - pinchStartFocusX
                list.translationY = pinchFocusY - pinchStartFocusY
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (mode == 1) {
                    refreshSinglePage()
                    updateStatus()
                    return
                }
                list.scaleX = 1f
                list.scaleY = 1f
                list.translationX = 0f
                list.translationY = 0f
                setZoom(pinchZoom, pinchStartFocusX, pinchStartFocusY, pinchFocusX, pinchFocusY)
            }
        }).apply { isQuickScaleEnabled = false }
        list = object : ListView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val viewport = if (::horizontal.isInitialized && horizontal.width > 0) horizontal.width else resources.displayMetrics.widthPixels
                val width = (viewport * if (mode == 1) 1f else zoom).toInt().coerceAtLeast(1)
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }.apply {
            setBackgroundColor(if (ThemePreferences.isDark(this@PdfActivity)) Color.rgb(41, 47, 51) else Color.rgb(232, 235, 239))
            dividerHeight = (8 * resources.displayMetrics.density).toInt()
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, state: Int) {
                    if (state == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) {
                        pendingTarget = null
                        navigationGeneration++
                    }
                }
                override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {
                    if (mode != 1 && total > 0 && visible > 0) {
                        val firstChild = view?.getChildAt(0)
                        val secondChild = view?.getChildAt(1)
                        fun visibleHeight(child: View?) = if (child == null || view == null) 0 else
                            (minOf(child.bottom, view.height) - maxOf(child.top, 0)).coerceAtLeast(0)
                        val row = if (visibleHeight(secondChild) > visibleHeight(firstChild)) first + 1 else first
                        pageIndex = pageForRow(row)
                        updateStatus()
                    }
                }
            })
        }
        list.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                renderGeneration++
                list.post { if (!closed) (list.adapter as? BaseAdapter)?.notifyDataSetChanged() }
            }
        }
        horizontal = HorizontalScrollView(this).apply { addView(list) }
        horizontal.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resizeDocument() }
        contentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(horizontal, LinearLayout.LayoutParams(0, -1, 1f))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
            addView(controls)
            addView(contentRow, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(controls.takeBottom())
        })
        val uri = intent.data ?: run { status.text = "PDF 오류: 파일이 없습니다."; return }
        openDocument(uri, null)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val location = IntArray(2)
            if (::horizontal.isInitialized) horizontal.getLocationOnScreen(location)
            touchInDocument = ::horizontal.isInitialized && event.rawX >= location[0] && event.rawX < location[0] + horizontal.width &&
                event.rawY >= location[1] && event.rawY < location[1] + horizontal.height
        }
        if (touchInDocument && ::scaleDetector.isInitialized) {
            scaleDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) list.parent.requestDisallowInterceptTouchEvent(true)
        }
        if (touchInDocument && event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && !multiTouch) {
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            multiTouch = true
        }
        if (touchInDocument && multiTouch) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) multiTouch = false
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchX = event.rawX; touchY = event.rawY; multiTouch = false
                panStartX = pageOffsetX; panStartY = pageOffsetY
            }
            MotionEvent.ACTION_POINTER_DOWN -> multiTouch = true
            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - touchX
                val dy = event.rawY - touchY
                val location = IntArray(2)
                if (::horizontal.isInitialized) horizontal.getLocationOnScreen(location)
                val inDocument = ::horizontal.isInitialized && touchX >= location[0] && touchX < location[0] + horizontal.width &&
                    touchY >= location[1] && touchY < location[1] + horizontal.height
                if (inDocument && !multiTouch && zoom <= 1f &&
                    kotlin.math.abs(dx) > 48 * resources.displayMetrics.density &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.25f) {
                    super.dispatchTouchEvent(event)
                    movePage(if (dx < 0) 1 else -1)
                    return true
                }
            }
        }
        if (touchInDocument && mode == 1 && zoom > 1f) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                pageOffsetX = panStartX + event.rawX - touchX
                pageOffsetY = panStartY + event.rawY - touchY
                currentPageImage()?.let { applyPageMatrix(it) }
            }
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun createRenderer(descriptor: ParcelFileDescriptor, password: String?): PdfRenderer = try {
        if (password != null && android.os.Build.VERSION.SDK_INT >= 35) PdfPasswordApi35.open(descriptor, password)
        else PdfRenderer(descriptor)
    } catch (error: Exception) {
        descriptor.close()
        if (error is SecurityException) throw PdfPasswordRequired()
        throw error
    }

    private fun openDocument(uri: Uri, password: String?) {
        worker.execute {
            try {
                synchronized(PdfActivity::class.java) {
                    if (!oldTemporaryFilesCleaned) {
                        val files = cacheDir.listFiles() ?: throw IOException("임시 폴더를 확인할 수 없습니다.")
                        files.filter { it.isFile && it.name.startsWith("pdf-") && it.name.endsWith(".pdf") }.forEach {
                            if (!it.delete()) throw IOException("이전 PDF 임시 파일을 정리할 수 없습니다.")
                        }
                        oldTemporaryFilesCleaned = true
                    }
                }
                val cached = temporaryFile
                val descriptor = if (cached != null) ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY)
                    else contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("파일을 읽을 수 없습니다.")
                val opened = try {
                    createRenderer(descriptor, password)
                } catch (error: IllegalArgumentException) {
                    val file = File.createTempFile("pdf-", ".pdf", cacheDir)
                    temporaryFile = file
                    val input = contentResolver.openInputStream(uri) ?: throw IOException("파일을 읽을 수 없습니다.")
                    input.use { source -> file.outputStream().use { target -> source.copyTo(target) } }
                    val copiedDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    createRenderer(copiedDescriptor, password)
                }
                renderer = opened
                pageCount = opened.pageCount
                if (pageCount == 0) throw IOException("표시할 페이지가 없습니다.")
                val initialPage = pageIndex.coerceIn(0, pageCount - 1)
                if (!closed) runOnUiThread {
                    if (!closed) {
                        setResult(RESULT_OK, android.content.Intent().setData(uri).putExtra("name", intent.getStringExtra("name")))
                        openedSuccessfully = true
                        recent.add(uri, intent.getStringExtra("name") ?: "document.pdf")
                        list.adapter = PageAdapter()
                        list.setSelection(rowForPage(initialPage))
                        updateStatus()
                        list.post {
                            if (!closed) {
                                persistenceReady = true
                                updateStatus()
                            }
                        }
                        if (restorePanel) {
                            restorePanel = false
                            horizontal.post { if (!closed) showThumbnails() }
                        }
                    }
                }
            } catch (_: PdfPasswordRequired) {
                if (!closed) runOnUiThread {
                    if (!closed) {
                        if (android.os.Build.VERSION.SDK_INT < 35) {
                            status.text = "PDF 오류: 암호 PDF는 Android 15 이상에서 열 수 있습니다."
                        } else {
                            val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
                            AlertDialog.Builder(this).setTitle("PDF 비밀번호")
                                .setMessage(if (password == null) "비밀번호를 입력해 주세요." else "비밀번호가 맞지 않거나 지원하지 않는 암호 방식입니다.")
                                .setView(input).setNegativeButton("취소") { _, _ -> finish() }
                                .setOnCancelListener { finish() }
                                .setPositiveButton("열기") { _, _ -> openDocument(uri, input.text.toString()) }.show()
                        }
                    }
                }
            } catch (error: Exception) {
                if (!closed) runOnUiThread { if (!closed) status.text = "PDF 오류: ${error.message}" }
            }
        }
    }

    private fun saveResult() {
        if (openedSuccessfully) {
            setResult(RESULT_OK, android.content.Intent().setData(intent.data).putExtra("name", intent.getStringExtra("name"))
                .putExtra("view_page", pageIndex).putExtra("view_mode", mode.toString()).putExtra("view_zoom", zoom))
        }
    }

    override fun finish() {
        saveResult()
        super.finish()
    }

    override fun onStop() {
        saveResult()
        super.onStop()
    }

    private fun showPage(index: Int) {
        if (index !in 0 until pageCount) return
        navigationGeneration++
        if (mode != 1) {
            pendingTarget = index
            list.setSelectionFromTop(rowForPage(index), 0)
        } else {
            if (pageIndex != index) { pageOffsetX = 0f; pageOffsetY = 0f }
            pageIndex = index
            (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
            list.setSelection(0)
            updateStatus()
        }
    }

    private fun movePage(direction: Int) {
        val index = if (mode != 2) pageIndex + direction else if (direction > 0) {
            if (pageIndex == 0) 1 else pageIndex + 2
        } else {
            if (pageIndex <= 1) 0 else pageIndex - 2
        }
        showPage(index)
    }

    private fun rowForPage(index: Int) = when (mode) { 1 -> 0; 2 -> if (index == 0) 0 else (index + 1) / 2; else -> index }
    private fun pageForRow(row: Int) = if (mode == 2 && row > 0) row * 2 - 1 else row

    private fun setMode(value: Int) {
        if (pageCount == 0 || mode == value) return
        pendingTarget = null
        navigationGeneration++
        val selected = pageIndex
        mode = value
        list.adapter = PageAdapter()
        list.setSelection(rowForPage(selected))
        pageIndex = if (mode == 2) pageForRow(rowForPage(selected)) else selected
        updateStatus()
    }

    private fun updateStatus() {
        status.text = "${pageIndex + 1}/$pageCount · ${(zoom * 100).toInt()}%"
        status.contentDescription = "PDF ${pageIndex + 1} / $pageCount · ${(zoom * 100).toInt()}%$searchMessage"
        val currentView = Triple(pageIndex, mode, zoom)
        if (persistenceReady && currentView != lastSavedView) {
            intent.data?.let { recent.updateView(it, pageIndex, mode.toString(), zoom) }
            lastSavedView = currentView
        }
    }

    private fun searchDocument() {
        if (pageCount == 0) return
        if (android.os.Build.VERSION.SDK_INT < 35) {
            searchMessage = " · 이 Android 버전에서는 PDF 검색을 지원하지 않습니다"
            android.widget.Toast.makeText(this, "이 Android 버전에서는 PDF 검색을 지원하지 않습니다", android.widget.Toast.LENGTH_LONG).show()
            updateStatus()
            return
        }
        val input = EditText(this).apply { setSingleLine(); hint = "검색어" }
        AlertDialog.Builder(this).setTitle("문서 검색").setView(input).setNegativeButton("취소", null)
            .setPositiveButton("찾기") { _, _ ->
                val query = input.text.toString()
                if (query.isNotBlank()) {
                    searchQuery = query
                    findMatch(query, 0, 0)
                }
            }.show()
    }

    private fun findMatch(query: String, startPage: Int, startMatch: Int) {
        if (android.os.Build.VERSION.SDK_INT < 35) return
        val generation = ++searchGeneration
        searching = true
        nextSearch.isEnabled = false
        searchMessage = " · 검색 중"
        updateStatus()
        worker.queue.clear()
        worker.execute {
            try {
                var found = -1
                var matchIndex = 0
                var bounds = emptyList<RectF>()
                var width = 1
                var height = 1
                for (offset in 0..pageCount) {
                    if (closed || generation != searchGeneration) return@execute
                    val index = (startPage + offset) % pageCount
                    val candidate = if (offset == 0) startMatch else 0
                    val page = (renderer ?: throw IOException("문서가 닫혔습니다.")).openPage(index)
                    page.use {
                        val match = PdfSearchApi35.match(it, query, candidate)
                        if (match != null) {
                            found = index
                            matchIndex = candidate
                            bounds = match
                            width = it.width
                            height = it.height
                        }
                    }
                    if (found >= 0) break
                }
                if (!closed) runOnUiThread {
                    if (!closed && generation == searchGeneration) {
                        searching = false
                        highlightedPage = found
                        highlightedMatch = matchIndex
                        highlightedBounds = bounds
                        highlightedWidth = width
                        highlightedHeight = height
                        nextSearch.isEnabled = found >= 0
                        searchMessage = if (found >= 0) " · 검색 결과 · 이 페이지 ${matchIndex + 1}번째" else " · 검색 결과 없음"
                        android.widget.Toast.makeText(this, searchMessage.removePrefix(" · "), android.widget.Toast.LENGTH_SHORT).show()
                        (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
                        if (found >= 0) list.post {
                            if (!closed && generation == searchGeneration) showPage(found)
                        }
                        updateStatus()
                    }
                }
            } catch (error: Exception) {
                if (!closed) runOnUiThread {
                    if (!closed && generation == searchGeneration) {
                        searching = false
                        status.text = "PDF 검색 오류: ${error.message}"
                        (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    private fun resizeDocument() {
        val width = (horizontal.width * if (mode == 1) 1f else zoom).toInt().coerceAtLeast(1)
        if (list.layoutParams.width != width) {
            renderGeneration++
            list.layoutParams = android.widget.FrameLayout.LayoutParams(width, -1)
            (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
        }
    }

    private fun setZoom(value: Float, focusX: Float? = null, focusY: Float? = null, targetX: Float? = focusX, targetY: Float? = focusY) {
        if (mode == 1) {
            val image = currentPageImage() ?: return
            val location = IntArray(2)
            image.getLocationInWindow(location)
            val x = focusX ?: location[0] + image.width / 2f
            val y = focusY ?: location[1] + image.height / 2f
            setPageZoom(value, x, y, targetX ?: x, targetY ?: y)
            refreshSinglePage()
            updateStatus()
            return
        }
        val next = value.coerceIn(1f, 4f)
        if (next == zoom) return
        val ratio = next / zoom
        val location = IntArray(2)
        horizontal.getLocationInWindow(location)
        val x = focusX?.minus(location[0]) ?: horizontal.width / 2f
        val y = focusY?.minus(location[1]) ?: horizontal.height / 2f
        val targetLocalX = targetX?.minus(location[0]) ?: x
        val targetLocalY = targetY?.minus(location[1]) ?: y
        val left = ((horizontal.scrollX + x) * ratio - targetLocalX).toInt()
        val first = list.firstVisiblePosition
        val top = list.getChildAt(0)?.top ?: 0
        val newTop = (targetLocalY - (y - top) * ratio).toInt()
        zoom = next
        resizeDocument()
        list.setSelectionFromTop(first, newTop)
        horizontal.post { horizontal.scrollTo(left, 0) }
        if (pageCount > 0) updateStatus()
    }

    private fun currentPageImage(): ImageView? = (list.getChildAt(0) as? LinearLayout)?.getChildAt(0) as? ImageView

    private fun applyPageMatrix(image: ImageView) {
        val drawable = image.drawable ?: return
        if (image.width == 0 || image.height == 0) return
        val fit = minOf(image.width.toFloat() / drawable.intrinsicWidth, image.height.toFloat() / drawable.intrinsicHeight)
        val scale = fit * zoom
        val width = drawable.intrinsicWidth * scale
        val height = drawable.intrinsicHeight * scale
        pageOffsetX = pageOffsetX.coerceIn(-maxOf(0f, (width - image.width) / 2f), maxOf(0f, (width - image.width) / 2f))
        pageOffsetY = pageOffsetY.coerceIn(-maxOf(0f, (height - image.height) / 2f), maxOf(0f, (height - image.height) / 2f))
        image.imageMatrix = android.graphics.Matrix().apply {
            setScale(scale, scale)
            postTranslate((image.width - width) / 2f + pageOffsetX, (image.height - height) / 2f + pageOffsetY)
        }
    }

    private fun setPageZoom(value: Float, focusX: Float, focusY: Float, targetX: Float, targetY: Float) {
        val image = currentPageImage() ?: return
        val next = value.coerceIn(1f, 4f)
        val location = IntArray(2)
        image.getLocationInWindow(location)
        val ratio = next / zoom
        pageOffsetX = (targetX - location[0] - image.width / 2f) - (focusX - location[0] - image.width / 2f - pageOffsetX) * ratio
        pageOffsetY = (targetY - location[1] - image.height / 2f) - (focusY - location[1] - image.height / 2f - pageOffsetY) * ratio
        zoom = next
        applyPageMatrix(image)
    }

    private fun refreshSinglePage() {
        val image = currentPageImage() ?: return
        val index = pageIndex
        val width = (((horizontal.width * zoom).toInt() + 127) / 128 * 128).coerceIn(128, 2560)
        val key = "$index:$width"
        if (image.tag == key) return
        image.tag = key
        fun display(bitmap: Bitmap) {
            if (!closed && mode == 1 && pageIndex == index && currentPageImage() === image && image.tag == key) {
                image.setImageBitmap(bitmap)
                applyPageMatrix(image)
            }
        }
        val cached = cache.get(key)
        if (cached != null) display(cached) else worker.execute {
            try {
                if (!closed) {
                    val bitmap = drawPage(index, width)
                    if (!closed) runOnUiThread {
                        if (!closed) { cache.put(key, bitmap); display(bitmap) }
                    }
                }
            } catch (error: Exception) {
                if (!closed) runOnUiThread { if (!closed && image.tag == key) status.text = "PDF 오류: ${error.message}" }
            }
        }
    }

    private fun drawPage(index: Int, targetWidth: Int = 960, thumbnail: Boolean = false): Bitmap {
        val page = (renderer ?: throw IOException("문서가 닫혔습니다.")).openPage(index)
        return page.use {
            val (width, height) = if (thumbnail) {
                val scale = minOf(targetWidth.toFloat() / it.width, 320f / it.height)
                maxOf(1, (it.width * scale).toInt()) to maxOf(1, (it.height * scale).toInt())
            } else documentRenderSize(it.width, it.height, targetWidth)
            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            result.eraseColor(Color.WHITE)
            it.render(result, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            result
        }
    }

    private fun showThumbnails() {
        if (pageCount == 0 || searching) return
        val selected = pageIndex
        thumbnailPanel?.let {
            contentRow.removeView(it)
            thumbnailPanel = null
            horizontal.post {
                if (!closed) {
                    (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
                    showPage(selected)
                }
            }
            return
        }
        worker.queue.clear()
        val grid = GridView(this).apply {
            contentDescription = "페이지 목록"
            numColumns = GridView.AUTO_FIT
            columnWidth = (96 * resources.displayMetrics.density).toInt()
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            adapter = object : BaseAdapter() {
                override fun getCount() = pageCount
                override fun getItem(position: Int) = position
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                    val item = (recycled as? LinearLayout ?: LinearLayout(this@PdfActivity)).apply {
                        orientation = LinearLayout.VERTICAL
                        contentDescription = "미리보기 ${position + 1}쪽"
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                        removeAllViews()
                        layoutParams = AbsListView.LayoutParams(-1, (144 * resources.displayMetrics.density).toInt())
                    }
                    val image = ImageView(this@PdfActivity).apply {
                        tag = position
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setBackgroundColor(Color.LTGRAY)
                        setImageBitmap(thumbnails.get(position))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    item.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
                    item.addView(TextView(this@PdfActivity).apply {
                        text = "${position + 1}쪽"
                        gravity = android.view.Gravity.CENTER
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                    if (thumbnails.get(position) == null) worker.execute {
                        if (!closed) {
                            try {
                                val bitmap = drawPage(position, 160, thumbnail = true)
                                if (!closed) runOnUiThread {
                                    if (!closed) {
                                        thumbnails.put(position, bitmap)
                                        if (image.tag == position) image.setImageBitmap(bitmap)
                                    }
                                }
                            } catch (error: Exception) {
                                if (!closed) runOnUiThread { if (!closed) status.text = "PDF 오류: ${error.message}" }
                            }
                        }
                    }
                    return item
                }
            }
        }
        if (resources.configuration.screenWidthDp >= 840) {
            thumbnailPanel = grid
            val panelWidth = (320 * resources.displayMetrics.density).toInt()
            contentRow.addView(grid, 0, LinearLayout.LayoutParams(panelWidth, -1))
            grid.setOnItemClickListener { _, _, position, _ -> showPage(position) }
            horizontal.post {
                if (!closed) {
                    (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
                    showPage(selected)
                }
            }
        } else {
            var target = selected
            val dialog = AlertDialog.Builder(this).setTitle("페이지 목록").setView(grid).setNegativeButton("닫기", null).create()
            grid.setOnItemClickListener { _, _, position, _ ->
                target = position
                dialog.dismiss()
            }
            dialog.setOnDismissListener {
                if (!closed) {
                    (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
                    list.post { if (!closed) showPage(target) }
                }
            }
            dialog.show()
        }
    }

    private inner class PageAdapter : BaseAdapter() {
        override fun getCount() = when (mode) { 1 -> 1; 2 -> 1 + pageCount / 2; else -> pageCount }
        override fun getItem(position: Int) = if (mode == 1) pageIndex else pageForRow(position)
        override fun getItemId(position: Int) = getItem(position).toLong()
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val page = getItem(position)
            val row = (recycled as? LinearLayout ?: LinearLayout(this@PdfActivity)).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = if (mode == 1) list.height else 0
                removeAllViews()
            }
            val count = if (mode == 2 && position > 0 && page + 1 < pageCount) 2 else 1
            repeat(count) { offset ->
                val index = page + offset
                val width = (parent.width / count).coerceAtLeast(1)
                val renderWidth = (((((width * if (mode == 1) zoom else 1f).toInt()) + 127) / 128) * 128).coerceAtMost(2560)
                val key = "$index:$renderWidth"
                val generation = renderGeneration
                val image = object : ImageView(this@PdfActivity) {
                    override fun onDraw(canvas: Canvas) {
                        super.onDraw(canvas)
                        val bitmap = drawable ?: return
                        if (index != highlightedPage) return
                        val area = RectF(0f, 0f, bitmap.intrinsicWidth.toFloat(), bitmap.intrinsicHeight.toFloat())
                        imageMatrix.mapRect(area)
                        val paint = Paint().apply { color = Color.argb(100, 255, 210, 0) }
                        highlightedBounds.forEach { rect ->
                            canvas.drawRect(area.left + rect.left * area.width() / highlightedWidth, area.top + rect.top * area.height() / highlightedHeight,
                                area.left + rect.right * area.width() / highlightedWidth, area.top + rect.bottom * area.height() / highlightedHeight, paint)
                        }
                    }
                }.apply {
                    contentDescription = "PDF ${index + 1}쪽"
                    scaleType = if (mode == 1) ImageView.ScaleType.MATRIX else ImageView.ScaleType.FIT_CENTER
                    tag = key
                    layoutParams = LinearLayout.LayoutParams(0, (width * 1.4f).toInt().coerceAtLeast(1), 1f)
                }
                row.addView(image)
                fun display(bitmap: Bitmap) {
                    val imageHeight = (width.toLong() * bitmap.height / bitmap.width).toInt().coerceAtLeast(1)
                    val height = if (mode == 1 && list.height > 0) list.height else imageHeight
                    image.layoutParams = LinearLayout.LayoutParams(0, height, 1f)
                    image.setImageBitmap(bitmap)
                    if (mode == 1) image.post { if (image.parent === row) applyPageMatrix(image) }
                    if (pendingTarget == index) {
                        val generation = navigationGeneration
                        pendingTarget = null
                        list.post { if (!closed && generation == navigationGeneration) list.setSelectionFromTop(rowForPage(index), 0) }
                    }
                }
                val cached = cache.get(key)
                if (cached != null) display(cached) else if (!searching) worker.execute {
                    if (!closed && generation == renderGeneration) {
                        try {
                            val bitmap = drawPage(index, renderWidth)
                            if (!closed) runOnUiThread {
                                if (!closed && generation == renderGeneration) {
                                    cache.put(key, bitmap)
                                    if (image.parent === row && image.tag == key) display(bitmap)
                                }
                            }
                        } catch (error: Exception) {
                            if (!closed) runOnUiThread { if (!closed) status.text = "PDF 오류: ${error.message}" }
                        }
                    }
                }
            }
            return row
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", pageIndex)
        outState.putInt("mode", mode)
        outState.putFloat("zoom", zoom)
        outState.putFloat("pageOffsetX", pageOffsetX)
        outState.putFloat("pageOffsetY", pageOffsetY)
        outState.putBoolean("thumbnailPanel", thumbnailPanel != null)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        closed = true
        searchGeneration++
        worker.queue.clear()
        worker.execute {
            try { renderer?.close() } finally { temporaryFile?.delete() }
        }
        worker.shutdown()
        cache.evictAll()
        thumbnails.evictAll()
        super.onDestroy()
    }
}
