package org.minviewer.app

import android.app.Activity
import android.app.AlertDialog
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Gravity
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.ImageView
import android.widget.EditText
import android.text.InputType
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.FrameLayout
import org.libreoffice.kit.Document
import org.libreoffice.kit.LibreOfficeKit
import org.libreoffice.kit.Office
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OfficeActivity : Activity() {
    private data class ViewPosition(val page: Int, val zoom: Float, val x: Int, val y: Int)
    companion object {
        private val worker = Executors.newSingleThreadExecutor()
        private val activeFiles = mutableSetOf<File>()
        private var engineCachePrepared = false
        private var activeActivities = 0
    }
    private lateinit var status: TextView
    private lateinit var image: ImageView
    private lateinit var slideList: TextView
    private lateinit var viewport: ScrollView
    private lateinit var sheetLeftHint: TextView
    private lateinit var sheetRightHint: TextView
    private lateinit var contentRow: LinearLayout
    private var closeSlidePanel: (() -> Unit)? = null
    private var slidePanelGrid: GridView? = null
    private var slidePanelSelection: Int? = null
    private var restoreSlidePanel = false
    private var slidePanelAnimator: ValueAnimator? = null
    private var slidePanelAnimating = false
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
    private var swipeAnimationTarget = -1
    private var swipeAnimationDirection = 0
    private var touchInDocument = false
    private lateinit var scaleDetector: ScaleGestureDetector
    private var pageLimit = " · 시험용"
    private var searchMessage = ""
    private var searchQuery = ""
    @Volatile private var searchPending = false
    @Volatile private var searchResultReceived = false
    @Volatile private var searchFallbackAttempted = false
    @Volatile private var searchFallbackScheduled = false
    private var searchPart = 0
    private var searchSheetsVisited = 0
    private var searchPrevious: IntArray? = null
    private var highlightPage = -1
    private var highlight: IntArray? = null
    private var pages = emptyList<IntArray>()
    private var sheetNames = emptyList<String>()
    private var tilePixels: ByteBuffer? = null
    @Volatile private var closed = false
    private var passwordLatch: CountDownLatch? = null
    private var passwordAttempts = 0
    private var openedSuccessfully = false
    private var recentRegistered = false
    private var lastSavedView: ViewPosition? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.apply(this)
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (ThemePreferences.isDark(this)) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        synchronized(OfficeActivity::class.java) { activeActivities++ }
        pageIndex = savedInstanceState?.getInt("page") ?: intent.getIntExtra("restore_page", 0)
        viewportX = savedInstanceState?.getInt("viewportX") ?: intent.getIntExtra("restore_x", 0)
        viewportY = savedInstanceState?.getInt("viewportY") ?: intent.getIntExtra("restore_y", 0)
        zoom = savedInstanceState?.getFloat("zoom") ?: intent.getFloatExtra("restore_zoom", 1f)
        restoreSlidePanel = savedInstanceState?.getBoolean("slidePanel") ?: false
        val controls = ViewerControls(this, intent.getStringExtra("name") ?: "문서", { finish() }, { searchDocument() },
            {
                when (documentType) {
                    Document.DOCTYPE_SPREADSHEET -> showSheetList()
                    Document.DOCTYPE_TEXT -> showWordPageList()
                    else -> showSlideList()
                }
            },
            { showPage(pageIndex - 1) }, { showPage(pageIndex + 1) },
            { setZoom(zoom / 1.5f) }, { setZoom(zoom * 1.5f) }, listOf("다음 검색 결과" to { searchNext() }))
        status = controls.status
        slideList = controls.listButton.apply { visibility = View.GONE }
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                wasScaling = true
                setZoom(zoom * kotlin.math.sqrt(detector.scaleFactor), detector.focusX, detector.focusY)
                return true
            }
        }).apply { isQuickScaleEnabled = false }
        image = ImageView(this).apply {
            adjustViewBounds = true
            contentDescription = "문서 화면"
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchX = event.x; touchY = event.y; wasScaling = false
                        view.parent.requestDisallowInterceptTouchEvent(true)
                        true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> { wasScaling = true; view.parent.requestDisallowInterceptTouchEvent(true); true }
                    MotionEvent.ACTION_MOVE -> true
                    MotionEvent.ACTION_UP -> {
                        val dx = event.x - touchX
                        val dy = event.y - touchY
                        val horizontalSwipe = kotlin.math.abs(dx) > 48 * resources.displayMetrics.density &&
                            kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.25f
                        if (!wasScaling && documentType != Document.DOCTYPE_SPREADSHEET && zoom <= 1f && horizontalSwipe) {
                            val direction = if (dx < 0) 1 else -1
                            val target = pageIndex + direction
                            if (target in 0 until pageCount) {
                                swipeAnimationTarget = target
                                swipeAnimationDirection = direction
                                showPage(target)
                            }
                        } else if (!wasScaling && (zoom > 1f || documentType == Document.DOCTYPE_SPREADSHEET) && view.width > 0 && view.height > 0 && (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10)) {
                            showPage(pageIndex, viewportX - (dx * tileWidth / view.width).toInt(), viewportY - (dy * tileHeight / view.height).toInt())
                        } else if (!wasScaling && kotlin.math.abs(dx) <= 10 && kotlin.math.abs(dy) <= 10) view.performClick()
                        true
                    }
                    else -> true
                }
            }
        }
        viewport = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(if (ThemePreferences.isDark(this@OfficeActivity)) Color.rgb(41, 47, 51) else Color.rgb(232, 235, 239))
            addView(image, android.widget.FrameLayout.LayoutParams(-1, -1))
            addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                val width = right - left
                val height = bottom - top
                val changed = width != viewportWidth || height != viewportHeight
                viewportWidth = width
                viewportHeight = height
                if (documentType != Document.DOCTYPE_SPREADSHEET && height > 0) {
                    image.layoutParams = android.widget.FrameLayout.LayoutParams(-1, height)
                    image.scaleType = ImageView.ScaleType.FIT_CENTER
                }
                if (changed && !slidePanelAnimating && pageCount > 0 && baseWidth > 0) showPage(pageIndex, viewportX, viewportY)
            }
        }
        val documentArea = FrameLayout(this).apply {
            addView(viewport, FrameLayout.LayoutParams(-1, -1))
            fun sheetHint(label: String, description: String, edge: Int): TextView = TextView(this@OfficeActivity).apply {
                text = label
                contentDescription = description
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(155, 25, 45, 77))
                visibility = View.GONE
                addView(this, FrameLayout.LayoutParams((24 * resources.displayMetrics.density).toInt(),
                    (48 * resources.displayMetrics.density).toInt(), edge or Gravity.CENTER_VERTICAL))
            }
            sheetLeftHint = sheetHint("‹", "왼쪽에 시트 영역 있음", Gravity.START)
            sheetRightHint = sheetHint("›", "오른쪽에 시트 영역 있음", Gravity.END)
        }
        contentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(documentArea, LinearLayout.LayoutParams(0, -1, 1f))
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
        val uri = intent.data
        val extension = intent.getStringExtra("name")?.substringAfterLast('.', "")?.lowercase()
        if (uri == null || extension !in listOf("docx", "xlsx", "pptx")) {
            status.text = "LibreOffice 오류: 지원하지 않는 문서입니다."
            return
        }
        status.postDelayed({
            val waitingForPassword = synchronized(this) { passwordLatch != null }
            if (!closed && !openedSuccessfully && !waitingForPassword && status.text == "불러오는 중") {
                status.text = "열기 지연 · 뒤로 닫기"
            }
        }, 20000)
        worker.execute {
            try {
                cacheDir.listFiles()?.filter { it.isFile && it.name.startsWith("asset-") && it.name.endsWith(".tmp") }?.forEach { it.delete() }
                @Suppress("DEPRECATION")
                val updatedAt = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
                val assetPreferences = getSharedPreferences("office-assets", MODE_PRIVATE)
                val refreshAssets = assetPreferences.getLong("updated-at", -1) != updatedAt
                copyAssets("unpack", File(applicationInfo.dataDir), refreshAssets)
                if (refreshAssets && !assetPreferences.edit().putLong("updated-at", updatedAt).commit()) {
                    throw IOException("엔진 자산 상태를 저장할 수 없습니다.")
                }
                cacheDir.listFiles()?.filter { it.isFile && it.name.startsWith("lok-") && it !in activeFiles }?.forEach { it.delete() }
                val file = File.createTempFile("lok-", ".$extension", cacheDir)
                temporaryFile = file
                activeFiles.add(file)
                val input = contentResolver.openInputStream(uri) ?: throw IOException("파일을 읽을 수 없습니다.")
                input.use { source -> file.outputStream().use { target -> source.copyTo(target) } }
                if (closed) return@execute
                val header = ByteArray(32)
                val count = file.inputStream().use { it.read(header) }
                val signature = header.copyOfRange(0, 4)
                val zip = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
                val compound = byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(),
                    0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte())
                val isCompound = count >= 8 && header.copyOfRange(0, 8).contentEquals(compound)
                if (count < 4 || (!signature.contentEquals(zip) && !isCompound)) {
                    throw IOException("파일 내용이 형식과 다릅니다.")
                }
                if (!engineCachePrepared) {
                    val engineCache = File(cacheDir, "lok-engine")
                    if (engineCache.exists() && !engineCache.deleteRecursively()) throw IOException("이전 엔진 캐시를 정리할 수 없습니다.")
                    if (!engineCache.mkdirs()) throw IOException("엔진 캐시를 만들 수 없습니다.")
                    engineCachePrepared = true
                }
                configureUserFonts()
                LibreOfficeKit.init(this)
                val handle = LibreOfficeKit.getLibreOfficeKitHandle() ?: throw IOException("엔진을 시작할 수 없습니다.")
                val office = Office(handle)
                office.setMessageCallback { type, payload ->
                    if (type == Document.CALLBACK_DOCUMENT_PASSWORD) requestPassword(office, payload)
                }
                office.setOptionalFeatures(Document.LOK_FEATURE_DOCUMENT_PASSWORD)
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
                if (documentType == Document.DOCTYPE_SPREADSHEET) {
                    sheetNames = (0 until pageCount).map { opened.getPartName(it) }
                }
                runOnUiThread {
                    if (!closed) {
                        openedSuccessfully = true
                        setResult(RESULT_OK, Intent().setData(uri).putExtra("name", intent.getStringExtra("name")))
                    }
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
                if (!closed && documentType == Document.DOCTYPE_SPREADSHEET) runOnUiThread {
                    if (!closed) {
                        slideList.text = "시트"
                        slideList.contentDescription = "시트 목록"
                        slideList.visibility = View.VISIBLE
                    }
                }
                if (!closed && documentType == Document.DOCTYPE_TEXT && pages.isNotEmpty()) runOnUiThread {
                    if (!closed) {
                        slideList.text = "페이지"
                        slideList.contentDescription = "페이지 목록"
                        slideList.visibility = View.VISIBLE
                    }
                }
                drawPage(pageIndex.coerceIn(0, pageCount - 1), viewportX, viewportY, zoom, renderGeneration.incrementAndGet())
            } catch (error: Exception) {
                closeDocument()
                if (!closed) runOnUiThread { if (!closed) status.text = "LibreOffice 오류: ${error.message}" }
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val location = IntArray(2)
            if (::viewport.isInitialized) viewport.getLocationOnScreen(location)
            touchInDocument = ::viewport.isInitialized && event.rawX >= location[0] && event.rawX < location[0] + viewport.width &&
                event.rawY >= location[1] && event.rawY < location[1] + viewport.height
        }
        if (touchInDocument && ::scaleDetector.isInitialized) {
            scaleDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                wasScaling = true
                viewport.parent.requestDisallowInterceptTouchEvent(true)
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun configureUserFonts() {
        val engineFonts = File(applicationInfo.dataDir, "user/fonts")
        engineFonts.listFiles()?.filter { it.name.matches(Regex("min-user-[0-9a-f]{64}\\.ttf")) }?.forEach {
            if (!it.delete()) throw IOException("이전 사용자 글꼴 연결을 정리할 수 없습니다.")
        }
        val stored = UserFonts(this)
        val entries = stored.all()
        entries.forEach { entry ->
            android.system.Os.symlink(stored.file(entry.id).absolutePath, File(engineFonts, "min-user-${entry.id}.ttf").absolutePath)
        }
        val hasMalgun = entries.any { entry -> entry.families.any { it.equals("Malgun Gothic", true) || it == "맑은 고딕" } }
        val original = assets.open("unpack/etc/fonts/fonts.conf").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val marker = "<fontconfig>"
        if (!original.contains(marker)) throw IOException("엔진 글꼴 설정을 읽을 수 없습니다.")
        val userDirectory = android.text.TextUtils.htmlEncode(engineFonts.absolutePath)
        val aliases = if (hasMalgun) "" else """
            <alias><family>Malgun Gothic</family><prefer><family>Noto Sans CJK KR</family><family>Noto Sans KR</family></prefer></alias>
            <alias><family>맑은 고딕</family><prefer><family>Noto Sans CJK KR</family><family>Noto Sans KR</family></prefer></alias>
        """.trimIndent()
        val configured = original.replaceFirst(marker, "$marker\n<dir>$userDirectory</dir>\n$aliases")
        val fontConfig = File(applicationInfo.dataDir, "etc/fonts/fonts.conf")
        if (!fontConfig.isFile || fontConfig.readText(Charsets.UTF_8) != configured) fontConfig.writeText(configured, Charsets.UTF_8)
    }

    private fun requestPassword(office: Office, url: String) {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)
        val retry = synchronized(this) {
            if (closed) return
            passwordLatch = latch
            passwordAttempts++ > 0
        }
        runOnUiThread {
            if (closed) {
                latch.countDown()
            } else {
                val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
                AlertDialog.Builder(this).setTitle("문서 비밀번호")
                    .setMessage(if (retry) "비밀번호가 맞지 않습니다. 다시 입력해 주세요." else "비밀번호를 입력해 주세요.")
                    .setView(input).setCancelable(false)
                    .setNegativeButton("취소") { _, _ ->
                        setResult(RESULT_CANCELED, Intent().putExtra("password_cancelled", true))
                        latch.countDown()
                        finish()
                    }
                    .setPositiveButton("열기") { _, _ -> result.set(input.text.toString()); latch.countDown() }
                    .show()
                input.requestFocus()
            }
        }
        try {
            latch.await()
            office.setDocumentPassword(url, result.get())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            office.setDocumentPassword(url, null)
        } finally {
            synchronized(this) { if (passwordLatch === latch) passwordLatch = null }
        }
    }

    private fun saveResult() {
        if (openedSuccessfully) {
            setResult(RESULT_OK, Intent().setData(intent.data).putExtra("name", intent.getStringExtra("name"))
                .putExtra("view_page", pageIndex).putExtra("view_mode", "").putExtra("view_zoom", zoom)
                .putExtra("view_x", viewportX).putExtra("view_y", viewportY))
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

    private fun showPage(index: Int, x: Int = 0, y: Int = 0) {
        if (index !in 0 until pageCount) return
        if (index != swipeAnimationTarget) swipeAnimationTarget = -1
        if (closeSlidePanel != null) slidePanelSelection = index
        val request = renderGeneration.incrementAndGet()
        val requestedZoom = zoom
        worker.execute {
            try { drawPage(index, x, y, requestedZoom, request) } catch (error: Exception) {
                if (!closed) runOnUiThread { if (!closed && request == renderGeneration.get()) status.text = "LibreOffice 오류: ${error.message}" }
            }
        }
    }

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
        val renderedHeight = maxOf(1, (fullHeight / requestedZoom).toInt())
        val left = if (sheet) 0 else rect[0]
        val top = if (sheet) 0 else rect[1]
        val right = if (sheet) width else rect[0] + rect[2]
        val bottom = if (sheet) height else rect[1] + rect[3]
        val x = requestedX.coerceIn(left, (right - renderedWidth).coerceAtLeast(left))
        val y = requestedY.coerceIn(top, (bottom - renderedHeight).coerceAtLeast(top))
        val (pixelWidth, pixelHeight) = documentRenderSize(renderedWidth, renderedHeight, availableWidth)
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
                val previousPage = pageIndex
                pageIndex = index
                if (closeSlidePanel != null) slidePanelSelection = index
                if (previousPage != index) slidePanelGrid?.invalidateViews()
                viewportX = x
                viewportY = y
                tileWidth = renderedWidth
                tileHeight = renderedHeight
                baseWidth = fullWidth
                baseHeight = fullHeight
                if (documentType == Document.DOCTYPE_SPREADSHEET && image.layoutParams.height != -2) {
                    image.layoutParams = android.widget.FrameLayout.LayoutParams(-1, -2)
                }
                image.setImageBitmap(bitmap)
                sheetLeftHint.visibility = if (sheet && x > left) View.VISIBLE else View.GONE
                sheetRightHint.visibility = if (sheet && x.toLong() + renderedWidth < right.toLong()) View.VISIBLE else View.GONE
                if (index == swipeAnimationTarget) {
                    swipeAnimationTarget = -1
                    image.animate().cancel()
                    image.translationX = swipeAnimationDirection * 36 * resources.displayMetrics.density
                    image.alpha = 0.75f
                    image.animate().translationX(0f).alpha(1f).setDuration(180).start()
                }
                pageLimit = when {
                    documentType == Document.DOCTYPE_SPREADSHEET -> " · 시험용: 끌어서 이동"
                    else -> " · 시험용"
                }
                updateStatus()
            }
        }
    }

    private fun setZoom(value: Float, focusX: Float? = null, focusY: Float? = null) {
        val next = value.coerceIn(1f, 4f)
        if (next == zoom) return
        val previousZoom = zoom
        zoom = next
        if (pageCount > 0 && baseWidth > 0 && baseHeight > 0) {
            val nextWidth = maxOf(1, (baseWidth / next).toInt())
            val availableWidth = if (viewportWidth > 0) viewportWidth else resources.displayMetrics.widthPixels
            val availableHeight = if (viewportHeight > 0) viewportHeight else resources.displayMetrics.heightPixels
            val nextHeight = maxOf(1, (baseHeight / next).toInt())
            val oldWidth = maxOf(1, (baseWidth / previousZoom).toInt())
            val oldHeight = maxOf(1, (baseHeight / previousZoom).toInt())
            val location = IntArray(2)
            image.getLocationInWindow(location)
            val drawable = image.drawable
            val area = if (drawable != null) {
                android.graphics.RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat()).also {
                    image.imageMatrix.mapRect(it)
                    it.offset(location[0].toFloat() + image.paddingLeft, location[1].toFloat() + image.paddingTop)
                }
            } else android.graphics.RectF(location[0].toFloat(), location[1].toFloat(),
                (location[0] + availableWidth).toFloat(), (location[1] + availableHeight).toFloat())
            val fx = focusX?.let { ((it - area.left) / area.width()).coerceIn(0f, 1f) } ?: 0.5f
            val fy = focusY?.let { ((it - area.top) / area.height()).coerceIn(0f, 1f) } ?: 0.5f
            viewportX += ((oldWidth - nextWidth) * fx).toInt()
            viewportY += ((oldHeight - nextHeight) * fy).toInt()
            showPage(pageIndex, viewportX, viewportY)
            updateStatus()
        }
    }

    private fun updateStatus() {
        status.text = "${pageIndex + 1}/$pageCount · ${(zoom * 100).toInt()}%"
        status.contentDescription = "LibreOffice ${pageIndex + 1} / $pageCount$pageLimit · ${(zoom * 100).toInt()}%$searchMessage"
        val uri = intent.data ?: return
        if (!openedSuccessfully) return
        val currentView = ViewPosition(pageIndex, zoom, viewportX, viewportY)
        if (recentRegistered && currentView == lastSavedView) return
        val state = Bundle().apply {
            putString("uri", uri.toString())
            putString("name", intent.getStringExtra("name") ?: "document")
            putBoolean("opened", !recentRegistered)
            putInt("page", pageIndex)
            putString("mode", "")
            putFloat("zoom", zoom)
            putInt("x", viewportX)
            putInt("y", viewportY)
        }
        contentResolver.call(android.net.Uri.parse("content://$packageName.viewstate"), "save", null, state)
        recentRegistered = true
        lastSavedView = currentView
    }

    private fun showSlideList() {
        if (documentType != Document.DOCTYPE_PRESENTATION || pageCount == 0 || slidePanelAnimating) return
        closeSlidePanel?.let { it(); return }
        val wide = resources.configuration.screenWidthDp >= 840
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
                val current = if (wide) slidePanelSelection ?: pageIndex else selected
                val item = (recycled as? LinearLayout ?: LinearLayout(this@OfficeActivity)).apply {
                    orientation = LinearLayout.VERTICAL
                    contentDescription = "미리보기 ${position + 1}번 슬라이드" + if (position == current) ", 선택됨" else ""
                    setBackgroundColor(if (position == current) Color.rgb(214, 230, 250) else Color.TRANSPARENT)
                    removeAllViews()
                    layoutParams = AbsListView.LayoutParams(-1, ((if (wide) 160 else 116) * resources.displayMetrics.density).toInt())
                }
                item.addView(ImageView(this@OfficeActivity).apply {
                    if (position == current) {
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(Color.WHITE)
                            setStroke((3 * resources.displayMetrics.density).toInt().coerceAtLeast(1), Color.rgb(140, 191, 245))
                        }
                        val inset = (3 * resources.displayMetrics.density).toInt()
                        setPadding(inset, inset, inset, inset)
                    } else setBackgroundColor(Color.LTGRAY)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(cache.get(position))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(-1, 0, 1f))
                item.addView(TextView(this@OfficeActivity).apply {
                    text = if (position in failed) "미리보기 실패" else "${position + 1}번"
                    if (position == current) setTextColor(Color.rgb(25, 45, 77))
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
                                val byteCount = pixelWidth * pixelHeight * 4
                                val pixels = tilePixels?.takeIf { it.capacity() >= byteCount }
                                    ?: ByteBuffer.allocateDirect(byteCount).also { tilePixels = it }
                                pixels.clear()
                                pixels.limit(byteCount)
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
            numColumns = if (wide) 1 else GridView.AUTO_FIT
            columnWidth = (96 * resources.displayMetrics.density).toInt()
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            this.adapter = adapter
        }
        val closeList = {
            val target = if (grid.parent === contentRow) slidePanelSelection ?: pageIndex else selected
            active.set(false)
            val finishClose = {
                cache.evictAll()
                if (grid.parent === contentRow) contentRow.removeView(grid)
                slidePanelGrid = null
                closeSlidePanel = null
                slidePanelSelection = null
                if (!closed) {
                    if (target == pageIndex) showPage(target, viewportX, viewportY) else showPage(target)
                }
            }
            if (!closed && grid.parent === contentRow) {
                animateSlidePanel(grid, grid.width, 0, finishClose)
            } else {
                finishClose()
            }
        }
        if (wide) {
            closeSlidePanel = closeList
            slidePanelGrid = grid
            slidePanelSelection = pageIndex
            val width = (240 * resources.displayMetrics.density).toInt()
            contentRow.addView(grid, 0, LinearLayout.LayoutParams(0, -1))
            grid.setOnItemClickListener { _, _, position, _ -> selected = position; showPage(position); grid.invalidateViews() }
            animateSlidePanel(grid, 0, width) {
                if (!closed && active.get()) showPage(pageIndex, viewportX, viewportY)
            }
        } else {
            val dialog = AlertDialog.Builder(this).setTitle("슬라이드 목록").setView(grid).setNegativeButton("닫기", null).create()
            grid.setOnItemClickListener { _, _, position, _ -> selected = position; dialog.dismiss() }
            dialog.setOnDismissListener { closeList() }
            dialog.show()
        }
    }

    private fun animateSlidePanel(grid: GridView, from: Int, to: Int, finished: () -> Unit) {
        slidePanelAnimating = true
        slidePanelAnimator = ValueAnimator.ofInt(from, to).apply {
            duration = 220
            addUpdateListener { animator ->
                grid.layoutParams = grid.layoutParams.apply { width = animator.animatedValue as Int }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    slidePanelAnimating = false
                    slidePanelAnimator = null
                    finished()
                }
            })
            start()
        }
    }

    private fun showSheetList() {
        if (sheetNames.isEmpty()) return
        val dialog = AlertDialog.Builder(this).setTitle("시트 목록")
            .setSingleChoiceItems(sheetNames.toTypedArray(), pageIndex) { dialog, position ->
                dialog.dismiss()
                if (position != pageIndex) showPage(position)
            }
            .setNegativeButton("닫기", null)
            .create()
        dialog.show()
    }

    private fun showWordPageList() {
        if (pages.isEmpty()) return
        val adapter = object : BaseAdapter() {
            override fun getCount() = pageCount
            override fun getItem(position: Int) = position
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                val item = recycled as? CheckedTextView
                    ?: layoutInflater.inflate(android.R.layout.simple_list_item_single_choice, parent, false) as CheckedTextView
                item.text = "${position + 1}쪽"
                return item
            }
        }
        AlertDialog.Builder(this).setTitle("페이지 목록")
            .setSingleChoiceItems(adapter, pageIndex) { dialog, position ->
                dialog.dismiss()
                if (position != pageIndex) showPage(position)
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun searchDocument() {
        if (pageCount == 0 || searchPending) return
        val input = EditText(this).apply { setSingleLine(); hint = "검색어"; setText(searchQuery) }
        val title = if (documentType == Document.DOCTYPE_SPREADSHEET) "전체 시트 검색" else "문서 검색"
        AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("취소", null)
            .setPositiveButton("찾기") { _, _ ->
                val query = input.text.toString()
                if (query.isNotBlank()) {
                    searchQuery = query
                    highlight = null
                    performSearch(0, 0, findAll = documentType == Document.DOCTYPE_SPREADSHEET)
                }
            }.show()
    }

    private fun searchNext() {
        val previous = highlight
        if (searchQuery.isEmpty() || previous == null) {
            android.widget.Toast.makeText(this, "먼저 문서를 검색해 주세요", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (documentType == Document.DOCTYPE_SPREADSHEET && highlightPage != pageIndex) {
            highlight = null
            performSearch(0, 0, findAll = true)
            return
        }
        performSearch(previous[0] + previous[2], previous[1] + previous[3] / 2)
    }

    private fun performSearch(startX: Int, startY: Int, findAll: Boolean = false, nextPart: Int? = null) {
        if (searchPending && !findAll && nextPart == null) return
        if ((!findAll || !searchPending) && nextPart == null) {
            searchPending = true
            searchFallbackAttempted = false
            searchFallbackScheduled = false
            searchPart = if (highlight != null) highlightPage else pageIndex
            searchSheetsVisited = 1
            searchPrevious = highlight?.copyOf()
        } else if (nextPart != null) {
            searchPart = nextPart
            searchPrevious = null
            searchFallbackAttempted = false
            searchFallbackScheduled = false
        }
        searchMessage = " · 검색 중"
        updateStatus()
        val part = searchPart
        val query = searchQuery
        worker.execute {
            try {
                fun property(type: String, value: String) = JSONObject().put("type", type).put("value", value)
                val args = JSONObject()
                    .put("SearchItem.SearchString", property("string", query))
                    .put("SearchItem.Backward", property("boolean", "false"))
                    .put("SearchItem.SearchStartPointX", property("long", startX.toString()))
                    .put("SearchItem.SearchStartPointY", property("long", startY.toString()))
                    .put("SearchItem.Command", property("long", if (findAll) "1" else "0"))
                val active = document ?: throw IOException("문서가 닫혔습니다.")
                if (!closed) {
                    if (findAll) searchFallbackScheduled = false
                    searchResultReceived = false
                    if (documentType != Document.DOCTYPE_TEXT) active.setPart(part)
                    active.postUnoCommand(".uno:ExecuteSearch", args.toString(), true)
                }
            } catch (error: Exception) {
                if (!closed) runOnUiThread {
                    if (!closed) { searchPending = false; status.text = "검색 오류: ${error.message}" }
                }
            }
        }
    }

    private fun searchNextSheet(): Boolean {
        if (documentType != Document.DOCTYPE_SPREADSHEET || searchSheetsVisited >= pageCount) return false
        searchSheetsVisited++
        val next = (searchPart + 1) % pageCount
        if (!closed) runOnUiThread { if (!closed) performSearch(0, 0, findAll = true, nextPart = next) }
        return true
    }

    private fun handleSearchResult(type: Int, payload: String) {
        if (closed) return
        if (type == Document.CALLBACK_SEARCH_RESULT_SELECTION) searchResultReceived = true
        val commandFailed = if (type == Document.CALLBACK_UNO_COMMAND_RESULT) {
            try {
                val result = JSONObject(payload)
                result.optString("commandName") == ".uno:ExecuteSearch" && result.has("success") && !result.getBoolean("success")
            } catch (_: org.json.JSONException) { false }
        } else false
        if (type == Document.CALLBACK_SEARCH_NOT_FOUND || commandFailed) {
            worker.execute {
                if (!searchPending || (commandFailed && searchResultReceived)) return@execute
                if (searchFallbackScheduled) return@execute
                if (documentType == Document.DOCTYPE_SPREADSHEET && !searchFallbackAttempted) {
                    searchFallbackAttempted = true
                    searchFallbackScheduled = true
                    if (!closed) runOnUiThread { if (!closed) performSearch(0, 0, true) }
                    return@execute
                }
                if (searchNextSheet()) return@execute
                highlight = null
                if (!closed) runOnUiThread {
                    if (!closed) {
                        searchMessage = if (commandFailed) " · 검색 결과 없음 또는 검색 실패" else " · 검색 결과 없음"
                        searchPending = false
                        val message = if (commandFailed) "검색 결과가 없거나 검색을 완료하지 못했습니다" else "검색 결과가 없습니다"
                        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
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
                    val previous = searchPrevious
                    if (documentType == Document.DOCTYPE_SPREADSHEET && part == searchPart && previous != null &&
                        (coords[1] < previous[1] || (coords[1] == previous[1] && coords[0] <= previous[0]))) {
                        if (searchNextSheet()) return@execute
                    }
                    val target = if (documentType == Document.DOCTYPE_TEXT && pages.isNotEmpty()) {
                        pages.indexOfFirst { coords[1] >= it[1] && coords[1].toLong() < it[1].toLong() + it[3] }.coerceAtLeast(0)
                    } else part
                    if (target !in 0 until pageCount) throw IOException("검색 페이지를 확인할 수 없습니다.")
                    highlightPage = target
                    highlight = coords
                    if (!closed) runOnUiThread {
                        if (!closed) {
                            searchMessage = " · 검색 결과"
                            searchPending = false
                            android.widget.Toast.makeText(this, "검색 결과를 표시합니다", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    drawPage(target, coords[0], coords[1], zoom, renderGeneration.incrementAndGet())
                } catch (error: Exception) {
                    if (!closed) runOnUiThread { if (!closed) { searchPending = false; status.text = "검색 오류: ${error.message}" } }
                }
            }
        }
    }

    private fun copyAssets(path: String, destination: File, replace: Boolean) {
        val children = assets.list(path) ?: throw IOException("엔진 자산을 읽을 수 없습니다.")
        if (children.isNotEmpty()) {
            if (!destination.isDirectory && !destination.mkdirs()) throw IOException("엔진 폴더를 만들 수 없습니다.")
            children.forEach { copyAssets("$path/$it", File(destination, it), replace) }
        } else if (replace || !destination.exists()) {
            val temporary = File.createTempFile("asset-", ".tmp", cacheDir)
            try {
                assets.open(path).use { source -> temporary.outputStream().use { target -> source.copyTo(target) } }
                if (!temporary.renameTo(destination)) throw IOException("엔진 파일을 준비할 수 없습니다.")
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }
    }

    override fun onDestroy() {
        closed = true
        synchronized(this) { passwordLatch?.countDown() }
        renderGeneration.incrementAndGet()
        slidePanelAnimator?.cancel()
        closeSlidePanel?.invoke()
        val finishing = isFinishing
        synchronized(OfficeActivity::class.java) { activeActivities-- }
        super.onDestroy()
        // 엔진이 문서 열기에서 멈춰 정리 작업이 실행되지 않는 경우 대비
        if (finishing) Handler(Looper.getMainLooper()).postDelayed({
            synchronized(OfficeActivity::class.java) {
                if (activeActivities == 0) android.os.Process.killProcess(android.os.Process.myPid())
            }
        }, 500)
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
