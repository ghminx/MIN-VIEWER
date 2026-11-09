package org.minviewer.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var officeTrial: Button
    private lateinit var legacyTrial: Button
    private lateinit var hwpSearch: Button
    private lateinit var hwpNextSearch: Button
    private lateinit var hwpModes: LinearLayout
    private var documentUri: Uri? = null
    private var pageCount = 0
    private var pageIndex = 0
    private var listVisible = false
    private var ready = false
    private var browserGeneration = 0
    private var pendingWebDocument = false
    private var selectedName = ""
    private var resumeAfterOffice = false
    private var waitingForFallback = false
    private var autoOpenOffice = false
    private var incomingMimeType: String? = null
    private var loadGeneration = 0
    private var hwpMode = "continuous"
    @Volatile private var documentSource: DocumentSource? = null

    private data class DocumentSource(val token: String, val uri: Uri)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        documentUri = savedInstanceState?.getString("uri")?.let(Uri::parse)
        selectedName = savedInstanceState?.getString("name") ?: ""
        resumeAfterOffice = savedInstanceState?.getBoolean("resumeAfterOffice") ?: false
        waitingForFallback = savedInstanceState?.getBoolean("waitingForFallback") ?: false
        pageIndex = savedInstanceState?.getInt("page") ?: 0
        listVisible = savedInstanceState?.getBoolean("list") ?: false
        hwpMode = savedInstanceState?.getString("hwpMode") ?: "continuous"
        recent = RecentDocuments(this)
        buildScreen()
        if (savedInstanceState == null) receiveDocument(intent)
        else if (waitingForFallback) showEngineChoice()
        else if (!resumeAfterOffice) loadDocument()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveDocument(intent)
    }

    private fun receiveDocument(incoming: Intent) {
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
        incomingMimeType = incoming.type
        pageIndex = 0
        waitingForFallback = false
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
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            button("홈") { showHome() },
            button("파일 열기") { pickDocument() },
            button("목록") { listVisible = !listVisible; updateLayout() },
            button("이전") { movePage(-1) },
            button("다음") { movePage(1) }
        ).forEach {
            toolbar.addView(it, toolLayout())
        }
        container.addView(toolbar)
        indicator = TextView(this).apply { text = "0 / 0"; setPadding(12, 0, 12, 4) }
        hwpSearch = button("검색") { searchHwp() }.apply { visibility = View.GONE }
        hwpNextSearch = button("다음 결과") {
            val generation = loadGeneration
            browser?.evaluateJavascript("window.nextHwpResult()") { value -> showHwpSearchResult(value, generation) }
        }.apply { visibility = View.GONE }
        container.addView(LinearLayout(this).apply {
            addView(indicator, LinearLayout.LayoutParams(0, -2, 1f))
            addView(hwpSearch)
            addView(hwpNextSearch)
        })
        hwpModes = LinearLayout(this).apply {
            visibility = View.GONE
            listOf("연속" to "continuous", "한쪽" to "single", "두쪽" to "spread").forEach { (label, value) ->
                addView(button(label) {
                    hwpMode = value
                    browser?.evaluateJavascript("window.setHwpMode(${JSONObject.quote(value)},$pageIndex)", null)
                }, toolLayout())
            }
        }
        container.addView(hwpModes)
        officeTrial = button("LibreOffice 시험") {
            documentUri?.let { uri ->
                releaseBrowser()
                documentSource = null
                pageCount = 0
                list.adapter = null
                indicator.text = "LibreOffice 시험 중"
                waitingForFallback = false
                legacyTrial.visibility = View.GONE
                resumeAfterOffice = true
                startActivity(Intent(this, OfficeActivity::class.java).setData(uri).putExtra("name", selectedName).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }.apply { visibility = View.GONE }
        container.addView(officeTrial)
        legacyTrial = button("경량 엔진 보기") {
            waitingForFallback = false
            legacyTrial.visibility = View.GONE
            loadDocument()
        }.apply { visibility = View.GONE }
        container.addView(legacyTrial)

        content = LinearLayout(this)
        list = ListView(this).apply {
            setOnItemClickListener { _, _, position, _ ->
                showPage(position)
                if (windowWidthDp() < 840) { listVisible = false; updateLayout() }
            }
        }
        content.addView(list)
        emptyMessage = TextView(this).apply { text = "파일을 선택해 주세요."; setPadding(24, 24, 24, 24) }
        content.addView(emptyMessage, LinearLayout.LayoutParams(0, -1, 1f))
        container.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        home = HomeScreen(this, recent, { pickDocument() }) { item ->
            openingRecent = true
            documentUri = item.uri
            incomingMimeType = null
            pageIndex = 0
            waitingForFallback = false
            autoOpenOffice = true
            loadDocument()
        }
        setContentView(FrameLayout(this).apply {
            addView(container)
            addView(home)
        })
        if (documentUri != null || waitingForFallback || resumeAfterOffice) home.visibility = View.GONE
        else container.visibility = View.GONE
        updateLayout()
    }

    private fun showHome() {
        loadGeneration++
        documentUri = null
        documentSource = null
        waitingForFallback = false
        resumeAfterOffice = false
        releaseBrowser()
        home.refresh()
        home.visibility = View.VISIBLE
        container.visibility = View.GONE
    }

    @Deprecated("Android 뒤로 이동 처리")
    override fun onBackPressed() {
        if (home.visibility == View.VISIBLE) super.onBackPressed() else showHome()
    }

    private fun createBrowser() {
        if (browser != null) return
        val generation = ++browserGeneration
        browser = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.domStorageEnabled = false
            addJavascriptInterface(EngineBridge(generation), "Android")
            webViewClient = object : WebViewClient() {
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
        val wide = windowWidthDp() >= 840
        content.orientation = LinearLayout.HORIZONTAL
        list.visibility = if (listVisible) View.VISIBLE else View.GONE
        val listWidth = (if (wide) 240f else windowWidthDp().toFloat()) * resources.displayMetrics.density
        list.layoutParams = LinearLayout.LayoutParams(listWidth.toInt(), -1)
        browser?.let { view ->
            view.visibility = if (listVisible && !wide) View.GONE else View.VISIBLE
            view.layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            if (view.visibility == View.VISIBLE && pageCount > 0) view.post { showPage(pageIndex) }
        }
    }

    private fun pickDocument() {
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
        if (requestCode == 2 || requestCode == 3) {
            if (resultCode == RESULT_OK) data?.data?.let { recent.add(it, data.getStringExtra("name") ?: selectedName) }
            val failedRecent = openingRecent && resultCode != RESULT_OK
            openingRecent = false
            if (requestCode == 2) showHome()
            if (failedRecent) {
                AlertDialog.Builder(this).setMessage("문서를 열 수 없습니다. 파일을 다시 선택해 주세요.")
                    .setNegativeButton("취소", null).setPositiveButton("다시 선택") { _, _ -> showHome(); pickDocument() }.show()
            }
            return
        }
        if (requestCode != 1 || resultCode != RESULT_OK) return
        documentUri = data?.data ?: return
        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (data.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 && flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
            try { contentResolver.takePersistableUriPermission(documentUri!!, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { }
        }
        openingRecent = false
        incomingMimeType = null
        pageIndex = 0
        waitingForFallback = false
        autoOpenOffice = true
        loadDocument()
    }

    private fun loadDocument() {
        val uri = documentUri ?: return
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
        officeTrial.visibility = View.GONE
        legacyTrial.visibility = View.GONE
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
                        selectedName = name
                        val extension = name.substringAfterLast('.', "").lowercase()
                        val isOffice = extension in listOf("docx", "xlsx", "pptx")
                        if (isOffice) {
                            officeTrial.visibility = View.VISIBLE
                        }
                        if (extension == "csv" && launchOffice) {
                            releaseBrowser()
                            documentSource = null
                            documentUri = null
                            indicator.text = "CSV 보기"
                            @Suppress("DEPRECATION")
                            startActivityForResult(Intent(this, CsvActivity::class.java).setData(uri).putExtra("name", name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), 2)
                        } else if (extension == "pdf" && launchOffice) {
                            releaseBrowser()
                            documentSource = null
                            documentUri = null
                            indicator.text = "PDF 보기"
                            @Suppress("DEPRECATION")
                            startActivityForResult(Intent(this, PdfActivity::class.java).setData(uri).putExtra("name", name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), 2)
                        } else if (isOffice && launchOffice) {
                            releaseBrowser()
                            documentSource = null
                            indicator.text = "LibreOffice 시험 중"
                            resumeAfterOffice = true
                            @Suppress("DEPRECATION")
                            startActivityForResult(Intent(this, OfficeActivity::class.java).setData(uri).putExtra("name", name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), 3)
                        } else {
                            if (!ready) {
                                pendingWebDocument = true
                                createBrowser()
                            } else {
                                val url = "/document/${source.token}"
                                browser?.evaluateJavascript("window.openDocument(${JSONObject.quote(url)},${JSONObject.quote(name)},$generation)", null)
                            }
                        }
                    }
                }
            } catch (error: java.io.IOException) {
                runOnUiThread {
                    if (generation == loadGeneration && !isDestroyed && !isFinishing) {
                        indicator.text = "파일을 읽을 수 없습니다"
                        showRecentAccessError()
                        if (ready) browser?.evaluateJavascript("window.showOpenError()", null)
                    }
                }
            } catch (error: SecurityException) {
                runOnUiThread {
                    if (generation == loadGeneration && !isDestroyed && !isFinishing) {
                        indicator.text = "파일 접근 권한이 없습니다"
                        showRecentAccessError()
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

    private fun showRecentAccessError() {
        if (!openingRecent) return
        openingRecent = false
        AlertDialog.Builder(this).setMessage("문서에 접근할 수 없습니다. 파일을 다시 선택해 주세요.")
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
        val input = EditText(this).apply { setSingleLine(); hint = "검색어" }
        AlertDialog.Builder(this).setTitle("한글 문서 검색").setView(input).setNegativeButton("취소", null)
            .setPositiveButton("찾기") { _, _ ->
                val query = input.text.toString()
                if (query.isNotBlank()) {
                    val generation = loadGeneration
                    browser?.evaluateJavascript("window.searchHwp(${JSONObject.quote(query)})") { value ->
                        showHwpSearchResult(value, generation)
                    }
                }
            }.show()
    }

    private fun showHwpSearchResult(value: String?, generation: Int) {
        if (generation != loadGeneration || isDestroyed || isFinishing) return
        try {
            val result = JSONObject(value ?: throw org.json.JSONException("검색 결과가 없습니다."))
            if (result.getBoolean("found")) {
                val number = result.getInt("number")
                val total = result.getInt("total")
                val canNext = result.getBoolean("canNext")
                hwpNextSearch.visibility = if (canNext) View.VISIBLE else View.GONE
                hwpNextSearch.text = "다음 $number/$total"
                showPage(result.getInt("page"))
                val message = if (total > 5000 && !canNext) "검색 결과가 많아 다음 결과 이동을 지원하지 않습니다." else "검색 결과 $number / $total"
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            } else {
                hwpNextSearch.visibility = View.GONE
                Toast.makeText(this, "검색 결과가 없습니다.", Toast.LENGTH_LONG).show()
            }
        } catch (error: org.json.JSONException) {
            hwpNextSearch.visibility = View.GONE
            Toast.makeText(this, "검색 결과를 확인할 수 없습니다.", Toast.LENGTH_LONG).show()
        }
    }

    inner class EngineBridge(private val generation: Int) {
        @JavascriptInterface fun onReady() = runOnUiThread {
            if (generation == browserGeneration && !isDestroyed && !isFinishing) {
                ready = true
                if (waitingForFallback || resumeAfterOffice) showEngineChoice()
                else if (pendingWebDocument) loadDocument()
            }
        }
        @JavascriptInterface fun onOpened(count: Int, generation: Int) = runOnUiThread {
            if (this.generation != browserGeneration || generation != loadGeneration || isDestroyed || isFinishing) return@runOnUiThread
            pageCount = count
            documentUri?.let { recent.add(it, selectedName) }
            val isHwp = selectedName.endsWith(".hwp", true) || selectedName.endsWith(".hwpx", true)
            hwpSearch.visibility = if (isHwp) View.VISIBLE else View.GONE
            hwpModes.visibility = if (isHwp) View.VISIBLE else View.GONE
            list.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, (1..count).map { "$it" })
            val selected = pageIndex.coerceIn(0, count - 1)
            if (isHwp) browser?.evaluateJavascript("window.setHwpMode(${JSONObject.quote(hwpMode)},$selected)", null)
            else showPage(selected)
        }
        @JavascriptInterface fun onPageShown(index: Int, generation: Int) = runOnUiThread {
            if (this.generation != browserGeneration || generation != loadGeneration || isDestroyed || isFinishing) return@runOnUiThread
            pageIndex = index
            indicator.text = "${index + 1} / $pageCount"
        }
        @JavascriptInterface fun onError(generation: Int) = runOnUiThread {
            if (this.generation == browserGeneration && generation == loadGeneration && !isDestroyed && !isFinishing) {
                indicator.text = "열기 실패"
                hwpSearch.visibility = View.GONE
                hwpNextSearch.visibility = View.GONE
                hwpModes.visibility = View.GONE
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("uri", documentUri?.toString())
        outState.putString("name", selectedName)
        outState.putInt("page", pageIndex)
        outState.putBoolean("list", listVisible)
        outState.putBoolean("resumeAfterOffice", resumeAfterOffice)
        outState.putBoolean("waitingForFallback", waitingForFallback)
        outState.putString("hwpMode", hwpMode)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (resumeAfterOffice) {
            resumeAfterOffice = false
            waitingForFallback = true
            showEngineChoice()
        }
    }

    private fun showEngineChoice() {
        indicator.text = "문서 보기 선택"
        officeTrial.visibility = View.VISIBLE
        legacyTrial.visibility = View.VISIBLE
        if (ready) browser?.evaluateJavascript("window.showEngineChoice()", null)
    }

    override fun onDestroy() {
        loadGeneration++
        documentSource = null
        worker.shutdownNow()
        releaseBrowser()
        super.onDestroy()
    }
}
