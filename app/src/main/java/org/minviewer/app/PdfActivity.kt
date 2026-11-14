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
    companion object {
        private var oldTemporaryFilesCleaned = false
    }
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
    private lateinit var contentRow: android.widget.FrameLayout
    private var thumbnailPanel: LinearLayout? = null
    private var pageOverview: android.app.Dialog? = null
    private var restoreOverview = false
    private var previewRecorded = false
    private lateinit var nextSearch: Button
    private var renderer: PdfRenderer? = null
    private var temporaryFile: File? = null
    private var pageCount = 0
    private var pageIndex = 0
    private var mode = 1
    private var viewing = PageViewing()
    private val wordSnapshot: File? by lazy {
        intent.getStringExtra("word_snapshot")?.let {name->
            require(name.matches(Regex("[0-9a-f-]{36}"))) {"Word 읽기 식별자가 올바르지 않습니다."}
            File(cacheDir,"word-view/$name")
        }
    }
    private var documentAreaWidth = 0
    private var documentAreaHeight = 0
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
    private var scrollAnchorPage: Int? = null
    private var navigationGeneration = 0
    @Volatile private var renderGeneration = 0
    private var touchX = 0f
    private var touchY = 0f
    private var multiTouch = false
    private var touchInDocument = false
    private lateinit var scaleDetector: ScaleGestureDetector
    private val tapDetector by lazy {
        android.view.GestureDetector(this,object:android.view.GestureDetector.SimpleOnGestureListener(){
            override fun onSingleTapConfirmed(event:MotionEvent):Boolean {
                if(closed||viewing.scrolling||zoom>1f||(inkEnabled&&fingerInk))return false
                val location=IntArray(2);horizontal.getLocationOnScreen(location)
                val x=event.rawX-location[0]
                val direction=ViewerControls.pageTapDirection(x,horizontal.width)
                if(direction!=0)movePage(direction)
                return true
            }
            override fun onDoubleTap(event:MotionEvent):Boolean {
                if(inkEnabled&&fingerInk)return false
                setZoom(if(zoom>1f)1f else 2f,event.rawX,event.rawY)
                return true
            }
        })
    }
    @Volatile private var searchGeneration = 0
    @Volatile private var searching = false
    @Volatile private var closed = false
    private var openedSuccessfully = false
    private var persistenceReady = false
    private val recent by lazy { RecentDocuments(this) }
    private var lastSavedView: Triple<Int, Int, Float>? = null
    private val inkStore by lazy { PdfInkStore(File(filesDir, "pdf-notes")) }
    private val inkWorker = PdfInkStore.io
    private var inkNote: PdfInkNote? = null
    private var inkEnabled = false
    private lateinit var controls: ViewerControls
    private var fingerInk = false
    private var encryptedDocument = false
    private lateinit var inkStatus: TextView
    private var inkPointer = -1
    private var inkImage: ImageView? = null
    private var activeStroke: InkStroke? = null
    private val inkPoints = ArrayList<InkPoint>()
    private var inkRevision = 0
    private var inkSettings: android.widget.PopupWindow? = null
    private var compactInkTools = true
    private var inkPreparing = false
    private var enableInkAfterOpen = false
    private lateinit var inkTools: HorizontalScrollView
    private lateinit var inkPanel: LinearLayout
    private lateinit var inkDock: LinearLayout
    private var inkCollapsed = false
    private var inkChromeExpanded = false
    private var inkChromeAnimating = false
    private var thumbnailAnimator: android.animation.ValueAnimator? = null
    private var touchOnOverlay = false
    private lateinit var inkRetry: Button
    private var penColor = Color.RED
    private var penWidth = 0.004f
    private var inkTool = "pen"
    private var stylusEraser = false
    private var activeInkTool = "pen"
    private var inkGestureStrokes: List<InkStroke>? = null
    private var highlighterColor = Color.rgb(255, 215, 0)
    private var highlighterWidth = 0.02f
    private val inkButtons = mutableMapOf<String, Button>()
    private val inkUndo = java.util.ArrayDeque<List<InkStroke>>()
    private val inkRedo = java.util.ArrayDeque<List<InkStroke>>()
    private val erasedStrokes = mutableSetOf<Int>()
    private var inkDirty = false
    private data class InkSession(val note: PdfInkNote, val undo: List<List<InkStroke>>, val redo: List<List<InkStroke>>, val dirty: Boolean)
    private var retainedInkSession: InkSession? = null
    private var inkSelection: InkSelection? = null
    private var selectionPreview: InkSelection? = null
    private var movingSelection = false

    private fun clearInkSelection() {
        if (inkSelection == null && selectionPreview == null) return
        inkSelection = null
        selectionPreview = null
        movingSelection = false
        if (!inkDirty) inkStatus.text = ""
        invalidateInk()
    }

    private fun setInkTool(tool: String) {
        clearInkSelection()
        inkTool = tool
        inkEnabled = true
    }

    private fun copySelectedInk() {
        val selection = inkSelection ?: return
        val strokes = selection.documentStrokes
        val copied = selection.moved(0.03f, 0.03f).documentStrokes.filterIndexed { i, _ -> i in selection.indices }
        val updated = strokes + copied
        commitInk(updated)
        inkSelection = InkSelection((strokes.size until updated.size).toSet(), updated)
        invalidateInk()
    }

    private fun deleteSelectedInk() {
        val selection = inkSelection ?: return
        commitInk(selection.documentStrokes.filterIndexed { i, _ -> i !in selection.indices })
    }

    private fun resizeSelectedInk() {
        val selection = inkSelection ?: return
        val factors = floatArrayOf(0.5f, 0.75f, 1.25f, 1.5f, 2f)
        AlertDialog.Builder(this).setTitle("선택한 필기 크기").setItems(arrayOf("50%", "75%", "125%", "150%", "200%")) { _, i ->
            val resized = selection.resized(factors[i])
            commitInk(resized.documentStrokes)
            inkSelection = resized
            invalidateInk()
        }.show()
    }

    private fun invalidateInk() {
        if (::controls.isInitialized) {
            controls.setReadingAction(inkEnabled, openedSuccessfully && !inkPreparing) {
                if (inkEnabled) stopInk() else beginInk()
            }
            if(::inkDock.isInitialized)updateInkChrome()
        }
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) row.getChildAt(j).invalidate()
        }
        inkButtons["실행 취소"]?.isEnabled = inkUndo.isNotEmpty()
        inkButtons["다시 실행"]?.isEnabled = inkRedo.isNotEmpty()
        inkButtons["색상"]?.isEnabled = inkTool == "pen" || inkTool == "highlighter"
        inkButtons["굵기"]?.isEnabled = inkTool == "pen" || inkTool == "highlighter"
        inkButtons["색상"]?.setTextColor(if(inkTool=="highlighter")highlighterColor else penColor)
        for ((name, button) in inkButtons) {
            val selectionAction = name in listOf("복사", "선택 삭제", "크기 조절", "선택 해제")
            val historyAction = name in listOf("실행 취소", "다시 실행")
            val fits = !compactInkTools || name !in listOf("색상", "굵기", "다시 실행")
            button.visibility = if (fits && (historyAction || selectionAction == (inkSelection != null))) View.VISIBLE else View.GONE
            button.isSelected = name == when (if(stylusEraser) "eraser" else inkTool) { "highlighter" -> "형광펜"; "eraser" -> "지우개"; "lasso" -> "올가미"; else -> "펜" }
            button.typeface = resources.getFont(if(button.isSelected)R.font.pretendard_semibold else R.font.pretendard_regular)
            button.contentDescription = if (button.isSelected) "$name, 선택됨" else name
        }
    }

    private fun commitInk(strokes: List<InkStroke>) {
        val note = inkNote ?: return
        if (strokes == note.strokes) return
        inkSelection = null
        inkUndo.addLast(note.strokes)
        if (inkUndo.size > 50) inkUndo.removeFirst()
        inkRedo.clear()
        inkNote = note.copy(strokes = strokes)
        persistInk(requireNotNull(inkNote))
        invalidateInk()
    }

    private fun changeInkHistory(redo: Boolean) {
        val note = inkNote ?: return
        val source = if (redo) inkRedo else inkUndo
        if (source.isEmpty()) return
        inkSelection = null
        val destination = if (redo) inkUndo else inkRedo
        destination.addLast(note.strokes)
        inkNote = note.copy(strokes = source.removeLast())
        persistInk(requireNotNull(inkNote))
        invalidateInk()
    }

    private fun eraseInk(image: ImageView, from: InkPoint, to: InkPoint) {
        val drawable = image.drawable ?: return
        val page = image.getTag(R.id.pdf_page_index) as Int
        val radius = 12 * resources.displayMetrics.density / image.imageMatrix.mapRadius(1f)
        requireNotNull(inkGestureStrokes).forEachIndexed { index, stroke ->
            if (stroke.page == page && index !in erasedStrokes && InkHitTest.hits(stroke, from, to,
                    drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat(), radius)) erasedStrokes.add(index)
        }
        image.invalidate()
    }

    private fun showInkSettings() {
        if(inkTool!="pen"&&inkTool!="highlighter")return
        inkSettings?.dismiss()
        val ui=UiStyle(this)
        val frame=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(ui.dp(16),ui.dp(8),ui.dp(16),ui.dp(16));background=ui.rounded(ui.surface,16,ui.line)}
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        val scroll=object:android.widget.ScrollView(this){
            override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
                val available=(resources.displayMetrics.heightPixels-ui.dp(112)).coerceAtLeast(ui.dp(48))
                val limit=minOf(available,MeasureSpec.getSize(heightMeasureSpec).takeIf{it>0}?:available)
                super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(limit,MeasureSpec.AT_MOST))
            }
        }.apply {addView(root)}
        val popup=android.widget.PopupWindow(frame,minOf(ui.dp(368),resources.displayMetrics.widthPixels-ui.dp(24)),ViewGroup.LayoutParams.WRAP_CONTENT,true)
        inkSettings=popup
        popup.setBackgroundDrawable(ui.rounded(ui.surface,16));popup.elevation=ui.dp(12).toFloat();popup.isOutsideTouchable=true
        popup.setOnDismissListener {if(inkSettings===popup)inkSettings=null}
        val bar=LinearLayout(this).apply {gravity=android.view.Gravity.CENTER_VERTICAL}
        bar.addView(ui.label(if(inkTool=="highlighter")"형광펜 설정"else"펜 설정",16f,true),LinearLayout.LayoutParams(0,ui.dp(48),1f))
        bar.addView(ui.button("펜 설정 닫기","닫기"){popup.dismiss()},LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)))
        frame.addView(bar)
        frame.addView(scroll,LinearLayout.LayoutParams(-1,-2))
        val preview=object:View(this){
            private val pen=Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas:Canvas){
                pen.color=if(inkTool=="highlighter")((highlighterColor and 0xffffff) or 0x60000000)else penColor
                pen.style=Paint.Style.STROKE;pen.strokeCap=Paint.Cap.ROUND;pen.strokeJoin=Paint.Join.ROUND
                pen.strokeWidth=(if(inkTool=="highlighter")highlighterWidth else penWidth)*ui.dp(600)
                val path=android.graphics.Path();path.moveTo(width*.08f,height*.7f)
                path.cubicTo(width*.32f,height*.6f,width*.18f,height*.05f,width*.34f,height*.3f)
                path.cubicTo(width*.44f,height*.5f,width*.32f,height*.94f,width*.55f,height*.52f)
                path.cubicTo(width*.8f,-height*.08f,width*.58f,height*.8f,width*.92f,height*.32f)
                canvas.drawPath(path,pen)
            }
        }.apply {contentDescription="획 미리보기";background=ui.rounded(ui.background,10)}
        root.addView(preview,LinearLayout.LayoutParams(-1,ui.dp(64)))
        root.addView(ui.label("색상",12f,color=ui.muted).apply {setPadding(0,ui.dp(14),0,ui.dp(4))})
        val names=arrayOf("검정","빨강","파랑","초록","노랑","보라")
        val colors=intArrayOf(Color.BLACK,Color.RED,Color.rgb(25,78,180),Color.rgb(25,130,60),Color.rgb(255,215,0),Color.rgb(145,55,180))
        val palette=android.widget.GridLayout(this).apply {columnCount=if(popup.width-ui.dp(32)<ui.dp(288))3 else 6}
        val colorButtons=ArrayList<Button>()
        fun updateColors(){colorButtons.forEachIndexed{i,button->button.isSelected=colors[i]==if(inkTool=="highlighter")highlighterColor else penColor;button.contentDescription=names[i]+if(button.isSelected)", 선택됨"else""};preview.invalidate();invalidateInk()}
        names.forEachIndexed {i,name->val button=ui.button(name,"색상",colors[i]){if(inkTool=="highlighter")highlighterColor=colors[i]else penColor=colors[i];updateColors()};colorButtons.add(button);palette.addView(button,android.widget.GridLayout.LayoutParams().apply {width=ui.dp(48);height=ui.dp(48)})}
        root.addView(palette)
        root.addView(ui.label("굵기",12f,color=ui.muted).apply {setPadding(0,ui.dp(12),0,ui.dp(4))})
        val widths=if(inkTool=="highlighter")floatArrayOf(.01f,.02f,.035f)else floatArrayOf(.002f,.004f,.008f)
        val widthButtons=ArrayList<Button>()
        val row=LinearLayout(this)
        fun updateWidths(){widthButtons.forEachIndexed{i,button->button.isSelected=widths[i]==if(inkTool=="highlighter")highlighterWidth else penWidth;button.contentDescription=button.text.toString()+if(button.isSelected)", 선택됨"else""};preview.invalidate()}
        arrayOf("가늘게","보통","굵게").forEachIndexed{i,label->val button=ui.button(label){if(inkTool=="highlighter")highlighterWidth=widths[i]else penWidth=widths[i];updateWidths()};widthButtons.add(button);row.addView(button,LinearLayout.LayoutParams(0,ui.dp(48),1f).apply {if(i>0)leftMargin=ui.dp(6)})}
        root.addView(row)
        root.addView(ui.label("펜과 형광펜 설정을 각각 기억",12f,color=ui.muted).apply {setPadding(0,ui.dp(12),0,0)})
        updateColors();updateWidths()
        if(!inkAtTop())popup.showAtLocation(inkTools,android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL,0,inkDock.height+ui.dp(28))
        else popup.showAsDropDown(inkButtons[if(inkTool=="pen")"펜"else"형광펜"],0,ui.dp(6))
    }

    private fun makeInkTools(): HorizontalScrollView {
        val ui=UiStyle(this)
        val row=LinearLayout(this).apply {gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(ui.dp(8),ui.dp(4),ui.dp(8),ui.dp(4));setBackgroundColor(ui.surface)}
        val actions=listOf("펜" to {if(inkTool=="pen")showInkSettings()else setInkTool("pen")},
            "형광펜" to {if(inkTool=="highlighter")showInkSettings()else setInkTool("highlighter")},
            "지우개" to {setInkTool("eraser")},"올가미" to {setInkTool("lasso")},
            "색상" to {showInkSettings()},"굵기" to {showInkSettings()},
            "실행 취소" to {changeInkHistory(false)},"다시 실행" to {changeInkHistory(true)},
            "복사" to {copySelectedInk()},"선택 삭제" to {deleteSelectedInk()},"크기 조절" to {resizeSelectedInk()},"선택 해제" to {clearInkSelection()})
        actions.forEach{(label,action)->
            if(label=="실행 취소")row.addView(View(this).apply {setBackgroundColor(ui.line);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(ui.dp(1),ui.dp(24)).apply {setMargins(ui.dp(8),0,ui.dp(8),0)})
            row.addView(ui.button(label,label){action();invalidateInk()}.apply{inkButtons[label]=this},LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)))
        }
        row.addView(ui.button("필기 도구 메뉴","더보기"){
            android.widget.PopupMenu(this,row.getChildAt(row.childCount-1)).apply{
                menu.add("색상·굵기").isEnabled=inkTool=="pen"||inkTool=="highlighter"
                menu.add("다시 실행").isEnabled=inkRedo.isNotEmpty()
                menu.add(if(fingerInk)"펜으로만 필기"else"손가락 필기")
                menu.add("읽기 모드")
                setOnMenuItemClickListener{item->when(item.title.toString()){
                    "색상·굵기"->showInkSettings();"다시 실행"->changeInkHistory(true)
                    "읽기 모드"->{stopInk()}
                    else->{fingerInk=!fingerInk;inkStatus.text=if(fingerInk)"손가락 필기 모드"else"펜 필기 모드"}
                };true};show()
            }
        },LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)))
        return HorizontalScrollView(this).apply {
            visibility=View.GONE;isHorizontalScrollBarEnabled=false;addView(row)
            addOnLayoutChangeListener{_,left,_,right,_,_,_,_,_->val compact=right-left<ui.dp(600);if(compact!=compactInkTools){compactInkTools=compact;invalidateInk()}}
        }
    }

    private fun beginInk() {
        if (!openedSuccessfully || inkPreparing) return
        inkStatus.visibility = View.VISIBLE
        if (encryptedDocument) { inkStatus.text = "암호 PDF의 필기는 아직 지원하지 않습니다."; return }
        if (inkNote != null) { inkEnabled = true; inkStatus.text = ""; invalidateInk(); return }
        val uri = intent.data ?: return
        inkPreparing = true
        invalidateInk()
        inkStatus.text = "필기 문서를 준비하는 중"
        inkWorker.execute {
            try {
                val snapshot=wordSnapshot
                val note = if(snapshot!=null)inkStore.createDocument(File(snapshot,"source.docx"),File(snapshot,"background.pdf"),intent.getStringExtra("name")?:"문서.docx",pageCount,File(snapshot,"profile.txt").readText()){closed}
                else (contentResolver.openInputStream(uri) ?: throw IOException("파일을 읽을 수 없습니다.")).use {
                    inkStore.create(it, intent.getStringExtra("name") ?: "문서.pdf", pageCount)
                }
                runOnUiThread {
                    if (!closed) {
                        inkPreparing = false
                        inkNote = note
                        val noteUri = Uri.parse("content://$packageName.pdfnotes/${note.id}")
                        viewing.save(this@PdfActivity,noteUri)
                        recent.remove(uri)
                        intent.data = noteUri
                        lastSavedView = null
                        intent.putExtra("name", note.name)
                        recent.add(noteUri, note.name)
                        DocumentPreviewCache.copy(applicationContext,uri,noteUri)
                        enableInkAfterOpen = true
                        openDocument(noteUri, null)
                        saveResult()
                    }
                }
            } catch (error: Exception) { runOnUiThread { if (!closed) { inkPreparing = false; inkStatus.text = "필기 준비 실패: ${error.message}";invalidateInk() } } }
        }
    }

    private fun stopInk() {
        inkEnabled = false
        setStylusEraser(false)
        clearInkInput()
        clearInkSelection()
        inkStatus.text = "읽기 모드"
        invalidateInk()
    }

    private fun openInkActions(action:String) {
        val note=inkNote?:return
        if(inkDirty)return
        startActivity(android.content.Intent(this,PdfNotesActivity::class.java).putExtra("note_id",note.id).putExtra("note_action",action))
    }

    private fun persistInk(updated: PdfInkNote) {
        val revision = ++inkRevision
        inkDirty = true
        inkStatus.text = "필기 저장 중"
        inkWorker.execute {
            try {
                inkStore.save(updated)
                runOnUiThread { if (!closed && revision == inkRevision) { inkDirty = false; inkStatus.text = "필기 저장 완료" } }
            } catch (error: Exception) { runOnUiThread { if (!closed) inkStatus.text = "필기 저장 실패: ${error.message} · 앱을 종료하지 마세요." } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.apply(this)
        super.onCreate(savedInstanceState)
        // 비동기 문서 로딩 전에 구성 변경 상태 확보
        retainedInkSession = lastNonConfigurationInstance as? InkSession
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (ThemePreferences.isDark(this)) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        pageIndex = savedInstanceState?.getInt("page") ?: intent.getIntExtra("restore_page", 0)
        viewing = PageViewing.load(this, requireNotNull(intent.data))
        mode = viewing.columns(resources.configuration.screenWidthDp, resources.configuration.screenHeightDp)
        restorePanel = savedInstanceState?.getBoolean("thumbnailPanel") ?: false
        restoreOverview = savedInstanceState?.getBoolean("pageOverview") ?: false
        zoom = if(savedInstanceState!=null)1f else intent.getFloatExtra("restore_zoom", 1f)
        pageOffsetX = savedInstanceState?.getFloat("pageOffsetX") ?: 0f
        pageOffsetY = savedInstanceState?.getFloat("pageOffsetY") ?: 0f
        inkEnabled = savedInstanceState?.getBoolean("inkEnabled") ?: intent.getBooleanExtra("start_ink",false)
        enableInkAfterOpen = savedInstanceState==null&&intent.getBooleanExtra("start_ink",false)
        fingerInk = savedInstanceState?.getBoolean("fingerInk") ?: false
        penColor = savedInstanceState?.getInt("penColor") ?: Color.RED
        penWidth = savedInstanceState?.getFloat("penWidth") ?: 0.004f
        inkTool = savedInstanceState?.getString("inkTool") ?: "pen"
        highlighterColor = savedInstanceState?.getInt("highlighterColor") ?: Color.rgb(255, 215, 0)
        highlighterWidth = savedInstanceState?.getFloat("highlighterWidth") ?: 0.02f
        nextSearch = Button(this).apply {
            text = "다음 찾기"; textSize = 12f; minWidth = 0; setPadding(0, 0, 0, 0); isEnabled = false
            setOnClickListener { if (searchQuery.isNotEmpty() && highlightedPage >= 0) findMatch(searchQuery, highlightedPage, highlightedMatch + 1) }
        }
        val title=(intent.getStringExtra("name")?:"PDF").let {if(intent.data?.authority=="$packageName.pdfnotes")it.removePrefix("[필기] ")else it}
        controls = ViewerControls(this, title, { finish() }, { searchDocument() },
            { showThumbnails() },
            listOf("한쪽 보기" to { setMode(1) }, "두쪽 보기" to { setMode(2) }, "페이지 넘김" to { changeViewing(viewing.copy(scrolling=false)) }, "세로 스크롤" to { changeViewing(viewing.copy(scrolling=true)) }, "첫 페이지를 표지로 보기" to { changeViewing(viewing.copy(cover=!viewing.cover)) },
                "필기 시작" to { beginInk() }, "손가락 필기" to {
                    inkStatus.visibility = View.VISIBLE
                    if (inkNote == null) { inkStatus.text = "먼저 필기 시작을 선택해 주세요." }
                    else { inkEnabled = true; fingerInk = !fingerInk; inkStatus.text = if (fingerInk) "손가락 필기 모드" else "펜 필기 모드" }
                },
                "필기 저장 다시 시도" to { inkNote?.let { persistInk(it) } },
                "읽기 모드" to { stopInk() },
                "필기 포함 PDF 내보내기" to {openInkActions("export")}, "문서 백업" to {openInkActions("backup")},
                "다음 검색 결과" to { if (nextSearch.isEnabled) nextSearch.performClick() }),
            selectedMenuItem={name->when(name){"한쪽 보기"->mode==1;"두쪽 보기"->mode==2;"페이지 넘김"->!viewing.scrolling;"세로 스크롤"->viewing.scrolling;"첫 페이지를 표지로 보기"->viewing.cover;else->false}},
            enabledMenuItem={name->when(name){"두쪽 보기"->openedSuccessfully&&resources.configuration.screenWidthDp>resources.configuration.screenHeightDp;"필기 포함 PDF 내보내기","문서 백업"->inkNote!=null&&!inkDirty&&!inkPreparing;"다음 검색 결과"->nextSearch.isEnabled;"필기 저장 다시 시도"->inkNote!=null&&inkDirty;"읽기 모드","손가락 필기"->inkEnabled;"필기 시작"->openedSuccessfully&&!inkEnabled&&!inkPreparing;else->openedSuccessfully}},onFit={val selected=pageIndex;setZoom(1f);showPage(selected)})
        status = controls.status
        controls.setListNavigation {if(thumbnailPanel==null&&pageOverview==null)showThumbnails()}
        controls.setLocationAction {controls.showPageJump(pageIndex,pageCount)}
        controls.setReadingAction(false, false) { beginInk() }
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
                if (mode == 1 && !viewing.scrolling) {
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
                if (mode == 1 && !viewing.scrolling) {
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
            override fun performClick(): Boolean {super.performClick();return true}
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if(!viewing.scrolling&&zoom<=1f){if(event.actionMasked==MotionEvent.ACTION_UP)performClick();return true}
                return super.onTouchEvent(event)
            }
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val viewport = if (::horizontal.isInitialized && horizontal.width > 0) horizontal.width else resources.displayMetrics.widthPixels
                val width = (viewport * if (mode == 1 && !viewing.scrolling) 1f else zoom).toInt().coerceAtLeast(1)
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }.apply {
            setBackgroundColor(if (ThemePreferences.isDark(this@PdfActivity)) Color.rgb(41, 47, 51) else Color.rgb(232, 235, 239))
            dividerHeight = (8 * resources.displayMetrics.density).toInt()
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, state: Int) {
                    if (state == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) {
                        clearInkSelection()
                        pendingTarget = null
                        scrollAnchorPage = null
                        navigationGeneration++
                    }
                }
                override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {
                    if (viewing.scrolling && scrollAnchorPage==null && total > 0 && visible > 0) {
                        val firstChild = view?.getChildAt(0)
                        val secondChild = view?.getChildAt(1)
                        fun visibleHeight(child: View?) = if (child == null || view == null) 0 else
                            (minOf(child.bottom, view.height) - maxOf(child.top, 0)).coerceAtLeast(0)
                        val row = if (visibleHeight(secondChild) > visibleHeight(firstChild)) first + 1 else first
                        if (pageIndex != pageForRow(row)) clearInkSelection()
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
        contentRow = android.widget.FrameLayout(this).apply {
            addView(horizontal, android.widget.FrameLayout.LayoutParams(-1,-1))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
            addView(controls)
            val ui=UiStyle(this@PdfActivity)
            inkStatus = ui.label("",12f,color=ui.muted).apply {
                visibility = View.GONE
                setPadding(ui.dp(12),ui.dp(6),ui.dp(12),ui.dp(6))
            }
            inkTools = makeInkTools()
            inkPanel=LinearLayout(this@PdfActivity).apply {setBackgroundColor(ui.surface);gravity=android.view.Gravity.CENTER_VERTICAL}
            inkPanel.addView(inkTools);inkPanel.addView(inkStatus)
            inkCollapsed=savedInstanceState?.getBoolean("inkToolsCollapsed")?:false
            inkDock=LinearLayout(this@PdfActivity).apply {orientation=LinearLayout.VERTICAL;addView(inkPanel)}
            inkRetry=ui.button("필기 저장 다시 시도"){inkNote?.let{persistInk(it)}}.apply {visibility=View.GONE}
            inkDock.addView(inkRetry)
            inkDock.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_->layoutDocumentArea()}
            inkStatus.addTextChangedListener(object:android.text.TextWatcher{
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
                override fun afterTextChanged(s:android.text.Editable?)=Unit
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){updateInkChrome()}
            })
            updateInkChrome()
            contentRow.addView(inkDock,android.widget.FrameLayout.LayoutParams(-1,-2,if(inkAtTop())android.view.Gravity.TOP else android.view.Gravity.BOTTOM))
            addView(contentRow, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        val uri = intent.data ?: run { status.text = "PDF 오류: 파일이 없습니다."; return }
        openDocument(uri, null)
    }

    private fun inkAtTop()=resources.configuration.screenWidthDp>=600&&resources.configuration.screenHeightDp>=480

    private fun updateInkChrome() {
        val ui=UiStyle(this)
        val failed=inkStatus.text.contains("실패")||inkStatus.text.contains("오류")
        val short=resources.configuration.screenHeightDp<480
        val collapsed=short&&inkCollapsed&&!failed
        controls.setInkToolsAction(inkEnabled&&short&&!failed,collapsed){inkCollapsed=!inkCollapsed;updateInkChrome()}
        val notice=inkPreparing||failed||inkStatus.text.startsWith("암호 PDF")
        val expanded=inkEnabled&&!collapsed
        if(expanded!=inkChromeExpanded) {
            inkChromeExpanded=expanded
            inkPanel.animation?.setAnimationListener(null)
            inkPanel.clearAnimation()
            inkChromeAnimating=true
            inkPanel.startAnimation(android.view.animation.TranslateAnimation(
                android.view.animation.Animation.RELATIVE_TO_SELF,0f,android.view.animation.Animation.RELATIVE_TO_SELF,0f,
                android.view.animation.Animation.RELATIVE_TO_SELF,if(expanded)-1f else 0f,
                android.view.animation.Animation.RELATIVE_TO_SELF,if(expanded)0f else -1f
            ).apply {
                duration=280;interpolator=android.view.animation.DecelerateInterpolator()
                setAnimationListener(object:android.view.animation.Animation.AnimationListener {
                    override fun onAnimationStart(animation:android.view.animation.Animation)=Unit
                    override fun onAnimationRepeat(animation:android.view.animation.Animation)=Unit
                    override fun onAnimationEnd(animation:android.view.animation.Animation){inkChromeAnimating=false;updateInkChrome()}
                })
            })
        }
        inkDock.visibility=if(expanded||notice||inkChromeAnimating)View.VISIBLE else View.GONE
        inkDock.post{if(!closed)layoutDocumentArea()}
        inkTools.visibility=if(expanded||inkChromeAnimating)View.VISIBLE else View.GONE
        val inline=inkAtTop()&&!failed
        inkPanel.orientation=if(inline)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        inkTools.layoutParams=if(inline)LinearLayout.LayoutParams(0,-2,1f)else LinearLayout.LayoutParams(-1,-2)
        inkStatus.layoutParams=LinearLayout.LayoutParams(if(inline)ui.dp(180)else -1,-2)
        inkStatus.visibility=if(inkStatus.text.isNotBlank()&&(inkEnabled||notice))View.VISIBLE else View.GONE
        inkStatus.setTextColor(if(failed)ui.danger else ui.muted)
        inkRetry.visibility=if(failed&&inkDirty)View.VISIBLE else View.GONE
    }

    private fun setStylusEraser(pressed: Boolean) {
        if(stylusEraser==pressed)return
        stylusEraser=pressed
        invalidateInk()
    }

    private fun updateStylusEraser(event: MotionEvent) {
        if(event.actionMasked==MotionEvent.ACTION_CANCEL){setStylusEraser(false);return}
        if(event.getToolType(event.actionIndex)!=MotionEvent.TOOL_TYPE_STYLUS)return
        setStylusEraser(inkEnabled&&inkNote!=null&&event.actionMasked!=MotionEvent.ACTION_HOVER_EXIT&&event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY))
        if(event.actionMasked==MotionEvent.ACTION_BUTTON_PRESS||event.actionMasked==MotionEvent.ACTION_BUTTON_RELEASE){
            val pointer=event.findPointerIndex(inkPointer)
            val image=inkImage
            if(pointer>=0&&image!=null)inkPoint(image,event,pointer)?.let { switchInkSegment(image,it,if(stylusEraser)"eraser"else inkTool) }
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        updateStylusEraser(event)
        if(inkEnabled&&inkNote!=null&&event.getToolType(event.actionIndex)==MotionEvent.TOOL_TYPE_STYLUS&&
            (event.actionMasked==MotionEvent.ACTION_BUTTON_PRESS||event.actionMasked==MotionEvent.ACTION_BUTTON_RELEASE)&&
            event.actionButton==MotionEvent.BUTTON_STYLUS_PRIMARY)return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(!hasFocus){setStylusEraser(false);clearInkInput()}
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        updateStylusEraser(event)
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            val bounds=android.graphics.Rect()
            touchOnOverlay=listOfNotNull(thumbnailPanel,if(::inkDock.isInitialized)inkDock else null).any {
                it.visibility==View.VISIBLE&&it.getGlobalVisibleRect(bounds)&&bounds.contains(event.rawX.toInt(),event.rawY.toInt())
            }
        }
        if(touchOnOverlay)return super.dispatchTouchEvent(event)
        if (handleInk(event)) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val location = IntArray(2)
            if (::horizontal.isInitialized) horizontal.getLocationOnScreen(location)
            touchInDocument = ::horizontal.isInitialized && event.rawX >= location[0] && event.rawX < location[0] + horizontal.width &&
                event.rawY >= location[1] && event.rawY < location[1] + horizontal.height
        }
        if (touchInDocument && ::scaleDetector.isInitialized) {
            if(event.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER)tapDetector.onTouchEvent(event)
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
                if (inDocument && !viewing.scrolling && !(inkEnabled&&fingerInk) && !multiTouch && zoom <= 1f &&
                    kotlin.math.abs(dx) > 48 * resources.displayMetrics.density &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.25f) {
                    super.dispatchTouchEvent(event)
                    movePage(if (dx < 0) 1 else -1)
                    return true
                }
            }
        }
        if (touchInDocument && (mode == 1 && !viewing.scrolling) && zoom > 1f) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                pageOffsetX = panStartX + event.rawX - touchX
                pageOffsetY = panStartY + event.rawY - touchY
                currentPageImage()?.let { applyPageMatrix(it) }
            }
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun inkPoint(image: ImageView, event: MotionEvent, pointer: Int): InkPoint? {
        val drawable = image.drawable ?: return null
        val matrix = android.graphics.Matrix()
        image.transformMatrixToGlobal(matrix)
        val inverse = android.graphics.Matrix()
        if (!matrix.invert(inverse)) return null
        val point = floatArrayOf(event.getRawX(pointer), event.getRawY(pointer))
        inverse.mapPoints(point)
        if (!image.imageMatrix.invert(inverse)) return null
        inverse.mapPoints(point)
        return InkPoint((point[0] / drawable.intrinsicWidth).coerceIn(0f, 1f),
            (point[1] / drawable.intrinsicHeight).coerceIn(0f, 1f), event.getPressure(pointer).coerceIn(0f, 1f), event.eventTime)
    }

    private fun clearInkInput() {
        activeStroke = null; inkPoints.clear(); erasedStrokes.clear(); inkGestureStrokes = null
        selectionPreview = null; movingSelection = false
        inkImage?.invalidate(); inkPointer = -1; inkImage = null
    }

    private fun startInkSegment(image: ImageView, point: InkPoint) {
        inkPoints.clear(); inkPoints.add(point); erasedStrokes.clear()
        val page = image.getTag(R.id.pdf_page_index) as Int
        activeStroke = when(activeInkTool) {
            "pen" -> InkStroke(page,penWidth,penColor,inkPoints)
            "highlighter" -> InkStroke(page,highlighterWidth,(highlighterColor and 0x00ffffff) or 0x60000000,inkPoints,"highlighter")
            else -> null
        }
        if(activeInkTool=="eraser")eraseInk(image,point,point)
    }

    // 접촉 중 도구 전환은 임시 목록에만 반영하고 손을 뗄 때 한 번 저장
    private fun finishInkSegment() {
        val strokes = inkGestureStrokes ?: return
        val stroke = activeStroke?.copy(points=inkPoints.toList())
        inkGestureStrokes = if(activeInkTool=="eraser")strokes.filterIndexed { index, _ -> index !in erasedStrokes }
            else if(stroke!=null)strokes+stroke else strokes
        activeStroke = null; erasedStrokes.clear()
    }

    private fun switchInkSegment(image: ImageView, point: InkPoint, tool: String) {
        if(activeInkTool==tool)return
        finishInkSegment()
        clearInkSelection()
        activeInkTool=tool
        startInkSegment(image,point)
        image.invalidate()
    }

    private fun handleInk(event: MotionEvent): Boolean {
        if (inkPointer >= 0) {
            val pointer = event.findPointerIndex(inkPointer)
            val canceled = event.actionMasked == MotionEvent.ACTION_CANCEL ||
                (android.os.Build.VERSION.SDK_INT >= 33 && event.flags and MotionEvent.FLAG_CANCELED != 0 && event.getPointerId(event.actionIndex) == inkPointer)
            val finger = pointer >= 0 && event.getToolType(pointer) == MotionEvent.TOOL_TYPE_FINGER
            if (canceled || (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && finger)) {
                clearInkInput()
                return canceled
            }
            val image = inkImage
            if (event.actionMasked == MotionEvent.ACTION_MOVE && pointer >= 0 && image != null) {
                inkPoint(image, event, pointer)?.let { p ->
                    val tool = if(stylusEraser&&event.getToolType(pointer)==MotionEvent.TOOL_TYPE_STYLUS)"eraser"else inkTool
                    switchInkSegment(image,p,tool)
                    if (activeInkTool == "eraser") eraseInk(image, inkPoints.last(), p)
                    if (movingSelection) selectionPreview = inkSelection?.moved(p.x - inkPoints.first().x, p.y - inkPoints.first().y)
                    inkPoints.add(p)
                }
                image.invalidate()
            }
            if ((event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_POINTER_UP) && event.getPointerId(event.actionIndex) == inkPointer) {
                if (image != null && pointer >= 0) inkPoint(image, event, pointer)?.let { p ->
                    if (activeInkTool == "eraser") eraseInk(image, inkPoints.last(), p)
                    if (movingSelection) selectionPreview = inkSelection?.moved(p.x - inkPoints.first().x, p.y - inkPoints.first().y)
                    inkPoints.add(p)
                }
                finishInkSegment()
                inkGestureStrokes?.let { commitInk(it) }
                if (activeInkTool == "lasso") {
                    if (movingSelection) {
                        selectionPreview?.let { moved -> commitInk(moved.documentStrokes); inkSelection = moved }
                    } else {
                        val page = image?.getTag(R.id.pdf_page_index) as? Int
                        inkSelection = if (page == null) null else InkSelection.enclose(inkNote!!.strokes, page, inkPoints)
                        if (!inkDirty) inkStatus.text = inkSelection?.let { "${it.indices.size}개 획 선택 · 끌어서 이동" } ?: "선택한 필기 없음"
                    }
                    selectionPreview = null; movingSelection = false
                    invalidateInk()
                }
                clearInkInput()
            }
            return true
        }
        if (!inkEnabled || inkNote == null || event.actionMasked != MotionEvent.ACTION_DOWN) return false
        val tool = event.getToolType(0)
        if (tool != MotionEvent.TOOL_TYPE_STYLUS && !(fingerInk && tool == MotionEvent.TOOL_TYPE_FINGER)) return false
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val image = row.getChildAt(j) as? ImageView ?: continue
                val bounds = android.graphics.Rect()
                if (!image.getGlobalVisibleRect(bounds) || !bounds.contains(event.rawX.toInt(), event.rawY.toInt())) continue
                val drawable = image.drawable ?: continue
                val point = inkPoint(image, event, 0) ?: continue
                val area = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
                image.imageMatrix.mapRect(area)
                val location = IntArray(2); image.getLocationOnScreen(location)
                if (!area.contains(event.rawX - location[0], event.rawY - location[1])) continue
                inkPointer = event.getPointerId(0); inkImage = image
                inkGestureStrokes = requireNotNull(inkNote).strokes
                activeInkTool = if(stylusEraser&&tool==MotionEvent.TOOL_TYPE_STYLUS) "eraser" else inkTool
                if(stylusEraser&&tool==MotionEvent.TOOL_TYPE_STYLUS)clearInkSelection()
                startInkSegment(image,point)
                if (activeInkTool == "lasso") {
                    val selected = inkSelection
                    val radius = 12 * resources.displayMetrics.density / image.imageMatrix.mapRadius(1f)
                    val padX = radius / drawable.intrinsicWidth; val padY = radius / drawable.intrinsicHeight
                    movingSelection = selected != null && selected.page == image.getTag(R.id.pdf_page_index) &&
                        point.x in (selected.bounds.left - padX)..(selected.bounds.right + padX) &&
                        point.y in (selected.bounds.top - padY)..(selected.bounds.bottom + padY)
                    if (!movingSelection) inkSelection = null
                    selectionPreview = null
                }
                scaleDetector.onTouchEvent(event)
                image.parent.requestDisallowInterceptTouchEvent(true)
                image.invalidate()
                return true
            }
        }
        return false
    }

    private class InkLayer {
        val node = android.graphics.RenderNode("완료된 페이지 필기").apply { setUseCompositingLayer(true, null) }
        var strokes: List<InkStroke>? = null
        var drawable: android.graphics.drawable.Drawable? = null
        val matrix = android.graphics.Matrix()
    }

    private fun drawStrokes(canvas: Canvas, strokes: List<InkStroke>, page: Int, width: Int, height: Int) {
        for (stroke in strokes) {
            if (stroke.page != page) continue
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = stroke.color; strokeWidth = stroke.width * minOf(width, height)
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                if (stroke.tool == "highlighter") blendMode = android.graphics.BlendMode.MULTIPLY
            }
            val path = android.graphics.Path()
            stroke.points.forEachIndexed { i, p ->
                val x = p.x * width; val y = p.y * height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (stroke.points.size == 1) { paint.style = Paint.Style.FILL; val p = stroke.points[0]; canvas.drawCircle(p.x * width, p.y * height, paint.strokeWidth / 2, paint) }
            else canvas.drawPath(path, paint)
        }
    }

    private fun drawInk(canvas: Canvas, image: ImageView, page: Int, layer: InkLayer? = null) {
        val drawable = image.drawable ?: return
        val strokes = (selectionPreview?.documentStrokes ?: inkGestureStrokes ?: inkNote?.strokes).orEmpty()
        val cached = layer != null && canvas.isHardwareAccelerated && selectionPreview == null && erasedStrokes.isEmpty() && strokes.isNotEmpty()
        if (!cached) layer?.strokes = null
        if (cached) {
            requireNotNull(layer)
            if (layer.strokes !== strokes || layer.drawable !== drawable || layer.matrix != image.imageMatrix || layer.node.width != image.width || layer.node.height != image.height) {
                layer.node.setPosition(0, 0, image.width, image.height)
                val recording = layer.node.beginRecording()
                try {
                    recording.concat(image.imageMatrix)
                    recording.clipRect(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
                    // 형광펜 MULTIPLY를 원본 배경과 함께 합성하고 확대 시 현재 화면 해상도로 다시 기록
                    drawable.draw(recording)
                    drawStrokes(recording, strokes, page, drawable.intrinsicWidth, drawable.intrinsicHeight)
                } finally { layer.node.endRecording() }
                layer.strokes = strokes; layer.drawable = drawable; layer.matrix.set(image.imageMatrix)
            }
            canvas.drawRenderNode(layer.node)
        }
        val saved = canvas.save()
        canvas.concat(image.imageMatrix)
        canvas.clipRect(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        if (!cached) drawStrokes(canvas, if (erasedStrokes.isEmpty()) strokes else strokes.filterIndexed { index, _ -> index !in erasedStrokes }, page, drawable.intrinsicWidth, drawable.intrinsicHeight)
        activeStroke?.takeIf { it.page == page }?.let { drawStrokes(canvas, listOf(it), page, drawable.intrinsicWidth, drawable.intrinsicHeight) }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 135, 155); style = Paint.Style.STROKE
            strokeWidth = 2 * resources.displayMetrics.density / image.imageMatrix.mapRadius(1f)
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(strokeWidth * 3, strokeWidth * 2), 0f)
        }
        (selectionPreview ?: inkSelection)?.takeIf { it.page == page }?.let { selection ->
            val b = selection.bounds
            canvas.drawRect(b.left * drawable.intrinsicWidth - outline.strokeWidth * 3,
                b.top * drawable.intrinsicHeight - outline.strokeWidth * 3,
                b.right * drawable.intrinsicWidth + outline.strokeWidth * 3,
                b.bottom * drawable.intrinsicHeight + outline.strokeWidth * 3, outline)
        }
        if (activeInkTool == "lasso" && !movingSelection && inkImage === image && inkPoints.isNotEmpty()) {
            val path = android.graphics.Path()
            inkPoints.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(p.x * drawable.intrinsicWidth, p.y * drawable.intrinsicHeight)
                else path.lineTo(p.x * drawable.intrinsicWidth, p.y * drawable.intrinsicHeight)
            }
            path.close(); canvas.drawPath(path, outline)
        }
        canvas.restoreToCount(saved)
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
                val restoredNote = if (uri.authority == "$packageName.pdfnotes") {
                    inkWorker.submit<PdfInkNote> { inkStore.load(requireNotNull(uri.lastPathSegment)) }.get()
                } else null
                synchronized(PdfActivity::class.java) {
                    if (!oldTemporaryFilesCleaned) {
                        val files = cacheDir.listFiles() ?: throw IOException("임시 폴더를 확인할 수 없습니다.")
                        files.filter { it.isFile && it.name.startsWith("pdf-") && it.name.endsWith(".pdf") }.forEach {
                            if (!it.delete()) throw IOException("이전 PDF 임시 파일을 정리할 수 없습니다.")
                        }
                        oldTemporaryFilesCleaned = true
                    }
                }
                val cached = if (restoredNote == null) wordSnapshot?.let{File(it,"background.pdf")}?:temporaryFile else null
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
                if (restoredNote != null && restoredNote.pages != opened.pageCount) {
                    opened.close()
                    throw IOException("필기 문서의 페이지 구성이 달라졌습니다. 기존 필기는 보관됩니다.")
                }
                val previousRenderer = renderer
                renderer = opened
                previousRenderer?.close()
                pageCount = opened.pageCount
                if (pageCount == 0) throw IOException("표시할 페이지가 없습니다.")
                val initialPage = pageIndex.coerceIn(0, pageCount - 1)
                if (!closed) runOnUiThread {
                    if (!closed) {
                        setResult(RESULT_OK, android.content.Intent().setData(uri).putExtra("name", intent.getStringExtra("name")))
                        openedSuccessfully = true
                        inkNote = restoredNote
                        if (restoredNote != null) {
                            val retained = retainedInkSession
                            retainedInkSession = null
                            if (retained?.note?.id == restoredNote.id) {
                                inkNote = retained.note
                                inkUndo.addAll(retained.undo); inkRedo.addAll(retained.redo)
                            }
                            inkTools.visibility = View.VISIBLE
                            inkStatus.visibility = View.VISIBLE
                            inkEnabled = enableInkAfterOpen || inkEnabled
                            inkStatus.text = if (enableInkAfterOpen) "" else "필기 복원 완료"
                            enableInkAfterOpen = false
                            if (retained?.note?.id == restoredNote.id && retained.dirty) persistInk(retained.note)
                        }
                        invalidateInk()
                        recent.add(uri, intent.getStringExtra("name") ?: "document.pdf")
                        scrollAnchorPage=initialPage
                        list.adapter = PageAdapter()
                        list.setSelection(rowForPage(initialPage))
                        updateStatus()
                        list.post {
                            if (!closed) {
                                persistenceReady = true
                                updateStatus()
                            }
                        }
                        if (restoreOverview) {
                            restoreOverview=false;horizontal.post {if(!closed)showPageOverview()}
                        }
                        if (restorePanel) {
                            restorePanel = false
                            horizontal.post { if (!closed) showThumbnails() }
                        }
                    }
                }
            } catch (_: PdfPasswordRequired) {
                encryptedDocument = true
                if (!closed) runOnUiThread {
                    if (!closed) {
                        if (android.os.Build.VERSION.SDK_INT < 35) {
                            status.text = "PDF 오류: 암호 PDF는 Android 15 이상에서 열 수 있습니다."
                        } else {
                            UiStyle(this).passwordDialog("PDF 비밀번호",if(password==null)null else"비밀번호가 맞지 않거나 지원하지 않는 암호 방식입니다.",cancel={finish()}) {value->openDocument(uri,value)}
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

    @Deprecated("Android 뒤로 이동 처리")
    override fun onBackPressed() {
        if(controls.searchIsOpen)controls.closeSearch()else super.onBackPressed()
    }

    override fun onStop() {
        saveResult()
        super.onStop()
    }

    private fun showPage(index: Int) {
        if (index !in 0 until pageCount) return
        clearInkSelection()
        navigationGeneration++
        if (viewing.scrolling) {
            pendingTarget = index
            scrollAnchorPage = index
            pageIndex=index;updateStatus()
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
        val row=viewing.row(pageIndex,mode)+direction
        if(row in 0 until viewing.rows(pageCount,mode))showPage(viewing.first(row,mode))
    }

    private fun rowForPage(index: Int) = if(viewing.scrolling)viewing.row(index,mode)else 0
    private fun pageForRow(row: Int) = if(viewing.scrolling)viewing.first(row,mode)else viewing.first(viewing.row(pageIndex,mode),mode)

    private fun setMode(value: Int) = changeViewing(viewing.copy(landscapePages=value))

    private fun changeViewing(value: PageViewing) {
        val selected=pageIndex
        viewing=value
        intent.data?.let {viewing.save(this,it)}
        mode=viewing.columns(resources.configuration.screenWidthDp,resources.configuration.screenHeightDp)
        clearInkSelection();pendingTarget=null;navigationGeneration++
        zoom=1f;pageOffsetX=0f;pageOffsetY=0f
        horizontal.scrollTo(0,0)
        pageIndex=selected
        scrollAnchorPage=selected
        renderGeneration++
        list.adapter=PageAdapter()
        list.setSelection(rowForPage(selected))
        updateStatus()
    }

    private fun updateStatus() {
        status.text = "${pageIndex + 1}/$pageCount · ${(zoom * 100).toInt()}%"
        val kind=if(intent.data?.authority=="$packageName.pdfnotes"||wordSnapshot!=null)when(intent.getStringExtra("name")?.substringAfterLast('.')?.lowercase()) {
            "pptx"->"PPT";"docx"->"Word";"hwp","hwpx"->"한글";else->"PDF"
        }else"PDF"
        status.contentDescription = "$kind ${pageIndex + 1} / $pageCount · ${(zoom * 100).toInt()}%$searchMessage"
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
        controls.showSearch(searchQuery,
            next={if(nextSearch.isEnabled)findMatch(searchQuery,highlightedPage,highlightedMatch+1)},
            closed={
                searchGeneration++;searching=false;nextSearch.isEnabled=false
                highlightedPage=-1;highlightedBounds=emptyList();searchMessage=""
                (list.adapter as? BaseAdapter)?.notifyDataSetChanged();updateStatus()
            }) { query -> searchQuery=query;findMatch(query,0,0) }
    }

    private fun findMatch(query: String, startPage: Int, startMatch: Int) {
        if (android.os.Build.VERSION.SDK_INT < 35) return
        val generation = ++searchGeneration
        searching = true
        nextSearch.isEnabled = false
        controls.setSearchResult("검색 중")
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
                        controls.setSearchResult(if(found>=0)"${found+1}쪽 · 이 페이지 ${matchIndex+1}번째"else"검색 결과 없음",found>=0)
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
                        controls.setSearchResult("검색 실패 · 검색어를 확인해 주세요")
                        (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    private fun layoutDocumentArea() {
        if(!::horizontal.isInitialized||!::inkDock.isInitialized)return
        val left=if(thumbnailPanel!=null&&UiStyle(this).useSidePanel())UiStyle(this).dp(200)else 0
        val tools=if(inkDock.visibility==View.VISIBLE)inkDock.height else 0
        val document=horizontal.layoutParams as android.widget.FrameLayout.LayoutParams
        val top=if(inkAtTop())tools else 0
        val bottom=if(inkAtTop())0 else tools
        if(document.leftMargin==left&&document.topMargin==top&&document.bottomMargin==bottom)return
        document.leftMargin=left;document.topMargin=top;document.bottomMargin=bottom
        horizontal.layoutParams=document
        val dock=inkDock.layoutParams as android.widget.FrameLayout.LayoutParams
        dock.leftMargin=left;inkDock.layoutParams=dock
    }

    private fun resizeDocument() {
        if(horizontal.width<=0||horizontal.height<=0)return
        if(documentAreaWidth>0&&(documentAreaWidth!=horizontal.width||documentAreaHeight!=horizontal.height)){
            zoom=1f;pageOffsetX=0f;pageOffsetY=0f
        }
        val changed=documentAreaWidth!=horizontal.width||documentAreaHeight!=horizontal.height
        documentAreaWidth=horizontal.width;documentAreaHeight=horizontal.height
        if(changed){renderGeneration++;(list.adapter as? BaseAdapter)?.notifyDataSetChanged();list.setSelection(rowForPage(pageIndex))}

        val width = (horizontal.width * if (mode == 1 && !viewing.scrolling) 1f else zoom).toInt().coerceAtLeast(1)
        if (list.layoutParams.width != width) {
            renderGeneration++
            list.layoutParams = android.widget.FrameLayout.LayoutParams(width, -1)
            (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
        }
        if(changed&&openedSuccessfully)updateStatus()
    }

    private fun setZoom(value: Float, focusX: Float? = null, focusY: Float? = null, targetX: Float? = focusX, targetY: Float? = focusY) {
        if (mode == 1 && !viewing.scrolling) {
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

    private fun applySpreadMatrix(image: ImageView, left: Boolean) {
        val drawable=image.drawable?:return
        if(image.width==0||image.height==0)return
        val scale=minOf(image.width.toFloat()/drawable.intrinsicWidth,image.height.toFloat()/drawable.intrinsicHeight)
        image.imageMatrix=android.graphics.Matrix().apply {
            setScale(scale,scale)
            postTranslate(if(left)image.width-drawable.intrinsicWidth*scale else 0f,(image.height-drawable.intrinsicHeight*scale)/2f)
        }
    }

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
            if (!closed && (mode == 1 && !viewing.scrolling) && pageIndex == index && currentPageImage() === image && image.tag == key) {
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
            if(!thumbnail&&!previewRecorded){
                previewRecorded=true
                intent.data?.let{uri->
                    if(index==0)DocumentPreviewCache.record(applicationContext,uri,result)
                    else worker.execute {
                        try { if(!closed)DocumentPreviewCache.record(applicationContext,uri,drawPage(0,180,thumbnail=true)) }
                        catch(error:Exception){runOnUiThread{if(!closed)status.text="미리보기 오류: ${error.message}"}}
                    }
                }
            }
            result
        }
    }

    private fun pageList(sidebar: Boolean, choose: (Int) -> Unit): LinearLayout {
        val ui = UiStyle(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ui.background) }
        val pages = (0 until pageCount).toList()
        val grid = GridView(this).apply {
            contentDescription = "페이지 목록"; numColumns = if (sidebar) 1 else GridView.AUTO_FIT
            columnWidth = ui.dp(142); stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = ui.dp(16); verticalSpacing = ui.dp(16)
            setPadding(ui.dp(16),ui.dp(12),ui.dp(16),ui.dp(16));clipToPadding=false
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = pages.size
            override fun getItem(position: Int) = pages[position]
            override fun getItemId(position: Int) = pages[position].toLong()
            override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                val page = pages[position]
                val item = LinearLayout(this@PdfActivity).apply {
                    orientation = LinearLayout.VERTICAL;contentDescription="미리보기 ${page+1}쪽"+if(page==pageIndex)", 선택됨"else""
                    layoutParams=AbsListView.LayoutParams(-1,ui.dp(if(sidebar)190 else 224))
                    setPadding(ui.dp(4),ui.dp(4),ui.dp(4),0)
                    background=ui.rounded(if(page==pageIndex)ui.selected else ui.background,6,if(page==pageIndex)ui.text else ui.line)
                }
                val image = object : ImageView(this@PdfActivity) {
                    override fun onDraw(canvas: Canvas) { super.onDraw(canvas);drawInk(canvas,this,page) }
                }.apply { tag=page;scaleType=ImageView.ScaleType.FIT_CENTER;setImageBitmap(thumbnails.get(page));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                item.addView(image,LinearLayout.LayoutParams(-1,0,1f))
                item.addView(ui.label("${page+1}쪽"+if(page==pageIndex)" · 현재"else"",12f,page==pageIndex).apply {gravity=android.view.Gravity.CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(-1,ui.dp(32)))
                if(thumbnails.get(page)==null)worker.execute {
                    try { if(!closed){val bitmap=drawPage(page,180,thumbnail=true);runOnUiThread {if(!closed){thumbnails.put(page,bitmap);if(image.tag==page)image.setImageBitmap(bitmap)}}} }
                    catch(error:Exception){runOnUiThread{if(!closed)status.text="PDF 오류: ${error.message}"}}
                }
                return item
            }
        }
        controls.attachListNavigation(root,pageIndex,pageCount,choose)
        root.addView(grid,LinearLayout.LayoutParams(-1,0,1f));grid.adapter=adapter
        grid.setOnItemClickListener {_,_,position,_->choose(pages[position]);adapter.notifyDataSetChanged()}
        grid.setSelection(pages.indexOf(pageIndex).coerceAtLeast(0))
        return root
    }

    private fun showPageOverview() {
        if(pageCount==0||searching||pageOverview!=null)return
        clearInkSelection()
        val ui=UiStyle(this)
        val dialog=android.app.Dialog(this,if(ui.dark)R.style.MinViewerDark else R.style.MinViewerLight)
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setBackgroundColor(ui.background)
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
                insets
            }
        }
        val bar=LinearLayout(this).apply {gravity=android.view.Gravity.CENTER_VERTICAL;setBackgroundColor(ui.navy)}
        bar.addView(ui.button("페이지 목록 닫기","뒤로",Color.WHITE){dialog.dismiss()},LinearLayout.LayoutParams(ui.dp(48),ui.dp(56)))
        bar.addView(ui.label(intent.getStringExtra("name")?:"페이지",16f,true,Color.WHITE).apply {gravity=android.view.Gravity.CENTER_VERTICAL;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,ui.dp(56),1f))
        bar.addView(ui.button("읽기",color=Color.WHITE){dialog.dismiss()},LinearLayout.LayoutParams(ui.dp(64),ui.dp(56)))
        root.addView(bar)
        root.addView(ui.label("페이지 · ${pageCount}쪽",20f,true).apply {setPadding(ui.dp(20),ui.dp(16),ui.dp(20),ui.dp(8))})
        root.addView(pageList(false){page->dialog.dismiss();showPage(page)},LinearLayout.LayoutParams(-1,0,1f))
        dialog.setContentView(root)
        dialog.setOnDismissListener {pageOverview=null;thumbnailPanel?.let {controls.attachListNavigation(it,pageIndex,pageCount){page->showPage(page)}}}
        dialog.window?.setWindowAnimations(R.style.PageListMotion)
        pageOverview=dialog;dialog.show();dialog.window?.setLayout(-1,-1)
        @Suppress("DEPRECATION")
        dialog.window?.decorView?.systemUiVisibility=if(ui.dark)0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    private fun showThumbnails() {
        if(pageCount==0||searching)return
        thumbnailPanel?.let {panel->
            animateThumbnailPanel(panel,0){contentRow.removeView(panel);thumbnailPanel=null}
            return
        }
        if(!UiStyle(this).useSidePanel()){showPageOverview();return}
        val ui=UiStyle(this)
        val panel=pageList(true){showPage(it)}
        thumbnailPanel=panel;contentRow.addView(panel,android.widget.FrameLayout.LayoutParams(0,-1,android.view.Gravity.START))
        animateThumbnailPanel(panel,ui.dp(200)){}
    }

    private fun animateThumbnailPanel(panel:View,target:Int,finished:()->Unit) {
        thumbnailAnimator?.let {it.removeAllListeners();it.cancel()}
        thumbnailAnimator=android.animation.ValueAnimator.ofInt(panel.layoutParams.width,target).apply {
            duration=280;interpolator=android.view.animation.DecelerateInterpolator()
            addUpdateListener {panel.layoutParams=panel.layoutParams.apply {width=it.animatedValue as Int}}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(animation:android.animation.Animator){finished();layoutDocumentArea()}})
            start()
        }
    }

    private inner class PageAdapter : BaseAdapter() {
        override fun getCount() = if(viewing.scrolling)viewing.rows(pageCount,mode)else 1
        override fun getItem(position: Int) = pageForRow(position)
        override fun getItemId(position: Int) = getItem(position).toLong()
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val page = getItem(position)
            val row = (recycled as? LinearLayout ?: LinearLayout(this@PdfActivity)).apply {
                orientation = LinearLayout.HORIZONTAL
                minimumHeight = 0
                layoutParams=AbsListView.LayoutParams(-1, (horizontal.height*if(mode==1&&!viewing.scrolling)1f else zoom).toInt().coerceAtLeast(1))
                gravity = android.view.Gravity.CENTER
                removeAllViews()
            }
            val singleCover=mode==2&&viewing.cover&&page==0
            val columns=if(singleCover)1 else mode
            val count=minOf(columns,pageCount-page)
            val gap=if(columns==2)UiStyle(this@PdfActivity).dp(6)else 0
            repeat(count) { offset ->
                val index = page + offset
                val width = ((parent.width-gap) / columns).coerceAtLeast(1)
                val renderWidth = (((((width * if (mode == 1 && !viewing.scrolling) zoom else 1f).toInt()) + 127) / 128) * 128).coerceAtMost(2560)
                val key = "$index:$renderWidth"
                val generation = renderGeneration
                val image = object : ImageView(this@PdfActivity) {
                    private val inkLayer = InkLayer()
                    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
                        super.onSizeChanged(w,h,oldw,oldh)
                        if(columns==2)applySpreadMatrix(this,offset==0)
                    }
                    override fun onDraw(canvas: Canvas) {
                        super.onDraw(canvas)
                        drawInk(canvas, this, index, inkLayer)
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
                    setTag(R.id.pdf_page_index, index)
                    scaleType = if (columns==2||mode == 1 && !viewing.scrolling) ImageView.ScaleType.MATRIX else ImageView.ScaleType.FIT_CENTER
                    tag = key
                    layoutParams = LinearLayout.LayoutParams(width, row.layoutParams.height)
                }
                if(offset>0)row.addView(View(this@PdfActivity),LinearLayout.LayoutParams(gap,1))
                row.addView(image)
                fun display(bitmap: Bitmap) {
                    val height = row.layoutParams.height
                    image.layoutParams = LinearLayout.LayoutParams(width, height)
                    image.setImageBitmap(bitmap)
                    if(columns==2)image.post {if(image.parent===row)applySpreadMatrix(image,offset==0)}
                    if (mode == 1 && !viewing.scrolling) image.post { if (image.parent === row) applyPageMatrix(image) }
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
            if(count<columns){
                row.addView(View(this@PdfActivity),LinearLayout.LayoutParams(gap,1))
                row.addView(View(this@PdfActivity),LinearLayout.LayoutParams((parent.width-gap)/columns,1))
            }
            return row
        }
    }

    @Deprecated("Android Activity의 구성 변경 상태 유지")
    override fun onRetainNonConfigurationInstance(): Any? =
        inkNote?.let { InkSession(it, inkUndo.toList(), inkRedo.toList(), inkDirty) } ?: retainedInkSession

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("inkToolsCollapsed",inkCollapsed)
        outState.putInt("page", pageIndex)
        outState.putInt("mode", mode)
        outState.putFloat("zoom", zoom)
        outState.putFloat("pageOffsetX", pageOffsetX)
        outState.putFloat("pageOffsetY", pageOffsetY)
        outState.putBoolean("inkEnabled", inkEnabled)
        outState.putBoolean("fingerInk", fingerInk)
        outState.putInt("penColor", penColor)
        outState.putFloat("penWidth", penWidth)
        outState.putString("inkTool", inkTool)
        outState.putInt("highlighterColor", highlighterColor)
        outState.putFloat("highlighterWidth", highlighterWidth)
        outState.putBoolean("thumbnailPanel", thumbnailPanel != null)
        outState.putBoolean("pageOverview", pageOverview != null)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        closed = true
        thumbnailAnimator?.let {it.removeAllListeners();it.cancel()}
        inkPanel.animation?.setAnimationListener(null);inkPanel.clearAnimation()
        pageOverview?.dismiss()
        inkSettings?.dismiss()
        searchGeneration++
        worker.queue.clear()
        worker.execute {
            try { renderer?.close() } finally { temporaryFile?.delete() }
        }
        worker.shutdown()
        cache.evictAll()
        thumbnails.evictAll()
        if(!isChangingConfigurations)wordSnapshot?.let {directory->PdfInkStore.io.execute {directory.deleteRecursively()}}
        super.onDestroy()
    }
}
