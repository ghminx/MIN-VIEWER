package org.minviewer.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Button
import android.widget.ImageView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.GridView
import org.libreoffice.kit.Document
import org.libreoffice.kit.LibreOfficeKit
import org.libreoffice.kit.Office
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class OfficeActivity : Activity() {
    companion object {
        private val worker = Executors.newSingleThreadExecutor()
        private val activeFiles = mutableSetOf<File>()
        private var engineCachePrepared = false
        private var activeActivities = 0
    }
    private lateinit var status: TextView
    private lateinit var image: ImageView
    private lateinit var slideList: Button
    private lateinit var viewport: ScrollView
    private lateinit var contentRow: LinearLayout
    private var closeSlidePanel: (() -> Unit)? = null
    private var slidePanelSelection: Int? = null
    private var restoreSlidePanel = false
    private var document: Document? = null
    private var temporaryFile: File? = null
    private var documentType = -1
    private var pageCount = 0
    private var pageIndex = 0
    private var viewportX = 0
    private var viewportY = 0
    private var touchX = 0f
    private var touchY = 0f
    @Volatile private var zoom = 1f
    private val renderGeneration = AtomicInteger()
    private var tileWidth = 0
    private var tileHeight = 0
    private var baseWidth = 0
    private var baseHeight = 0
    @Volatile private var viewportWidth = 0
    @Volatile private var viewportHeight = 0
    private var wasScaling = false
    private var pageLimit = " · 시험용"
    private var searchMessage = ""
    private var highlightPage = -1
    private var highlight: IntArray? = null
    private var pages = emptyList<IntArray>()
    private var tilePixels: ByteBuffer? = null
    @Volatile private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        synchronized(OfficeActivity::class.java) { activeActivities++ }
        pageIndex = savedInstanceState?.getInt("page") ?: 0
        viewportX = savedInstanceState?.getInt("viewportX") ?: 0
        viewportY = savedInstanceState?.getInt("viewportY") ?: 0
        zoom = savedInstanceState?.getFloat("zoom") ?: 1f
        restoreSlidePanel = savedInstanceState?.getBoolean("slidePanel") ?: false
        val toolbar = LinearLayout(this)
        listOf(
            "뒤로" to { finish() },
            "이전" to { showPage(pageIndex - 1) },
            "다음" to { showPage(pageIndex + 1) },
        ).forEach { (label, action) ->
            toolbar.addView(Button(this).apply { text = label; setOnClickListener { action() } }, toolLayout())
        }
        status = TextView(this).apply { text = "LibreOffice 시험 문서를 불러오는 중입니다." }
        val zoomTools = LinearLayout(this)
        listOf("축소" to { setZoom(zoom / 1.5f) }, "확대" to { setZoom(zoom * 1.5f) }).forEach { (label, action) ->
            zoomTools.addView(Button(this).apply { text = label; setOnClickListener { action() } }, toolLayout())
        }
        zoomTools.addView(Button(this).apply { text = "검색"; setOnClickListener { searchDocument() } }, toolLayout())
        slideList = Button(this).apply { text = "목록"; visibility = View.GONE; setOnClickListener { showSlideList() } }
        zoomTools.addView(slideList, toolLayout())
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                wasScaling = true
                setZoom(zoom * detector.scaleFactor)
                return true
            }
        })
        image = ImageView(this).apply {
            adjustViewBounds = true
            contentDescription = "문서 화면"
            setOnTouchListener { view, event ->
                scaleDetector.onTouchEvent(event)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchX = event.x; touchY = event.y; wasScaling = false
                        if (zoom > 1f || documentType == Document.DOCTYPE_SPREADSHEET) view.parent.requestDisallowInterceptTouchEvent(true)
                        true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> { wasScaling = true; view.parent.requestDisallowInterceptTouchEvent(true); true }
                    MotionEvent.ACTION_MOVE -> true
                    MotionEvent.ACTION_UP -> {
                        val dx = event.x - touchX
                        val dy = event.y - touchY
                        if (!wasScaling && (zoom > 1f || documentType == Document.DOCTYPE_SPREADSHEET) && view.width > 0 && view.height > 0 && (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10)) {
                            showPage(pageIndex, viewportX - (dx * tileWidth / view.width).toInt(), viewportY - (dy * tileHeight / view.height).toInt())
                        } else if (!wasScaling && kotlin.math.abs(dx) <= 10 && kotlin.math.abs(dy) <= 10) view.performClick()
                        true
                    }
                    else -> true
                }
            }
        }
        viewport = ScrollView(this).apply {
            addView(image, android.widget.FrameLayout.LayoutParams(-1, -2))
            addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                val width = right - left
                val height = bottom - top
                val changed = width != viewportWidth || height != viewportHeight
                viewportWidth = width
                viewportHeight = height
                if (changed && pageCount > 0 && zoom > 1f && baseWidth > 0) showPage(pageIndex, viewportX, viewportY)
            }
        }
        contentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(viewport, LinearLayout.LayoutParams(0, -1, 1f))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
            addView(toolbar)
            addView(zoomTools)
            addView(status)
            addView(contentRow, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        val uri = intent.data
        val extension = intent.getStringExtra("name")?.substringAfterLast('.', "")?.lowercase()
        if (uri == null || extension !in listOf("docx", "xlsx", "pptx")) {
            status.text = "LibreOffice 오류: 지원하지 않는 문서입니다."
            return
        }
        worker.execute {
            try {
                copyAssets("unpack", File(applicationInfo.dataDir))
                cacheDir.listFiles()?.filter { it.isFile && it.name.startsWith("lok-") && it !in activeFiles }?.forEach { it.delete() }
                val file = File.createTempFile("lok-", ".$extension", cacheDir)
                temporaryFile = file
                activeFiles.add(file)
                val input = contentResolver.openInputStream(uri) ?: throw IOException("파일을 읽을 수 없습니다.")
                input.use { source -> file.outputStream().use { target -> source.copyTo(target) } }
                if (closed) return@execute
                val signature = ByteArray(4)
                val count = file.inputStream().use { it.read(signature) }
                if (count != 4 || !signature.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) {
                    throw IOException("파일 내용이 형식과 다르거나 암호화되어 있습니다.")
                }
                if (!engineCachePrepared) {
                    val engineCache = File(cacheDir, "lok-engine")
                    if (engineCache.exists() && !engineCache.deleteRecursively()) throw IOException("이전 엔진 캐시를 정리할 수 없습니다.")
                    if (!engineCache.mkdirs()) throw IOException("엔진 캐시를 만들 수 없습니다.")
                    engineCachePrepared = true
                }
                LibreOfficeKit.init(this)
                val handle = LibreOfficeKit.getLibreOfficeKitHandle() ?: throw IOException("엔진을 시작할 수 없습니다.")
                val office = Office(handle)
                val opened = office.documentLoad(file.absolutePath) ?: throw IOException("문서를 열 수 없습니다.")
                document = opened
                opened.initializeForRendering()
                opened.setMessageCallback { type, payload -> handleSearchResult(type, payload) }
                documentType = opened.documentType
                val expectedType = when (extension) {
                    "docx" -> Document.DOCTYPE_TEXT
                    "xlsx" -> Document.DOCTYPE_SPREADSHEET
                    else -> Document.DOCTYPE_PRESENTATION
                }
                if (documentType != expectedType) throw IOException("파일 내용과 확장자가 일치하지 않습니다.")
                pages = if (documentType == Document.DOCTYPE_TEXT) {
                    opened.partPageRectangles.orEmpty().split(';').mapNotNull { part ->
                        val values = part.split(',').map { it.trim().toIntOrNull() }
                        if (values.size == 4 && values.all { it != null }) values.map { it!! }.toIntArray() else null
                    }.filter { it[2] > 0 && it[3] > 0 }
                } else emptyList()
                pageCount = if (pages.isNotEmpty()) pages.size else opened.parts
                if (pageCount <= 0) throw IOException("표시할 페이지가 없습니다.")
                runOnUiThread {
                    if (!closed) setResult(RESULT_OK, Intent().setData(uri).putExtra("name", intent.getStringExtra("name")))
                }
                if (!closed && documentType == Document.DOCTYPE_PRESENTATION) runOnUiThread {
                    if (!closed) {
                        slideList.visibility = View.VISIBLE
                        if (restoreSlidePanel) {
                            restoreSlidePanel = false
                            viewport.post { if (!closed) showSlideList() }
                        }
                    }
                }
                drawPage(pageIndex.coerceIn(0, pageCount - 1), viewportX, viewportY, zoom, renderGeneration.incrementAndGet())
            } catch (error: Exception) {
                closeDocument()
                if (!closed) runOnUiThread { if (!closed) status.text = "LibreOffice 오류: ${error.message}" }
            }
        }
    }

    private fun showPage(index: Int, x: Int = 0, y: Int = 0) {
        if (index !in 0 until pageCount) return
        if (closeSlidePanel != null) slidePanelSelection = index
        val request = renderGeneration.incrementAndGet()
        val requestedZoom = zoom
        worker.execute {
            try { drawPage(index, x, y, requestedZoom, request) } catch (error: Exception) {
                if (!closed) runOnUiThread { if (!closed && request == renderGeneration.get()) status.text = "LibreOffice 오류: ${error.message}" }
            }
        }
    }

    private fun toolLayout() = if (resources.configuration.screenWidthDp >= 600)
        LinearLayout.LayoutParams((96 * resources.displayMetrics.density).toInt(), -2)
    else LinearLayout.LayoutParams(0, -2, 1f)

    private fun drawPage(index: Int, requestedX: Int, requestedY: Int, requestedZoom: Float, request: Int) {
        if (closed || request != renderGeneration.get()) return
        val opened = document ?: throw IOException("문서가 닫혔습니다.")
        if (documentType != Document.DOCTYPE_TEXT) opened.setPart(index)
        val width = Math.toIntExact(opened.documentWidth)
        val height = Math.toIntExact(opened.documentHeight)
        if (width <= 0 || height <= 0) throw IOException("문서 크기를 확인할 수 없습니다.")
        val rect = pages.getOrNull(index) ?: intArrayOf(0, 0, width, height)
        val sheet = documentType == Document.DOCTYPE_SPREADSHEET
        val fullWidth = if (sheet) minOf(width, 6000) else rect[2]
        val fullHeight = if (sheet) minOf(height.toLong(), fullWidth.toLong() * 1600 / 960).toInt() else rect[3]
        val renderedWidth = maxOf(1, (fullWidth / requestedZoom).toInt())
        val availableWidth = if (viewportWidth > 0) viewportWidth else resources.displayMetrics.widthPixels
        val availableHeight = if (viewportHeight > 0) viewportHeight else resources.displayMetrics.heightPixels
        val renderedHeight = if (requestedZoom <= 1f) fullHeight else
            minOf(fullHeight.toLong(), maxOf(1L, renderedWidth.toLong() * availableHeight / availableWidth)).toInt()
        val left = if (sheet) 0 else rect[0]
        val top = if (sheet) 0 else rect[1]
        val right = if (sheet) width else rect[0] + rect[2]
        val bottom = if (sheet) height else rect[1] + rect[3]
        val x = requestedX.coerceIn(left, (right - renderedWidth).coerceAtLeast(left))
        val y = requestedY.coerceIn(top, (bottom - renderedHeight).coerceAtLeast(top))
        val pixelWidth = minOf(960L, maxOf(1L, 1600L * renderedWidth / renderedHeight)).toInt()
        val pixelHeight = maxOf(1, (pixelWidth.toLong() * renderedHeight / renderedWidth).toInt())
        val byteCount = pixelWidth * pixelHeight * 4
        val pixels = tilePixels?.takeIf { it.capacity() >= byteCount }
            ?: ByteBuffer.allocateDirect(byteCount).also { tilePixels = it }
        pixels.clear()
        pixels.limit(byteCount)
        opened.paintTile(pixels, pixelWidth, pixelHeight, x, y, renderedWidth, renderedHeight)
        if (closed || request != renderGeneration.get()) return
        val bitmap = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
        pixels.rewind()
        bitmap.copyPixelsFromBuffer(pixels)
        val selection = highlight
        if (selection != null && highlightPage == index) {
            val paint = Paint().apply { color = Color.argb(100, 255, 210, 0) }
            val factorX = pixelWidth.toFloat() / renderedWidth
            val factorY = pixelHeight.toFloat() / renderedHeight
            Canvas(bitmap).drawRect(
                (selection[0] - x) * factorX, (selection[1] - y) * factorY,
                (selection[0] + selection[2] - x) * factorX, (selection[1] + selection[3] - y) * factorY, paint,
            )
        }
        if (!closed) runOnUiThread {
            if (!closed && request == renderGeneration.get()) {
                pageIndex = index
                if (closeSlidePanel != null) slidePanelSelection = index
                viewportX = x
                viewportY = y
                tileWidth = renderedWidth
                tileHeight = renderedHeight
                baseWidth = fullWidth
                baseHeight = fullHeight
                image.setImageBitmap(bitmap)
                pageLimit = when {
                    documentType == Document.DOCTYPE_SPREADSHEET -> " · 시험용: 끌어서 이동"
                    else -> " · 시험용"
                }
                updateStatus()
            }
        }
    }

    private fun setZoom(value: Float) {
        val next = value.coerceIn(1f, 4f)
        if (next == zoom) return
        zoom = next
        if (pageCount > 0 && baseWidth > 0 && baseHeight > 0) {
            val nextWidth = maxOf(1, (baseWidth / next).toInt())
            val availableWidth = if (viewportWidth > 0) viewportWidth else resources.displayMetrics.widthPixels
            val availableHeight = if (viewportHeight > 0) viewportHeight else resources.displayMetrics.heightPixels
            val nextHeight = if (next <= 1f) baseHeight else
                minOf(baseHeight.toLong(), maxOf(1L, nextWidth.toLong() * availableHeight / availableWidth)).toInt()
            showPage(pageIndex, viewportX + (tileWidth - nextWidth) / 2, viewportY + (tileHeight - nextHeight) / 2)
            updateStatus()
        }
    }

    private fun updateStatus() {
        status.text = "LibreOffice ${pageIndex + 1} / $pageCount$pageLimit · ${(zoom * 100).toInt()}%$searchMessage"
    }

    private fun showSlideList() {
        if (documentType != Document.DOCTYPE_PRESENTATION || pageCount == 0) return
        closeSlidePanel?.let { it(); return }
        val active = AtomicBoolean(true)
        val cache = object : LruCache<Int, Bitmap>(4 * 1024 * 1024) {
            override fun sizeOf(key: Int, value: Bitmap) = value.allocationByteCount
        }
        val failed = mutableSetOf<Int>()
        var pending = false
        var selected = pageIndex
        lateinit var grid: GridView
        val adapter = object : BaseAdapter() {
            override fun getCount() = pageCount
            override fun getItem(position: Int) = position
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                val item = (recycled as? LinearLayout ?: LinearLayout(this@OfficeActivity)).apply {
                    orientation = LinearLayout.VERTICAL
                    contentDescription = "미리보기 ${position + 1}번 슬라이드"
                    removeAllViews()
                    layoutParams = AbsListView.LayoutParams(-1, (116 * resources.displayMetrics.density).toInt())
                }
                item.addView(ImageView(this@OfficeActivity).apply {
                    setBackgroundColor(Color.LTGRAY)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(cache.get(position))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(-1, 0, 1f))
                item.addView(TextView(this@OfficeActivity).apply {
                    text = if (position in failed) "미리보기 실패" else "${position + 1}번"
                    gravity = android.view.Gravity.CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                if (cache.get(position) == null && position !in failed && !pending) {
                    pending = true
                    worker.execute {
                        try {
                            if (!closed && active.get()) {
                                val opened = document ?: throw IOException("문서가 닫혔습니다.")
                                opened.setPart(position)
                                val width = Math.toIntExact(opened.documentWidth)
                                val height = Math.toIntExact(opened.documentHeight)
                                if (width <= 0 || height <= 0) throw IOException("슬라이드 크기를 확인할 수 없습니다.")
                                val pixelWidth = minOf(240L, maxOf(1L, 240L * width / height)).toInt()
                                val pixelHeight = maxOf(1L, pixelWidth.toLong() * height / width).toInt()
                                val pixels = ByteBuffer.allocateDirect(pixelWidth * pixelHeight * 4)
                                opened.paintTile(pixels, pixelWidth, pixelHeight, 0, 0, width, height)
                                val bitmap = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
                                pixels.rewind()
                                bitmap.copyPixelsFromBuffer(pixels)
                                if (!closed) runOnUiThread {
                                    if (!closed && active.get()) {
                                        cache.put(position, bitmap)
                                        pending = false
                                        grid.invalidateViews()
                                    }
                                }
                            }
                        } catch (error: Exception) {
                            if (!closed) runOnUiThread {
                                if (!closed && active.get()) {
                                    failed.add(position)
                                    pending = false
                                    grid.invalidateViews()
                                }
                            }
                        }
                    }
                }
                return item
            }
        }
        grid = GridView(this).apply {
            contentDescription = "슬라이드 목록"
            numColumns = GridView.AUTO_FIT
            columnWidth = (96 * resources.displayMetrics.density).toInt()
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            this.adapter = adapter
        }
        val closeList = {
            val target = if (grid.parent === contentRow) slidePanelSelection ?: pageIndex else selected
            active.set(false)
            cache.evictAll()
            if (grid.parent === contentRow) contentRow.removeView(grid)
            closeSlidePanel = null
            slidePanelSelection = null
            if (!closed) {
                if (target == pageIndex) showPage(target, viewportX, viewportY) else showPage(target)
            }
        }
        if (resources.configuration.screenWidthDp >= 840) {
            closeSlidePanel = closeList
            slidePanelSelection = pageIndex
            contentRow.addView(grid, 0, LinearLayout.LayoutParams((320 * resources.displayMetrics.density).toInt(), -1))
            grid.setOnItemClickListener { _, _, position, _ -> selected = position; showPage(position) }
            viewport.post { if (!closed && active.get()) showPage(pageIndex, viewportX, viewportY) }
        } else {
            val dialog = AlertDialog.Builder(this).setTitle("슬라이드 목록").setView(grid).setNegativeButton("닫기", null).create()
            grid.setOnItemClickListener { _, _, position, _ -> selected = position; dialog.dismiss() }
            dialog.setOnDismissListener { closeList() }
            dialog.show()
        }
    }

    private fun searchDocument() {
        if (pageCount == 0) return
        val input = EditText(this).apply { setSingleLine(); hint = "검색어" }
        AlertDialog.Builder(this).setTitle("문서 검색").setView(input).setNegativeButton("취소", null)
            .setPositiveButton("찾기") { _, _ ->
                val query = input.text.toString()
                if (query.isNotBlank()) {
                    searchMessage = " · 검색 중"
                    updateStatus()
                    worker.execute {
                        try {
                            fun property(type: String, value: String) = JSONObject().put("type", type).put("value", value)
                            val args = JSONObject()
                                .put("SearchItem.SearchString", property("string", query))
                                .put("SearchItem.Backward", property("boolean", "false"))
                                .put("SearchItem.SearchStartPointX", property("long", "0"))
                                .put("SearchItem.SearchStartPointY", property("long", "0"))
                                .put("SearchItem.Command", property("long", "0"))
                            val active = document ?: throw IOException("문서가 닫혔습니다.")
                            if (!closed) {
                                if (documentType == Document.DOCTYPE_PRESENTATION) active.setPart(pageIndex)
                                active.postUnoCommand(".uno:ExecuteSearch", args.toString(), false)
                            }
                        } catch (error: Exception) {
                            if (!closed) runOnUiThread { if (!closed) status.text = "검색 오류: ${error.message}" }
                        }
                    }
                }
            }.show()
    }

    private fun handleSearchResult(type: Int, payload: String) {
        if (closed) return
        if (type == Document.CALLBACK_SEARCH_NOT_FOUND) {
            worker.execute {
                highlight = null
                if (!closed) runOnUiThread { if (!closed) searchMessage = " · 검색 결과 없음" }
                try {
                    if (!closed) drawPage(pageIndex, viewportX, viewportY, zoom, renderGeneration.incrementAndGet())
                } catch (error: Exception) {
                    if (!closed) runOnUiThread { if (!closed) status.text = "검색 오류: ${error.message}" }
                }
            }
        } else if (type == Document.CALLBACK_SEARCH_RESULT_SELECTION) {
            worker.execute {
                try {
                    val first = JSONObject(payload).getJSONArray("searchResultSelection").getJSONObject(0)
                    val coords = first.getString("rectangles").substringBefore(';').split(',').map { it.trim().toInt() }.toIntArray()
                    if (coords.size != 4) throw IOException("검색 위치를 읽을 수 없습니다.")
                    val part = first.getInt("part")
                    val target = if (documentType == Document.DOCTYPE_TEXT && pages.isNotEmpty()) {
                        pages.indexOfFirst { coords[1] >= it[1] && coords[1].toLong() < it[1].toLong() + it[3] }.coerceAtLeast(0)
                    } else part
                    if (target !in 0 until pageCount) throw IOException("검색 페이지를 확인할 수 없습니다.")
                    highlightPage = target
                    highlight = coords
                    if (!closed) runOnUiThread { if (!closed) searchMessage = " · 검색 결과" }
                    drawPage(target, coords[0], coords[1], zoom, renderGeneration.incrementAndGet())
                } catch (error: Exception) {
                    if (!closed) runOnUiThread { if (!closed) status.text = "검색 오류: ${error.message}" }
                }
            }
        }
    }

    private fun copyAssets(path: String, destination: File) {
        val children = assets.list(path) ?: throw IOException("엔진 자산을 읽을 수 없습니다.")
        if (children.isNotEmpty()) {
            if (!destination.isDirectory && !destination.mkdirs()) throw IOException("엔진 폴더를 만들 수 없습니다.")
            children.forEach { copyAssets("$path/$it", File(destination, it)) }
        } else if (!destination.exists()) {
            assets.open(path).use { source -> destination.outputStream().use { target -> source.copyTo(target) } }
        }
    }

    override fun onDestroy() {
        closed = true
        renderGeneration.incrementAndGet()
        closeSlidePanel?.invoke()
        val finishing = isFinishing
        synchronized(OfficeActivity::class.java) { activeActivities-- }
        super.onDestroy()
        worker.execute {
            closeDocument()
            if (finishing) synchronized(OfficeActivity::class.java) {
                if (activeActivities == 0) android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun closeDocument() {
        document?.destroy()
        document = null
        tilePixels = null
        temporaryFile?.let { file ->
            file.delete()
            activeFiles.remove(file)
        }
        temporaryFile = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", pageIndex)
        outState.putInt("viewportX", viewportX)
        outState.putInt("viewportY", viewportY)
        outState.putFloat("zoom", zoom)
        outState.putBoolean("slidePanel", closeSlidePanel != null)
        super.onSaveInstanceState(outState)
    }
}
