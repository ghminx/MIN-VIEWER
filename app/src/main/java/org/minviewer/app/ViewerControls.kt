package org.minviewer.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.TextUtils
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ProgressBar

class ViewerControls(
    private val activity: Activity,
    title: String,
    onBack: () -> Unit,
    private val onSearch: (() -> Unit)?,
    onList: () -> Unit,
    private val menuItems: List<Pair<String, () -> Unit>>,
    private val selectedViewMode: () -> String? = { null },
    private val enabledMenuItem: (String) -> Boolean = { true },
    private val onFit: (() -> Unit)? = null
) : LinearLayout(activity) {
    companion object {
        fun pageTapDirection(x: Float, width: Int) = when {
            x < width * .24f -> -1
            x > width * .76f -> 1
            else -> 0
        }
    }
    private val ui = UiStyle(activity)
    val status: TextView
    val listButton: TextView
    private val titleView: TextView
    private val titleArea=LinearLayout(activity).apply {orientation=VERTICAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=ui.dp(48)}
    private val top = LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL;setBackgroundColor(ui.navy) }
    private val notice: TextView
    private val noticeArea=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL;setBackgroundColor(ui.surface);visibility=View.GONE;setPadding(ui.dp(12),ui.dp(4),ui.dp(8),ui.dp(4))}
    private val progress=ProgressBar(activity).apply {visibility=View.GONE;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
    private val noticeAction=ui.button("문서 닫기"){onBack()}.apply {visibility=View.GONE}
    private var documentNotice = ""
    private var readingAction: TextView? = null
    private var inkToolsAction: TextView? = null
    private val searchBar = LinearLayout(activity).apply {orientation=VERTICAL;visibility=View.GONE;setBackgroundColor(ui.surface)}
    private val searchInput = ui.searchInput().apply {contentDescription="문서 검색어";imeOptions=EditorInfo.IME_ACTION_SEARCH}
    private val searchResult = ui.label("검색어를 입력해 주세요",12f,color=ui.muted)
    private val nextResult = ui.button("다음 검색 결과","다음") {}.apply {visibility=GONE;isEnabled=false}
    private val submit = ui.button("찾기","검색") {}
    private var searchActive = false
    private var openNavigation: () -> Unit = {}
    private val pageInput=ui.searchInput().apply {contentDescription="페이지 번호";hint="페이지";inputType=android.text.InputType.TYPE_CLASS_NUMBER;setCompoundDrawables(null,null,null,null)}
    private val pageGo=ui.button("페이지로 이동") {}.apply {text="이동";setPadding(ui.dp(8),0,ui.dp(8),0)}
    private var closeSearchAction: () -> Unit = {}
    private var submitSearchAction: (String) -> Unit = {}
    val searchIsOpen get() = searchActive

    init {
        orientation=VERTICAL
        top.addView(action("뒤로",onBack))
        titleView=ui.label(title,14f,true,Color.WHITE).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END}
        status=ui.label("불러오는 중",12f,color=Color.WHITE).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END}
        titleArea.addView(titleView);titleArea.addView(status)
        top.addView(titleArea,LayoutParams(0,-2,1f))
        listButton=action("목록",onList).apply {text="";setCompoundDrawablesRelative(ui.icon("목록",Color.WHITE),null,null,null)}
        top.addView(listButton)
        if(onSearch!=null)top.addView(action("검색",onSearch))
        if(menuItems.isNotEmpty())top.addView(action("더보기") {showMenu(top.getChildAt(top.childCount-1))})
        addView(top)
        val inputRow=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(ui.dp(8),ui.dp(4),ui.dp(4),0)}
        searchInput.minimumHeight=ui.dp(48)
        inputRow.addView(searchInput,LayoutParams(0,-2,1f))
        inputRow.addView(submit,LayoutParams(LayoutParams.WRAP_CONTENT,ui.dp(48)))
        searchBar.addView(inputRow)
        val resultRow=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(ui.dp(16),0,ui.dp(4),ui.dp(4))}
        searchResult.maxLines=1;searchResult.ellipsize=TextUtils.TruncateAt.END
        resultRow.addView(searchResult,LayoutParams(0,ui.dp(48),1f))
        resultRow.addView(nextResult,LayoutParams(ui.dp(48),ui.dp(48)))
        searchBar.addView(resultRow)
        submit.setOnClickListener {submitSearch()}
        searchInput.setOnEditorActionListener {_,action,_->if(action==EditorInfo.IME_ACTION_SEARCH){submitSearch();true}else false}
        notice=ui.label("",14f,color=ui.danger).apply {setPadding(ui.dp(8),ui.dp(10),ui.dp(8),ui.dp(10))}
        noticeArea.addView(progress,LayoutParams(ui.dp(24),ui.dp(24)))
        noticeArea.addView(notice,LayoutParams(0,-2,1f));noticeArea.addView(noticeAction)
        val pageRow=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(ui.dp(8),0,ui.dp(4),ui.dp(4))}
        pageRow.addView(pageInput,LayoutParams(0,ui.dp(44),1f))
        pageRow.addView(pageGo,LayoutParams(LayoutParams.WRAP_CONTENT,ui.dp(44)))
        searchBar.addView(pageRow)
        addView(noticeArea)
        status.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun afterTextChanged(s:Editable?)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                val message=s?.toString().orEmpty()
                val failed=message.contains("오류")||message.contains("실패")||message.contains("없습니다")||message.contains("권한")
                val busy=message.contains("불러오는")||message.contains("열기 지연")
                notice.text=if(failed||busy)message else documentNotice
                notice.setTextColor(if(failed)ui.danger else ui.text)
                progress.visibility=if(busy&&!failed)View.VISIBLE else View.GONE
                noticeAction.visibility=if(failed||busy)View.VISIBLE else View.GONE
                noticeAction.text=if(busy&&!failed)"취소"else"닫기"
                noticeArea.visibility=if(failed||busy||documentNotice.isNotEmpty())View.VISIBLE else View.GONE
            }
        })
        status.text="불러오는 중"
    }

    fun showSearch(query:String,context:String="문서 검색",next:(()->Unit)?=null,closed:()->Unit={},submit:(String)->Unit) {
        openNavigation()
        if(searchIsOpen){searchInput.requestFocus();return}
        searchActive=true
        val typed=searchInput.text.toString()
        searchInput.inputType=android.text.InputType.TYPE_CLASS_TEXT
        searchInput.contentDescription="문서 검색어";searchInput.hint="검색어 입력"
        this.submit.text="찾기";this.submit.contentDescription="찾기"
        submitSearchAction=submit;closeSearchAction=closed
        nextResult.visibility=if(next==null)View.GONE else View.VISIBLE;nextResult.isEnabled=false
        nextResult.setOnClickListener {next?.invoke()}
        searchInput.setText(typed.ifBlank {query});searchInput.setSelection(searchInput.text.length)
        searchResult.setTextColor(ui.muted)
        searchResult.text="$context · 검색어를 입력해 주세요";searchBar.visibility=View.VISIBLE
        searchInput.requestFocus()
        searchInput.post {(activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(searchInput,InputMethodManager.SHOW_IMPLICIT)}
    }

    private fun submitSearch() {
        if(!searchActive)onSearch?.invoke()
        val query=searchInput.text.toString()
        if(query.isBlank()){searchInput.error="검색어를 입력해 주세요";return}
        (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchInput.windowToken,0)
        setSearchResult("검색 중")
        submitSearchAction(query)
    }

    fun setSearchResult(message:String,canNext:Boolean=false) {
        searchResult.text=message;nextResult.isEnabled=canNext
    }

    fun closeSearch() {
        if(!searchIsOpen)return
        searchActive=false
        (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchInput.windowToken,0)
        searchInput.clearFocus();closeSearchAction()
    }

    fun setTitle(title:String) {titleView.text=title}

    fun setReadingAction(editing:Boolean,enabled:Boolean,toggle:()->Unit) {
        val button=readingAction?:action("읽기",toggle).apply {
            setSingleLine();setPadding(ui.dp(4),0,ui.dp(4),0)
        }.also {top.addView(it,2,LayoutParams(ui.dp(48),ui.dp(48)));readingAction=it}
        button.text=if(editing)"필기"else"읽기";button.contentDescription=if(editing)"읽기 모드로 전환"else"필기 시작"
        button.setTextColor(if(editing)ui.navy else Color.WHITE)
        button.background=RippleDrawable(ColorStateList.valueOf(0x307f9fc9),ui.rounded(if(editing)Color.WHITE else Color.TRANSPARENT,20,if(editing)null else activity.getColor(R.color.ui_accent_dark)),null)
        button.isSelected=editing;button.isEnabled=enabled;button.alpha=if(enabled)1f else .5f;button.setOnClickListener {toggle()}
    }

    fun setDocumentNotice(message:String) {documentNotice=message;notice.text=message;notice.setTextColor(ui.text);noticeArea.visibility=if(message.isEmpty())View.GONE else View.VISIBLE;progress.visibility=View.GONE;noticeAction.visibility=View.GONE}

    fun setInkToolsAction(visible:Boolean,collapsed:Boolean,toggle:()->Unit) {
        if(!visible&&inkToolsAction==null)return
        val button=inkToolsAction?:action("펜",toggle).also {top.addView(it,top.indexOfChild(listButton));inkToolsAction=it}
        button.visibility=if(visible)VISIBLE else GONE
        button.contentDescription=if(collapsed)"필기 도구 펼치기"else"필기 도구 접기"
        button.setCompoundDrawablesRelative(ui.icon(if(collapsed)"도구 펼치기"else"도구 접기",Color.WHITE),null,null,null)
        button.isSelected=!collapsed;button.setOnClickListener {toggle()}
    }

    fun setLocationAction(label:String="페이지로 이동",action:()->Unit) {
        titleArea.contentDescription="현재 위치, $label"
        titleArea.setOnClickListener {action()}
        titleArea.background=RippleDrawable(ColorStateList.valueOf(0x307f9fc9),null,null)
    }

    fun setListNavigation(open:()->Unit) {openNavigation=open}

    fun attachListNavigation(host:LinearLayout,current:Int,count:Int,go:(Int)->Unit) {
        (searchBar.parent as? android.view.ViewGroup)?.removeView(searchBar)
        host.addView(searchBar,0,LayoutParams(-1,-2))
        searchBar.visibility=VISIBLE
        pageInput.setText((current+1).toString());pageInput.hint="1–${count}쪽"
        pageGo.setOnClickListener {
            val page=pageInput.text.toString().toIntOrNull()
            if(page==null||page !in 1..count){searchResult.text="1–${count}쪽 사이의 번호를 입력해 주세요";searchResult.setTextColor(ui.danger)}
            else {
                (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(pageInput.windowToken,0)
                go(page-1);searchResult.setTextColor(ui.muted);searchResult.text="${page} / ${count}쪽"
            }
        }
        pageInput.setOnEditorActionListener {_,action,_->if(action==EditorInfo.IME_ACTION_SEARCH){pageGo.performClick();true}else false}
    }

    fun showPageJump(current:Int,count:Int) {
        if(count<=0)return
        openNavigation()
        pageInput.setText((current+1).toString());pageInput.selectAll();pageInput.requestFocus()
        pageInput.post {(activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(pageInput,InputMethodManager.SHOW_IMPLICIT)}
    }

    private fun showMenu(anchor:View) {
        val views=listOf("한쪽 보기","두쪽 보기","연속 보기","전체 페이지 보기")
        fun section(name:String)=when {name in views||name in listOf("화면 맞춤","문자 인코딩")->"보기";name.contains("필기")||name=="읽기 모드"||name.contains("백업")->"필기·사본";else->"탐색"}
        val actions=menuItems+
            listOfNotNull(onFit?.let {"화면 맞춤" to it})
        val body=LinearLayout(activity).apply {orientation=VERTICAL;setPadding(ui.dp(6),ui.dp(6),ui.dp(6),ui.dp(6))}
        val scroll=ScrollView(activity).apply {addView(body)}
        val visible=Rect();anchor.getWindowVisibleDisplayFrame(visible)
        val width=minOf(ui.dp(300),visible.width()-ui.dp(24))
        val popup=PopupWindow(scroll,width,LayoutParams.WRAP_CONTENT,true).apply {
            setBackgroundDrawable(activity.getDrawable(R.drawable.ui_popup_surface));elevation=ui.dp(8).toFloat();isOutsideTouchable=true
            inputMethodMode=PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        for(group in listOf("보기","필기·사본")) {
            val items=actions.distinctBy {it.first}.filter {section(it.first)==group}.sortedBy {if(it.first in views)views.indexOf(it.first)else views.size}
            if(items.isEmpty())continue
            if(body.childCount>0)body.addView(View(activity).apply {setBackgroundColor(ui.line)},LayoutParams(-1,ui.dp(1)).apply {setMargins(ui.dp(10),ui.dp(6),ui.dp(10),ui.dp(6))})
            body.addView(ui.label(group,12f,color=ui.muted).apply {setPadding(ui.dp(12),ui.dp(6),0,ui.dp(4))})
            for((name,call) in items) {
                val selected=name==selectedViewMode()
                val kind=when {name in views->"격자";name.contains("내보내기")->"내보내기";name.contains("백업")->"백업";name.contains("필기")->"펜";name.contains("검색")->"검색";else->"목록"}
                body.addView(ui.button(name){popup.dismiss();call()}.apply {
                    gravity=Gravity.CENTER_VERTICAL or Gravity.START;textSize=14f
                    minHeight=ui.dp(48);setPadding(ui.dp(12),ui.dp(8),ui.dp(12),ui.dp(8))
                    setCompoundDrawablesRelative(ui.icon(kind),null,if(selected)ui.icon("선택")else null,null);compoundDrawablePadding=ui.dp(10)
                    isSelected=selected;isEnabled=enabledMenuItem(name)
                    alpha=if(isEnabled)1f else .4f
                    contentDescription=name+if(selected)", 선택됨"else""
                },LayoutParams(-1,-2))
            }
        }
        scroll.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
        popup.height=minOf(scroll.measuredHeight,visible.height()-ui.dp(16))
        popup.showAsDropDown(anchor,anchor.width-width,0)
    }

    private fun action(name:String,call:()->Unit)=ui.label(if(name in listOf("목록","읽기"))name else "",13f,true,Color.WHITE).apply {
        contentDescription=name;gravity=Gravity.CENTER;minimumWidth=ui.dp(48);minimumHeight=ui.dp(48)
        setPadding(ui.dp(12),0,ui.dp(12),0)
        if(text.isEmpty())setCompoundDrawablesRelative(ui.icon(name,Color.WHITE),null,null,null)
        background=RippleDrawable(ColorStateList.valueOf(0x307f9fc9),null,null)
        setOnClickListener {call()}
    }
}
