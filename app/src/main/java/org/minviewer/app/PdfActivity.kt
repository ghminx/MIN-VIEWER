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
    private val cache = object : LruCache<Int, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.allocationByteCount
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
    private var mode = 0
    private var restorePanel = false
    private var zoom = 1f
    private var searchMessage = ""
    private var highlightedPage = -1
    private var highlightedBounds = emptyList<RectF>()
    private var highlightedWidth = 1
    private var highlightedHeight = 1
    private var highlightedMatch = 0
    private var searchQuery = ""
    private var pendingTarget: Int? = null
    private var navigationGeneration = 0
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
        mode = savedInstanceState?.getInt("mode") ?: intent.getIntExtra("restore_mode", 0)
        restorePanel = savedInstanceState?.getBoolean("thumbnailPanel") ?: false
        zoom = savedInstanceState?.getFloat("zoom") ?: intent.getFloatExtra("restore_zoom", 1f)
        nextSearch = Button(this).apply {
            text = "다음 찾기"; textSize = 12f; minWidth = 0; setPadding(0, 0, 0, 0); isEnabled = false
            setOnClickListener { if (searchQuery.isNotEmpty() && highlightedPage >= 0) findMatch(searchQuery, highlightedPage, highlightedMatch + 1) }
        }
        val controls = ViewerControls(this, intent.getStringExtra("name") ?: "PDF", { finish() }, { searchDocument() },
            { showThumbnails() }, { movePage(-1) }, { movePage(1) }, { setZoom(zoom / 1.5f) }, { setZoom(zoom * 1.5f) },
            listOf("연속 보기" to { setMode(0) }, "한쪽 보기" to { setMode(1) }, "두쪽 보기" to { setMode(2) },
                "다음 검색 결과" to { if (nextSearch.isEnabled) nextSearch.performClick() }))
        status = controls.status
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setZoom(zoom * detector.scaleFactor)
                return true
            }
        })
        list = object : ListView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val viewport = if (::horizontal.isInitialized && horizontal.width > 0) horizontal.width else resources.displayMetrics.widthPixels
                val width = (viewport * zoom).toInt().coerceAtLeast(1)
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }.apply {
            dividerHeight = (8 * resources.displayMetrics.density).toInt()
            setOnTouchListener { view, event ->
                scaleDetector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) view.parent.requestDisallowInterceptTouchEvent(true)
                scaleDetector.isInProgress
            }
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
            pageIndex = index
            (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
            list.setSelection(0)
            updateStatus()
        }
    }

    private fun toolLayout() = if (resources.configuration.screenWidthDp >= 600)
        LinearLayout.LayoutParams((96 * resources.displayMetrics.density).toInt(), -2)
    else LinearLayout.LayoutParams(0, -2, 1f)

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
        val width = (horizontal.width * zoom).toInt().coerceAtLeast(1)
        if (list.layoutParams.width != width) {
            list.layoutParams = android.widget.FrameLayout.LayoutParams(width, -1)
            (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
        }
    }

    private fun setZoom(value: Float) {
        zoom = value.coerceIn(1f, 4f)
        resizeDocument()
        if (pageCount > 0) updateStatus()
    }

    private fun drawPage(index: Int, targetWidth: Int = 960, maxHeight: Int = 2048): Bitmap {
        val page = (renderer ?: throw IOException("문서가 닫혔습니다.")).openPage(index)
        return page.use {
            val scale = minOf(targetWidth.toFloat() / it.width, maxHeight.toFloat() / it.height)
            val result = Bitmap.createBitmap(maxOf(1, (it.width * scale).toInt()), maxOf(1, (it.height * scale).toInt()), Bitmap.Config.ARGB_8888)
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
                                val bitmap = drawPage(position, 160, 320)
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
                removeAllViews()
            }
            val count = if (mode == 2 && position > 0 && page + 1 < pageCount) 2 else 1
            repeat(count) { offset ->
                val index = page + offset
                val width = (parent.width / count).coerceAtLeast(1)
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
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    tag = index
                    layoutParams = LinearLayout.LayoutParams(0, (width * 1.4f).toInt().coerceAtLeast(1), 1f)
                }
                row.addView(image)
                fun display(bitmap: Bitmap) {
                    image.layoutParams = LinearLayout.LayoutParams(0, (width.toLong() * bitmap.height / bitmap.width).toInt().coerceAtLeast(1), 1f)
                    image.setImageBitmap(bitmap)
                    if (pendingTarget == index) {
                        val generation = navigationGeneration
                        pendingTarget = null
                        list.post { if (!closed && generation == navigationGeneration) list.setSelectionFromTop(rowForPage(index), 0) }
                    }
                }
                val cached = cache.get(index)
                if (cached != null) display(cached) else if (!searching) worker.execute {
                    if (!closed) {
                        try {
                            val bitmap = drawPage(index)
                            if (!closed) runOnUiThread {
                                if (!closed) {
                                    cache.put(index, bitmap)
                                    if (image.parent === row && image.tag == index) display(bitmap)
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
