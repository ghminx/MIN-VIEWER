package org.minviewer.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.AbsListView
import android.view.ViewGroup
import android.graphics.Bitmap
import android.util.LruCache
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var container: LinearLayout
    private lateinit var home: HomeScreen
    private lateinit var recent: RecentDocuments
    private var openingRecent = false
    private lateinit var content: LinearLayout
    private lateinit var list: ListView
    private var browser: WebView? = null
    private lateinit var emptyMessage: TextView
    private lateinit var indicator: TextView
    private lateinit var hwpSearch: Button
    private lateinit var hwpNextSearch: Button
    private lateinit var hwpModes: LinearLayout
    private lateinit var viewerControls: ViewerControls
    private var documentUri: Uri? = null
    private var pageCount = 0
    private var pageIndex = 0
    private var listVisible = false
    private val hwpThumbnails=object:LruCache<Int,Bitmap>(4*1024*1024){override fun sizeOf(key:Int,value:Bitmap)=value.allocationByteCount}
    private val pendingThumbnails=mutableSetOf<Int>()
    private val failedThumbnails=mutableSetOf<Int>()
    private var ready = false
    private var browserGeneration = 0
    private var pendingWebDocument = false
    private var selectedName = ""
    private var resumeAfterOffice = false
    private var autoOpenOffice = false
    private var incomingMimeType: String? = null
    private var loadGeneration = 0
    private var hwpMode = "single"
    private var hwpZoom = 1f
    private var hwpSearchMessage = ""
    private var hwpSearchQuery = ""
    private var pendingHwpZoomRestore = false
    private var swipeX = 0f
    private var swipeY = 0f
    private var swipeDocument = false
    private var swipeMultiTouch = false
    private val tapDetector by lazy {
        android.view.GestureDetector(this,object:android.view.GestureDetector.SimpleOnGestureListener(){
            override fun onSingleTapConfirmed(event:MotionEvent):Boolean {
                val view=browser?:return false
                if(hwpMode=="continuous"||hwpZoom>1.01f||pageCount==0)return false
                val location=IntArray(2);view.getLocationOnScreen(location)
                val x=event.rawX-location[0]
                val direction=ViewerControls.pageTapDirection(x,view.width)
                if(direction!=0)movePage(direction)
                return true
            }
        })
    }
    @Volatile private var documentSource: DocumentSource? = null

    private data class DocumentSource(val token: String, val uri: Uri)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.apply(this)
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (ThemePreferences.isDark(this)) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        documentUri = savedInstanceState?.getString("uri")?.let(Uri::parse)
        selectedName = savedInstanceState?.getString("name") ?: ""
        resumeAfterOffice = savedInstanceState?.getBoolean("resumeAfterOffice") ?: false
        pageIndex = if (savedInstanceState?.getBoolean("mobileWord") == true) savedInstanceState.getInt("originalWordPage")
            else savedInstanceState?.getInt("page") ?: 0
        listVisible = savedInstanceState?.getBoolean("list") ?: false
        hwpMode = savedInstanceState?.getString("hwpMode")?.takeIf { it in listOf("continuous", "single", "spread") } ?: "single"
        hwpZoom = savedInstanceState?.getFloat("hwpZoom") ?: 1f
        recent = RecentDocuments(this)
        buildScreen()
        home.restoreState(savedInstanceState)
        if (savedInstanceState == null) receiveDocument(intent)
        else if (!resumeAfterOffice) loadDocument()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveDocument(intent)
    }

    private fun receiveDocument(incoming: Intent) {
        viewerControls.closeSearch()
        val uri = when (incoming.action) {
            Intent.ACTION_VIEW -> incoming.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = if (android.os.Build.VERSION.SDK_INT >= 33) incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else incoming.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri
                stream ?: incoming.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
            }
            else -> return
        }
        loadGeneration++
        pendingWebDocument = false
        documentSource = null
        pageCount = 0
        list.adapter = null
        hwpSearch.visibility = View.GONE
        hwpNextSearch.visibility = View.GONE
        hwpModes.visibility = View.GONE
        if (ready) browser?.evaluateJavascript("window.resetDocument()", null)
        if (uri?.scheme != "content") {
            documentUri = null
            indicator.text = "공유된 파일을 열 수 없습니다"
            Toast.makeText(this, "공유된 파일을 열 수 없습니다", Toast.LENGTH_LONG).show()
            return
        }
        documentUri = uri
        openingRecent = false
        releaseBrowser()
        incomingMimeType = incoming.type
        pageIndex = 0
        hwpMode = "single"
        hwpZoom = 1f
        resumeAfterOffice = false
        autoOpenOffice = true
        loadDocument()
    }

    private fun buildScreen() {
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        hwpSearch = button("검색") { searchHwp() }.apply { visibility = View.GONE }
        hwpNextSearch = button("다음 결과") {
            val generation = loadGeneration
            browser?.evaluateJavascript("window.nextHwpResult()") { value -> showHwpSearchResult(value, generation) }
        }.apply { visibility = View.GONE }
        viewerControls = ViewerControls(this, "문서", { showHome() }, { if (hwpSearch.visibility == View.VISIBLE) searchHwp() },
            { listVisible = !listVisible; updateLayout() }, { movePage(-1) }, { movePage(1) },
            { browser?.zoomOut() }, { browser?.zoomIn() },
            listOf("한쪽 보기" to { changeHwpMode("single") }, "두쪽 보기" to { changeHwpMode("spread") },
                "연속 보기" to { changeHwpMode("continuous") },
                "다음 검색 결과" to { if (hwpNextSearch.visibility == View.VISIBLE) hwpNextSearch.performClick() },
                "다른 파일 열기" to { pickDocument() }),
            selectedViewMode={when(hwpMode){"continuous"->"연속 보기";"spread"->"두쪽 보기";else->"한쪽 보기"}},
            enabledMenuItem={name->when(name){"다음 검색 결과"->hwpNextSearch.visibility==View.VISIBLE;"이전 페이지"->pageIndex>0;"다음 페이지"->pageIndex<pageCount-1;else->pageCount>0}},onFit={browser?.zoomBy(1f/hwpZoom)})
        indicator = viewerControls.status
        viewerControls.setLocationAction {viewerControls.showPageJump(pageIndex,pageCount){showPage(it)}}
        container.addView(viewerControls)
        hwpModes = LinearLayout(this).apply {
            visibility = View.GONE
            listOf("한쪽" to "single", "두쪽" to "spread", "연속" to "continuous").forEach { (label, value) ->
                addView(button(label) {
                    changeHwpMode(value)
                }, toolLayout())
            }
        }
        content = LinearLayout(this)
        list = ListView(this).apply {
            setOnItemClickListener { _, _, position, _ ->
                pageIndex = position
                showPage(position)
                if (!UiStyle(this@MainActivity).useSidePanel()) { listVisible = false; updateLayout() }
            }
        }
        content.addView(list)
        emptyMessage = TextView(this).apply { text = "파일을 선택해 주세요."; setPadding(24, 24, 24, 24) }
        content.addView(emptyMessage, LinearLayout.LayoutParams(0, -1, 1f))
        container.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        home = HomeScreen(this, recent, { pickDocument() }, { noteId -> startActivityForResult(Intent(this, PdfNotesActivity::class.java).putExtra("note_id",noteId), 4) }) { item ->
            openingRecent = true
            documentUri = item.uri
            incomingMimeType = null
            pageIndex = if (item.mode == "mobile-word") item.x else item.page
            hwpMode = item.mode.takeIf { it in listOf("continuous", "single", "spread") } ?: "single"
            hwpZoom = item.zoom
            autoOpenOffice = true
            loadDocument()
        }
        setContentView(FrameLayout(this).apply {
            addView(container)
            addView(home)
        })
        if (documentUri != null || resumeAfterOffice) home.visibility = View.GONE
        else container.visibility = View.GONE
        updateLayout()
    }

    private fun changeHwpMode(value: String) {
        if (hwpModes.visibility != View.VISIBLE) return
        hwpMode = value
        browser?.evaluateJavascript("window.setHwpMode(${JSONObject.quote(value)},$pageIndex)", null)
        documentUri?.let { recent.updateView(it, pageIndex, hwpMode, hwpZoom) }
    }

    private fun showHome() {
        viewerControls.closeSearch()
        loadGeneration++
        viewerControls.setDocumentNotice("")
        documentUri = null
        documentSource = null
        resumeAfterOffice = false
        releaseBrowser()
        home.refresh()
        home.visibility = View.VISIBLE
        container.visibility = View.GONE
    }

    @Deprecated("Android 뒤로 이동 처리")
    override fun onBackPressed() {
        if(home.visibility!=View.VISIBLE&&viewerControls.searchIsOpen)viewerControls.closeSearch()
        else if (home.visibility == View.VISIBLE) super.onBackPressed() else showHome()
    }

    private fun createBrowser() {
        if (browser != null) return
        val generation = ++browserGeneration
        pendingHwpZoomRestore = kotlin.math.abs(hwpZoom - 1f) > 0.005f
        browser = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.domStorageEnabled = false
            addJavascriptInterface(EngineBridge(generation), "Android")
            webViewClient = object : WebViewClient() {
                override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
                    if (generation != browserGeneration || pageCount == 0 || pendingHwpZoomRestore ||
                        !(selectedName.endsWith(".hwp", true) || selectedName.endsWith(".hwpx", true))) return
                    hwpZoom = newScale / resources.displayMetrics.density
                    showHwpPosition()
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val uri = request.url
                    if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net" || uri.path?.contains("..") == true) {
                        return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                    }
                    val path = uri.path?.removePrefix("/") ?: ""
                    if (path.startsWith("document/")) {
                        val source = documentSource
                        if (source == null || path != "document/${source.token}") {
                            return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                        }
                        return try {
                            val stream = contentResolver.openInputStream(source.uri) ?: throw java.io.FileNotFoundException()
                            WebResourceResponse("application/octet-stream", null, 200, "OK", mapOf("Cache-Control" to "no-store"), stream)
                        } catch (_: java.io.IOException) {
                            WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                        } catch (_: SecurityException) {
                            // URI 재전달 오류는 비동기 UI 콜백 내부에서 처리
                            WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                        }
                    }
                    val mime = when (path.substringAfterLast('.', "")) {
                        "html" -> "text/html"
                        "js", "mjs" -> "text/javascript"
                        "wasm" -> "application/wasm"
                        else -> "application/octet-stream"
                    }
                    return try {
                        WebResourceResponse(mime, null, assets.open(path))
                    } catch (_: java.io.IOException) {
                        WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                    }
                }
            }
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if ((right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) &&
                    ready && pageCount > 0 && hwpMode == "single") {
                    post { if (generation == browserGeneration && !isDestroyed) showPage(pageIndex) }
                }
            }
        }
        emptyMessage.visibility = View.GONE
        browser?.let { content.addView(it) }
        updateLayout()
        browser?.loadUrl("https://appassets.androidplatform.net/index.html")
    }

    private fun releaseBrowser() {
        pendingWebDocument = false
        ready = false
        browserGeneration++
        browser?.let {
            content.removeView(it)
            it.removeJavascriptInterface("Android")
            it.destroy()
        }
        browser = null
        emptyMessage.visibility = View.VISIBLE
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeX = event.rawX
                swipeY = event.rawY
                swipeMultiTouch = false
                val view = browser
                val location = IntArray(2)
                view?.getLocationOnScreen(location)
                swipeDocument = view != null && view.visibility == View.VISIBLE &&
                    swipeX >= location[0] && swipeX < location[0] + view.width &&
                    swipeY >= location[1] && swipeY < location[1] + view.height
            }
            MotionEvent.ACTION_POINTER_DOWN -> swipeMultiTouch = true
            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - swipeX
                val dy = event.rawY - swipeY
                if (swipeDocument && !swipeMultiTouch && hwpMode != "continuous" && hwpZoom <= 1.01f &&
                    kotlin.math.abs(dx) > 48 * resources.displayMetrics.density &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.25f) {
                    super.dispatchTouchEvent(event)
                    movePage(if (dx < 0) 1 else -1)
                    return true
                }
            }
        }
        if(swipeDocument&&event.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER)tapDetector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        minWidth = 0
        setPadding(0, 0, 0, 0)
        setOnClickListener { action() }
    }

    private fun windowWidthDp() = resources.configuration.screenWidthDp

    private fun toolLayout() = if (windowWidthDp() >= 600)
        LinearLayout.LayoutParams((96 * resources.displayMetrics.density).toInt(), -2)
    else LinearLayout.LayoutParams(0, -2, 1f)

    private fun updateLayout() {
        val wide = UiStyle(this).useSidePanel()
        content.orientation = LinearLayout.HORIZONTAL
        list.visibility = if (listVisible) View.VISIBLE else View.GONE
        val listWidth = (if (wide) 200f else windowWidthDp().toFloat()) * resources.displayMetrics.density
        list.layoutParams = LinearLayout.LayoutParams(listWidth.toInt(), -1)
        browser?.let { view ->
            view.visibility = if (listVisible && !wide) View.GONE else View.VISIBLE
            view.layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            if (view.visibility == View.VISIBLE && pageCount > 0) view.post { showPage(pageIndex) }
        }
    }

    private fun pickDocument() {
        viewerControls.closeSearch()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, 1)
    }

    @Deprecated("Android 파일 선택 결과 처리")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 4) { home.refresh(); return }
        if (requestCode == 2 || requestCode == 3) {
            if (requestCode == 3 && data?.getBooleanExtra("password_cancelled", false) == true) {
                openingRecent = false
                resumeAfterOffice = false
                showHome()
                return
            }
            if (resultCode == RESULT_OK) data?.data?.let {
                recent.add(it, data.getStringExtra("name") ?: selectedName)
                if (data.hasExtra("view_page")) recent.updateView(it, data.getIntExtra("view_page", 0),
                    data.getStringExtra("view_mode") ?: "", data.getFloatExtra("view_zoom", 1f),
                    data.getIntExtra("view_x", 0), data.getIntExtra("view_y", 0))
            }
            val failedRecent = openingRecent && resultCode != RESULT_OK
            openingRecent = false
            if (requestCode == 3) resumeAfterOffice = false
            showHome()
            if (failedRecent) {
                AlertDialog.Builder(this).setMessage("문서를 열 수 없습니다. 파일을 다시 선택해 주세요.")
                    .setNegativeButton("취소", null).setPositiveButton("다시 선택") { _, _ -> showHome(); pickDocument() }.show()
            }
            return
        }
        if (requestCode != 1 || resultCode != RESULT_OK) return
        releaseBrowser()
        documentUri = data?.data ?: return
        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (data.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 && flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
            try { contentResolver.takePersistableUriPermission(documentUri!!, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { }
        }
        openingRecent = false
        incomingMimeType = null
        pageIndex = 0
        hwpMode = "single"
        hwpZoom = 1f
        autoOpenOffice = true
        loadDocument()
    }

    private fun loadDocument() {
        val uri = documentUri ?: return
        hwpThumbnails.evictAll();pendingThumbnails.clear();failedThumbnails.clear()
        viewerControls.setDocumentNotice("")
        hwpSearchMessage = ""
        home.visibility = View.GONE
        container.visibility = View.VISIBLE
        pendingWebDocument = false
        val launchOffice = autoOpenOffice
        autoOpenOffice = false
        val generation = ++loadGeneration
        val requestMimeType = incomingMimeType
        val source = DocumentSource(UUID.randomUUID().toString(), uri)
        documentSource = source
        pageCount = 0
        list.adapter = null
        indicator.text = "불러오는 중"
        hwpSearch.visibility = View.GONE
        hwpNextSearch.visibility = View.GONE
        hwpModes.visibility = View.GONE
        if (ready) browser?.evaluateJavascript("window.resetDocument()", null)
        worker.execute {
            try {
                val displayName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                val mimeType = requestMimeType ?: contentResolver.getType(uri)
                val extensionFromMime = when (mimeType?.lowercase()) {
                    "application/pdf" -> "pdf"
                    "text/csv", "text/comma-separated-values", "application/csv", "application/x-csv" -> "csv"
                    "application/x-hwp", "application/haansofthwp", "application/vnd.hancom.hwp" -> "hwp"
                    "application/vnd.hancom.hwpx", "application/hwp+zip" -> "hwpx"
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
                    else -> null
                }
                val name = if (!displayName.isNullOrBlank() && '.' in displayName) displayName
                    else if (extensionFromMime != null) "${displayName ?: "document"}.$extensionFromMime"
                    else displayName ?: "document"
                runOnUiThread {
                    if (generation == loadGeneration && !isDestroyed && !isFinishing) {
                        try {
                            selectedName = name
                            viewerControls.setTitle(name)
                            val saved = if (openingRecent) recent.all().firstOrNull { it.uri == uri } else null
                            val extension = name.substringAfterLast('.', "").lowercase()
                            val isOffice = extension in listOf("docx", "xlsx", "pptx")
                            if (extension == "csv" && launchOffice) {
                                releaseBrowser()
                                documentSource = null
                                documentUri = null
                                indicator.text = "CSV 보기"
                                @Suppress("DEPRECATION")
                                startActivityForResult(Intent(this, CsvActivity::class.java).setData(uri).putExtra("name", name)
                                    .putExtra("restore_page", saved?.page ?: 0).putExtra("restore_encoding", saved?.mode ?: "")
                                    .putExtra("restore_zoom", saved?.zoom ?: 1f)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), 2)
                            } else if (extension == "pdf" && launchOffice) {
                                releaseBrowser()
                                documentSource = null
                                documentUri = null
                                indicator.text = "PDF 보기"
                                @Suppress("DEPRECATION")
                                startActivityForResult(Intent(this, PdfActivity::class.java).setData(uri).putExtra("name", name)
                                    .putExtra("restore_page", saved?.page ?: 0).putExtra("restore_mode", saved?.mode?.toIntOrNull() ?: 1)
                                    .putExtra("restore_zoom", saved?.zoom ?: 1f).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), 2)
                            } else if (isOffice && (launchOffice || extension == "docx")) {
                                releaseBrowser()
                                documentSource = null
                                indicator.text = "문서를 불러오는 중"
                                resumeAfterOffice = true
                                @Suppress("DEPRECATION")
                                startActivityForResult(Intent(this, OfficeActivity::class.java).setData(uri).putExtra("name", name)
                                    .putExtra("restore_page", if (saved?.mode == "mobile-word") saved.x else saved?.page ?: pageIndex)
                                    .putExtra("restore_zoom", if (saved?.mode == "mobile-word") 1f else saved?.zoom ?: 1f)
                                    .putExtra("restore_x", if (saved?.mode == "mobile-word") 0 else saved?.x ?: 0)
                                    .putExtra("restore_y", if (saved?.mode == "mobile-word") 0 else saved?.y ?: 0)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), 3)
                            } else {
                                if (!ready) {
                                    pendingWebDocument = true
                                    createBrowser()
                                } else {
                                    val url = "/document/${source.token}"
                                    browser?.evaluateJavascript("window.openDocument(${JSONObject.quote(url)},${JSONObject.quote(name)},$generation)", null)
                                }
                            }
                        } catch (_: SecurityException) {
                            resumeAfterOffice=false
                            indicator.text="파일 접근 권한이 없습니다"
                            showFileAccessError(force=true)
                        }
                    }
                }
            } catch (error: java.io.IOException) {
                runOnUiThread {
                    if (generation == loadGeneration && !isDestroyed && !isFinishing) {
                        indicator.text = "파일을 읽을 수 없습니다"
                        showFileAccessError()
                        if (ready) browser?.evaluateJavascript("window.showOpenError()", null)
                    }
                }
            } catch (error: SecurityException) {
                runOnUiThread {
                    if (generation == loadGeneration && !isDestroyed && !isFinishing) {
                        indicator.text = "파일 접근 권한이 없습니다"
                        showFileAccessError()
                        if (ready) browser?.evaluateJavascript("window.showOpenError()", null)
                    }
                }
            } catch (error: IllegalArgumentException) {
                runOnUiThread {
                    if (generation == loadGeneration && !isDestroyed && !isFinishing) {
                        indicator.text = "파일 정보를 읽을 수 없습니다"
                        if (ready) browser?.evaluateJavascript("window.showOpenError()", null)
                    }
                }
            }
        }
    }

    private fun showFileAccessError(force:Boolean=false) {
        if (!openingRecent&&!force) return
        openingRecent = false
        AlertDialog.Builder(this).setMessage("문서를 열 수 없습니다. 파일을 다시 선택해 주세요.")
            .setNegativeButton("취소") { _, _ -> showHome() }
            .setPositiveButton("다시 선택") { _, _ -> showHome(); pickDocument() }.show()
    }

    private fun showPage(index: Int) {
        if (index !in 0 until pageCount) return
        browser?.evaluateJavascript("window.showPage($index)", null)
    }

    private fun movePage(direction: Int) {
        if (hwpModes.visibility == View.VISIBLE && hwpMode == "spread") {
            val first = if (pageIndex == 0) 0 else 1 + (pageIndex - 1) / 2 * 2
            val target = if (direction > 0) {
                if (first == 0) 1 else first + 2
            } else {
                if (first <= 1) 0 else first - 2
            }
            showPage(target)
        } else showPage(pageIndex + direction)
    }

    private fun searchHwp() {
        viewerControls.showSearch(hwpSearchQuery,context="한글 문서 검색",next={hwpNextSearch.performClick()},closed={
            hwpSearchMessage="";hwpNextSearch.visibility=View.GONE
            browser?.evaluateJavascript("window.clearHwpSearch()",null);showHwpPosition()
        }) {query->
            hwpSearchQuery=query
            val generation=loadGeneration
            browser?.evaluateJavascript("window.searchHwp(${JSONObject.quote(query)})") {value->
                if(viewerControls.searchIsOpen)showHwpSearchResult(value,generation)
            }
        }
    }

    private fun showHwpSearchResult(value: String?, generation: Int) {
        if (generation != loadGeneration || isDestroyed || isFinishing) return
        try {
            val result = JSONObject(value ?: throw org.json.JSONException("검색 결과가 없습니다."))
            if (result.getBoolean("found")) {
                val number = result.getInt("number")
                val total = result.getInt("total")
                val canNext = result.getBoolean("canNext")
                hwpSearchMessage = " · 검색 $number/$total"
                hwpNextSearch.visibility = if (canNext) View.VISIBLE else View.GONE
                hwpNextSearch.text = "다음 $number/$total"
                viewerControls.setSearchResult("$number / $total",canNext)
                val target = result.getInt("page")
                if (target !in 0 until pageCount) throw org.json.JSONException("검색 결과 페이지가 범위를 벗어났습니다.")
                pageIndex = target
                browser?.evaluateJavascript("window.showHwpSearchResult($target)", null)
                showHwpPosition()
                val message = when {
                    !result.optBoolean("highlighted") -> "검색 결과 위치를 강조할 수 없습니다."
                    total > 5000 && !canNext -> "검색 결과가 많아 다음 결과 이동을 지원하지 않습니다."
                    else -> "검색 결과 $number / $total"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            } else {
                viewerControls.setSearchResult("검색 결과 없음")
                hwpSearchMessage = ""
                hwpNextSearch.visibility = View.GONE
                showHwpPosition()
                Toast.makeText(this, "검색 결과가 없습니다.", Toast.LENGTH_LONG).show()
            }
        } catch (error: org.json.JSONException) {
            viewerControls.setSearchResult("검색 결과를 확인할 수 없습니다")
            hwpNextSearch.visibility = View.GONE
            Toast.makeText(this, "검색 결과를 확인할 수 없습니다.", Toast.LENGTH_LONG).show()
        }
    }

    inner class EngineBridge(private val generation: Int) {
        @JavascriptInterface fun onReady() = runOnUiThread {
            if (generation == browserGeneration && !isDestroyed && !isFinishing) {
                ready = true
                if (pendingWebDocument) loadDocument()
            }
        }
        @JavascriptInterface fun onOpened(count: Int, generation: Int) = runOnUiThread {
            if (this.generation != browserGeneration || generation != loadGeneration || isDestroyed || isFinishing) return@runOnUiThread
            pageCount = count
            documentUri?.let { recent.add(it, selectedName) }
            openingRecent = false
            val isHwp = selectedName.endsWith(".hwp", true) || selectedName.endsWith(".hwpx", true)
            hwpSearch.visibility = if (isHwp) View.VISIBLE else View.GONE
            hwpModes.visibility = if (isHwp) View.VISIBLE else View.GONE
            list.adapter = HwpPageAdapter()
            val selected = pageIndex.coerceIn(0, count - 1)
            if (isHwp) browser?.evaluateJavascript("window.setHwpMode(${JSONObject.quote(hwpMode)},$selected)", null)
            else showPage(selected)
            if (isHwp && pendingHwpZoomRestore) {
                val zoomToRestore = hwpZoom
                browser?.post {
                    if (this.generation == browserGeneration && generation == loadGeneration && !isDestroyed && !isFinishing) {
                        pendingHwpZoomRestore = false
                        browser?.zoomBy(zoomToRestore)
                    }
                }
            }
        }
        @JavascriptInterface fun onPageShown(index: Int, generation: Int) = runOnUiThread {
            if (this.generation != browserGeneration || generation != loadGeneration || isDestroyed || isFinishing) return@runOnUiThread
            pageIndex = index
            showHwpPosition()
        }
        private fun currentThumbnail(index:Int,generation:Int)=this.generation==browserGeneration&&generation==loadGeneration&&!isDestroyed&&index in 0 until pageCount
        @JavascriptInterface fun onThumbnailError(index:Int,generation:Int)=runOnUiThread {
            if(currentThumbnail(index,generation)){
                pendingThumbnails.remove(index);failedThumbnails.add(index)
                (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
            }
        }
        @JavascriptInterface fun onThumbnail(encoded: String, index:Int,generation: Int) = runOnUiThread {
            if(!currentThumbnail(index,generation))return@runOnUiThread
            if(encoded.length>512*1024){onThumbnailError(index,generation);return@runOnUiThread}
            val uri=documentUri?:return@runOnUiThread
            PdfInkStore.io.execute {
                val bytes=try{android.util.Base64.decode(encoded,android.util.Base64.DEFAULT)}catch(_:IllegalArgumentException){onThumbnailError(index,generation);return@execute}
                val bounds=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
                android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                if(bounds.outWidth !in 1..180||bounds.outHeight !in 1..480){onThumbnailError(index,generation);return@execute}
                val bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                if(bitmap==null){onThumbnailError(index,generation);return@execute}
                if(index==0)DocumentPreviewCache.record(applicationContext,uri,bitmap)
                runOnUiThread {if(currentThumbnail(index,generation)){
                    hwpThumbnails.put(index,bitmap);pendingThumbnails.remove(index)
                    (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
                }}
            }
        }
        @JavascriptInterface fun onHwpPasswordRequired(generation: Int, retry: Boolean) = runOnUiThread {
            val source = documentSource
            if (this.generation != browserGeneration || generation != loadGeneration || source == null || isDestroyed || isFinishing) return@runOnUiThread
            UiStyle(this@MainActivity).passwordDialog("한글 비밀번호",if(retry)"비밀번호가 맞지 않거나 암호 데이터가 손상되었습니다."else null,cancel={showHome()}) {password->
                    if (this.generation == browserGeneration && generation == loadGeneration && documentSource == source) {
                        val url = "/document/${source.token}"
                        browser?.evaluateJavascript("window.openDocument(${JSONObject.quote(url)},${JSONObject.quote(selectedName)},$generation,${JSONObject.quote(password)})", null)
                    }
            }
        }
        @JavascriptInterface fun onError(generation: Int) = runOnUiThread {
            if (this.generation == browserGeneration && generation == loadGeneration && !isDestroyed && !isFinishing) {
                indicator.text = "열기 실패"
                hwpSearch.visibility = View.GONE
                hwpNextSearch.visibility = View.GONE
                hwpModes.visibility = View.GONE
                showFileAccessError()
            }
        }
    }

    private fun showHwpPosition() {
        indicator.text = "${pageIndex + 1}/$pageCount · ${(hwpZoom * 100).toInt()}%$hwpSearchMessage"
        indicator.contentDescription = "${pageIndex + 1} / $pageCount · ${(hwpZoom * 100).toInt()}%$hwpSearchMessage"
        documentUri?.let { recent.updateView(it, pageIndex, hwpMode, hwpZoom) }
        (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }

    private inner class HwpPageAdapter:BaseAdapter(){
        override fun getCount()=pageCount
        override fun getItem(position:Int)=position
        override fun getItemId(position:Int)=position.toLong()
        override fun getView(position:Int,recycled:View?,parent:ViewGroup):View {
            val ui=UiStyle(this@MainActivity)
            val item=LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.VERTICAL;isSelected=position==pageIndex
                contentDescription="미리보기 ${position+1}쪽"+if(position==pageIndex)", 선택됨"else"";layoutParams=AbsListView.LayoutParams(-1,ui.dp(190))
                setPadding(ui.dp(8),ui.dp(8),ui.dp(8),ui.dp(4))
                background=ui.rounded(if(position==pageIndex)ui.selected else ui.background,6,if(position==pageIndex)ui.text else ui.line)
            }
            val bitmap=hwpThumbnails.get(position)
            val image=ImageView(this@MainActivity).apply {scaleType=ImageView.ScaleType.FIT_CENTER;setImageBitmap(bitmap);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
            item.addView(image,LinearLayout.LayoutParams(-1,0,1f))
            item.addView(ui.label(if(position in failedThumbnails)"미리보기 없음 · ${position+1}쪽"else"${position+1}쪽"+if(position==pageIndex)" · 현재"else"",12f,position==pageIndex).apply {
                gravity=android.view.Gravity.CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },LinearLayout.LayoutParams(-1,ui.dp(32)))
            if(bitmap==null&&position !in pendingThumbnails&&position !in failedThumbnails&&pendingThumbnails.size<3){
                pendingThumbnails.add(position)
                browser?.evaluateJavascript("window.renderHwpThumbnail($position,$loadGeneration)",null)
            }
            return item
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        home.saveState(outState)
        outState.putString("uri", documentUri?.toString())
        outState.putString("name", selectedName)
        outState.putInt("page", pageIndex)
        outState.putBoolean("list", listVisible)
        outState.putBoolean("resumeAfterOffice", resumeAfterOffice)
        outState.putString("hwpMode", hwpMode)
        outState.putFloat("hwpZoom", hwpZoom)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        loadGeneration++
        documentSource = null
        worker.shutdownNow()
        releaseBrowser()
        hwpThumbnails.evictAll()
        super.onDestroy()
    }
}
