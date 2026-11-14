package org.minviewer.app

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

data class LibraryDocument(val uri: Uri, val name: String, val detail: String,
    val open: () -> Unit, val manage: () -> Unit, val manageLabel: String)

/** 창 너비에 맞춰 격자와 목록을 표시하는 단일 최근 문서 화면. */
class DocumentLibraryView(activity: Activity, private val onOpen: () -> Unit,
    private val onSettings: () -> Unit) : LinearLayout(activity) {
    private val ui = UiStyle(activity)
    private var documents = emptyList<LibraryDocument>()
    private var shown = documents
    private var query = ""
    private var filter = "전체"
    private var rows = false
    private var wide: Boolean? = null
    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private lateinit var count: TextView
    private val filterButtons = ArrayList<Button>()
    private var scrollPosition = 0
    val statusArea = LinearLayout(activity).apply { orientation = VERTICAL }
    private val adapter = Cards()

    init { orientation = HORIZONTAL;setBackgroundColor(ui.background);build(false) }
    fun setDocuments(items: List<LibraryDocument>) { documents=items;applyFilter() }
    fun saveState(state: Bundle) { state.putString("libraryQuery",query);state.putString("libraryFilter",filter);state.putBoolean("libraryRows",rows);state.putInt("libraryScroll",grid.firstVisiblePosition) }
    fun restoreState(state: Bundle?) {
        if(state==null)return
        query=state.getString("libraryQuery").orEmpty();filter=state.getString("libraryFilter")?.takeIf{it in listOf("전체","PDF","한글","Word","PPT","Excel")}?:"전체";rows=state.getBoolean("libraryRows");scrollPosition=state.getInt("libraryScroll");build(wide==true)
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        val next=w>=ui.dp(840)
        if(wide!=next){wide=next;post { if(isAttachedToWindow)build(next) }}
    }
    private fun build(expanded: Boolean) {
        if(::grid.isInitialized&&grid.childCount>0)scrollPosition=grid.firstVisiblePosition
        removeAllViews();filterButtons.clear()
        val body=LinearLayout(context).apply {orientation=VERTICAL}
        addView(body,LayoutParams(0,-1,1f))
        val bar=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(ui.dp(12),0,ui.dp(8),0);setBackgroundColor(ui.navy)}
        bar.addView(HomeScreen.LogoView(ui.activity),LayoutParams(0,ui.dp(64),1f))
        bar.addView(ui.button("파일 열기","추가",ui.navy,onOpen).apply {background=ui.rounded(Color.WHITE,24)},LayoutParams(ui.dp(48),ui.dp(48)))
        bar.addView(ui.button("화면 설정","설정",Color.WHITE,onSettings),LayoutParams(ui.dp(48),ui.dp(48)))
        body.addView(bar)
        val heading=LinearLayout(context).apply {orientation=if(expanded)HORIZONTAL else VERTICAL;gravity=Gravity.CENTER_VERTICAL;setPadding(ui.dp(20),ui.dp(16),ui.dp(20),ui.dp(8))}
        heading.addView(ui.label("최근 문서",22f,true),LayoutParams(if(expanded)0 else -1,ui.dp(40),if(expanded)1f else 0f))
        val search=EditText(context).apply {
            setText(query);hint="문서 이름 검색";contentDescription="문서 이름 검색"
            textSize=13f;setSingleLine();setTextColor(ui.text);setHintTextColor(ui.muted);setPadding(ui.dp(14),0,ui.dp(14),0)
            background=resources.getDrawable(R.drawable.ui_input_background,context.theme)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_ui_search,0,0,0);compoundDrawablePadding=ui.dp(8)
            addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,c:Int,n:Int)=Unit;override fun afterTextChanged(s:Editable?)=Unit
                override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){query=s.toString();applyFilter()}})
        }
        heading.addView(search,LayoutParams(if(expanded)ui.dp(280) else -1,ui.dp(48)))
        body.addView(heading)
        val tools=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(ui.dp(12),0,ui.dp(12),0)}
        val scroll=HorizontalScrollView(context).apply {isHorizontalScrollBarEnabled=false}
        val filters=LinearLayout(context)
        for(label in listOf("전체","PDF","한글","Word","PPT","Excel"))filters.addView(ui.button(label){filter=label;applyFilter()}.also{filterButtons.add(it)},LayoutParams(-2,ui.dp(48)))
        scroll.addView(filters);tools.addView(scroll,LayoutParams(0,ui.dp(48),1f))
        count=ui.label("",11f,color=ui.muted)
        if(expanded)tools.addView(count,LayoutParams(-2,-2).apply {rightMargin=ui.dp(12)})
        tools.addView(ui.button(if(rows)"격자 보기로 전환"else"목록 보기로 전환",if(rows)"격자"else"행 목록"){
            rows=!rows;build(expanded)
        },LayoutParams(ui.dp(48),ui.dp(48)))
        body.addView(tools)
        (statusArea.parent as? ViewGroup)?.removeView(statusArea);body.addView(statusArea)
        val holder=FrameLayout(context)
        grid=GridView(context).apply {
            numColumns=if(rows)1 else GridView.AUTO_FIT;columnWidth=ui.dp(if(expanded)180 else 136);stretchMode=GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing=ui.dp(16);verticalSpacing=ui.dp(16)
            setPadding(ui.dp(20),ui.dp(12),ui.dp(20),ui.dp(16));adapter=this@DocumentLibraryView.adapter
            setOnItemClickListener { _,_,position,_->shown[position].open() }
        }
        empty=ui.label("",14f,color=ui.muted).apply {gravity=Gravity.CENTER;setPadding(ui.dp(24),ui.dp(24),ui.dp(24),ui.dp(24))}
        holder.addView(grid,FrameLayout.LayoutParams(-1,-1));holder.addView(empty,FrameLayout.LayoutParams(-1,-1));grid.emptyView=empty
        body.addView(holder,LayoutParams(-1,0,1f))
        applyFilter();grid.setSelection(scrollPosition)
    }
    private fun applyFilter() {
        if(!::grid.isInitialized)return
        shown=documents.filter { it.name.contains(query,true) && when(filter){"PDF"->it.name.endsWith(".pdf",true);"한글"->it.name.endsWith(".hwp",true)||it.name.endsWith(".hwpx",true)
            "Word"->it.name.substringAfterLast('.').lowercase() in listOf("doc","docx")
            "PPT"->it.name.substringAfterLast('.').lowercase() in listOf("ppt","pptx")
            "Excel"->it.name.substringAfterLast('.').lowercase() in listOf("xls","xlsx","csv");else->true} }
        filterButtons.forEach {it.isSelected=it.text.toString()==filter}
        count.text="${shown.size}개 문서"
        empty.text=if(documents.isEmpty())"최근에 연 문서가 없습니다\n\n+ 버튼을 눌러 문서를 열어 보세요" else "조건에 맞는 문서가 없습니다"
        adapter.notifyDataSetChanged()
    }
    private inner class Cards:BaseAdapter(){
        override fun getCount()=shown.size
        override fun getItem(position:Int)=shown[position]
        override fun getItemId(position:Int)=position.toLong()
        override fun getView(position:Int,recycled:View?,parent:ViewGroup):View{
            val item=shown[position]
            val short=resources.configuration.screenHeightDp<480
            val card=LinearLayout(context).apply {orientation=if(rows)HORIZONTAL else VERTICAL;gravity=Gravity.CENTER_VERTICAL;layoutParams=AbsListView.LayoutParams(-1,ui.dp(if(rows)84 else if(short)148 else 270));setOnClickListener{item.open()} }
            val cover=FrameLayout(context).apply {background=ui.rounded(if(ui.dark)Color.rgb(47,59,75)else Color.rgb(234,238,243))}
            val icon=ui.label(item.name.substringAfterLast('.').uppercase().take(4),if(rows)10f else 20f,true,ui.muted).apply{gravity=Gravity.CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
            cover.addView(icon,FrameLayout.LayoutParams(-1,-1))
            val preview=ImageView(context).apply {scaleType=ImageView.ScaleType.FIT_CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO;setPadding(ui.dp(12),ui.dp(12),ui.dp(12),ui.dp(12))}
            cover.addView(preview,FrameLayout.LayoutParams(-1,-1))
            if(item.name.substringAfterLast('.').lowercase() in listOf("pdf","hwp","hwpx","doc","docx","ppt","pptx"))DocumentPreviewCache.bind(context,item.uri,preview)
            card.addView(cover,LayoutParams(if(rows)ui.dp(52) else -1,ui.dp(if(rows)68 else if(short)72 else 190)))
            val info=LinearLayout(context).apply {orientation=VERTICAL;setPadding(if(rows)ui.dp(12)else 0,ui.dp(if(rows)0 else 6),0,0)}
            val line=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
            line.addView(ui.label(item.name,14f,true).apply {
                maxLines=if(rows)1 else 2;ellipsize=TextUtils.TruncateAt.END;tooltipText=item.name
                setOnClickListener { item.open() }
            },LayoutParams(0,ui.dp(48),1f))
            line.addView(ui.button(item.manageLabel,"더보기"){item.manage()},LayoutParams(ui.dp(48),ui.dp(48)))
            info.addView(line)
            info.addView(ui.label(item.detail,12f,color=ui.muted).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END})
            card.addView(info,if(rows)LayoutParams(0,-2,1f) else LayoutParams(-1,-2))
            return card
        }
    }
}
