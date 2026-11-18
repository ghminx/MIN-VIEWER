package org.minviewer.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.text.Editable
import android.text.TextWatcher
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan

/** 원문과 결과를 분리하는 로컬 AI 패널. 세션은 문서 수명에만 보관. */
class DocumentAi(private val activity: Activity, controls: ViewerControls, private val document: View,
    private val load: ((Result<AiSource>) -> Unit) -> Unit, private val go: (Int) -> Unit,
    private val beforeOpen: () -> Unit, retained: Any? = null, private val viewportChanging: () -> Unit = {}) {
    companion object { const val enabled = true }
    private val ui = UiStyle(activity)
    private var session = retained as? AiSession ?: AiSession(NanoAiEngine())
    private var alive = true
    private var sourceGeneration = 0
    private var loading = false
    private var expanded: Boolean
        get()=session.expanded
        set(value){session.expanded=value}
    private var panelTouch = false
    private var wasOpen = false
    private var selectedSource: AiSource?
        get()=session.selectedSource
        set(value){session.selectedSource=value}
    private var question: String
        get()=session.draft
        set(value){session.draft=value}
    private val panel = LinearLayout(activity).apply {
        orientation=LinearLayout.VERTICAL;contentDescription="AI 패널";setBackgroundColor(ui.surface)
        elevation=ui.dp(3).toFloat()
    }
    val view: LinearLayout = object: LinearLayout(activity) {
        override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {super.onSizeChanged(w,h,oldw,oldh);layoutPanel()}
    }.apply {
        document.contentDescription="문서 영역"
        addView(document,LinearLayout.LayoutParams(-1,0,1f));addView(panel)
    }
    private val entry = controls.addAiAction {
        if(session.state.open)hide() else {
            beforeOpen()
            if(session.state.source == null)loadCurrent()else session.reopen()
        }
    }

    init {bind();render()}
    private fun bind() {
        session.observer={activity.runOnUiThread {if(alive)render()}}
    }
    private fun loadCurrent() {
        beforeOpen()
        val token=++sourceGeneration
        loading=true;session.reopen()
        load { outcome -> activity.runOnUiThread {
            if(!alive||token!=sourceGeneration)return@runOnUiThread
            loading=false
            outcome.onSuccess {session.show(it,session.state.task)}.onFailure {session.fail(it.message ?: "원문을 읽을 수 없습니다.")}
        } }
    }
    fun selected(text:String,page:Int,task:String) {
        sourceGeneration++;loading=false;beforeOpen()
        selectedSource=AiSource(page,text,true)
        session.show(selectedSource!!,when(task){"translate"->AiTask.TRANSLATE;"summary"->AiTask.SUMMARY;else->AiTask.EXPLAIN})
    }
    fun hide():Boolean {
        if(!session.state.open)return false
        sourceGeneration++;loading=false;session.hide()
        (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken,0)
        return true
    }
    fun ownsTouch(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            val r=Rect();panelTouch=panel.visibility==View.VISIBLE&&panel.getGlobalVisibleRect(r)&&r.contains(event.rawX.toInt(),event.rawY.toInt())
        }
        return panelTouch
    }
    fun retain():Any = session
    fun close(keep:Boolean) {
        alive=false;sourceGeneration++;session.observer=null
        if(!keep)session.close()
    }
    fun reset() {
        sourceGeneration++;loading=false;selectedSource=null;question=""
        session.observer=null;session.close();session=AiSession(NanoAiEngine());bind();render()
    }

    private fun layoutPanel() {
        val width=if(view.width>0)view.width else activity.resources.displayMetrics.widthPixels
        val height=if(view.height>0)view.height else activity.resources.displayMetrics.heightPixels-ui.dp(100)
        val w=width/activity.resources.displayMetrics.density;val h=height/activity.resources.displayMetrics.density
        val side=w>=720 || (w>=600&&h<480)
        val full=session.state.open&&((expanded&&h<480)||(!side&&h<360))
        view.orientation=if(side)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        document.visibility=if(full)View.GONE else View.VISIBLE
        val docParams=if(side)LinearLayout.LayoutParams(0,-1,1f)else LinearLayout.LayoutParams(-1,0,1f)
        if(document.layoutParams.width!=docParams.width||document.layoutParams.height!=docParams.height)document.layoutParams=docParams
        val panelWidth=if(full||!side)-1 else minOf(ui.dp(if(expanded)520 else 380),(width*.47f).toInt())
        val panelHeight=if(side||full)-1 else (height*(if(expanded).86f else if(session.state.result.isNotEmpty()).76f else .64f)).toInt()
        if(panel.layoutParams.width!=panelWidth||panel.layoutParams.height!=panelHeight){viewportChanging();panel.layoutParams=LinearLayout.LayoutParams(panelWidth,panelHeight)}
    }

    private fun render() {
        val state=session.state
        if(state.open)activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(wasOpen!=state.open)viewportChanging()
        panel.removeAllViews()
        panel.visibility=if(state.open)View.VISIBLE else View.GONE
        entry?.isSelected=state.open
        entry?.background=ui.rounded(if(state.open)0xff365a88.toInt()else Color.TRANSPARENT,10)
        layoutPanel()
        if(!state.open){wasOpen=false;return}
        val header=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(ui.dp(16),0,ui.dp(4),0)}
        header.addView(ui.label("AI 도우미",16f,true).apply {gravity=Gravity.CENTER_VERTICAL},LinearLayout.LayoutParams(0,ui.dp(48),1f))
        header.addView(ui.button(if(expanded)"AI 패널 줄이기"else"AI 패널 크게 보기","화면 맞춤") {expanded=!expanded;render()},LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)))
        header.addView(ui.button("AI 패널 닫기","닫기"){hide()},LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)))
        panel.addView(header)
        val scopeRow=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(ui.dp(12),0,ui.dp(12),0)}
        scopeRow.addView(ui.button((if(state.source?.selected==true)"선택한 글"else"현재 페이지")+(state.source?.let {" · ${it.page+1}쪽"}?:"")) {
            val options=if(selectedSource==null)arrayOf("현재 페이지")else arrayOf("현재 페이지","선택한 글")
            AlertDialog.Builder(activity).setTitle("처리할 범위").setItems(options) {_,index->
                if(index==0)loadCurrent()else session.show(selectedSource!!,state.task)
            }.show()
        }.apply {isEnabled=!state.busy&&!loading;background=ui.rounded(ui.background,10,ui.line)},LinearLayout.LayoutParams(0,ui.dp(48),1f))
        val short=view.height>0&&view.height<ui.dp(420)
        if(short)scopeRow.addView(ui.button(state.task.label+" ▾") {
            val tasks=listOf(AiTask.SUMMARY,AiTask.EXPLAIN,AiTask.TRANSLATE)
            AlertDialog.Builder(activity).setTitle("AI 작업").setItems(tasks.map{it.label}.toTypedArray()){_,index->session.choose(tasks[index])}.show()
        }.apply {isEnabled=!state.busy})
        else scopeRow.addView(ui.label("기기 내 AI",11f,color=ui.muted).apply {setPadding(ui.dp(10),0,0,0)})
        panel.addView(scopeRow)
        val tabs=LinearLayout(activity).apply {setPadding(ui.dp(12),ui.dp(8),ui.dp(12),ui.dp(8))}
        listOf(AiTask.SUMMARY,AiTask.EXPLAIN,AiTask.TRANSLATE).forEach {task->
            tabs.addView(ui.button(task.label) {session.choose(task)}.apply {
                isSelected=task==state.task;isEnabled=!state.busy&&!loading
                background=ui.rounded(if(isSelected)ui.selected else ui.background,10)
                setTextColor(if(isSelected&&!ui.dark)ui.navy else ui.text)
            },LinearLayout.LayoutParams(0,ui.dp(48),1f).apply {marginEnd=ui.dp(4)})
        }
        if(!short)panel.addView(tabs)
        val body=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(ui.dp(16),ui.dp(10),ui.dp(16),ui.dp(16))}
        val scroll=ScrollView(activity).apply {contentDescription="AI 결과 스크롤";isFillViewport=false;addView(body)}
        panel.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        fun line(text:String,size:Float=14f,bold:Boolean=false) {body.addView(ui.label(text,size,bold).apply {setPadding(0,0,0,ui.dp(12));setLineSpacing(ui.dp(3).toFloat(),1f)})}
        if(loading)line("원문을 준비하고 있습니다…")
        else {
            state.source?.let {source->
                if(state.result.isEmpty())body.addView(ui.label("텍스트 원문 미리보기 · ${source.page+1}쪽 · 총 ${source.text.length}자\n"+source.text.take(180),12f,color=ui.muted).apply {
                    id=R.id.ai_source
                    setPadding(ui.dp(12),ui.dp(10),ui.dp(12),ui.dp(10));background=ui.rounded(ui.background,8)
                },LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=ui.dp(14)})
            }
            if(state.task==AiTask.TRANSLATE)body.addView(ui.button("번역 언어 · ${state.language}") {
                val languages=arrayOf("영어","한국어","일본어")
                AlertDialog.Builder(activity).setTitle("번역 언어").setItems(languages){_,index->session.choose(AiTask.TRANSLATE,languages[index])}.show()
            }.apply {isEnabled=!state.busy},LinearLayout.LayoutParams(-1,ui.dp(48)))
            if(state.busy) {
                line(if(state.preparing)"AI 모델을 준비하는 중…"else"기기에서 ${state.task.label}하는 중…",16f,true)
                body.addView(ui.button("요청 취소"){session.cancel()})
            } else if(state.error.isNotEmpty()) {
                line(state.error)
                if(state.preparationRequired)body.addView(ui.button("AI 모델 준비") {
                    AlertDialog.Builder(activity).setTitle("기기 내 AI 모델 준비")
                        .setMessage("Google AICore가 필요한 모델을 다운로드합니다. 네트워크와 저장 공간을 사용하며 문서 처리는 자동 실행하지 않습니다.")
                        .setNegativeButton("취소",null).setPositiveButton("준비하기"){_,_->session.prepare()}.show()
                })
                else if(state.source!=null)body.addView(ui.button("다시 요청하기"){session.run()})
                else body.addView(ui.button("원문 다시 읽기"){loadCurrent()})
            } else if(state.result.isNotEmpty()) {
                val result=AiResultText.format(state.result,literal=state.task==AiTask.TRANSLATE)
                val title=when(state.task){AiTask.SUMMARY->"핵심 요약";AiTask.EXPLAIN->"쉽게 풀어보기";AiTask.TRANSLATE->"${state.language} 번역";AiTask.QUESTION->"질문에 대한 답변"}
                body.addView(ui.label(title,21f,true).apply {setPadding(0,ui.dp(8),0,ui.dp(8))})
                body.addView(ui.label((if(state.source!!.selected)"선택한 글"else"현재 페이지")+" · ${state.source.page+1}쪽",12f,color=ui.muted).apply {setPadding(0,0,0,ui.dp(24))})
                val styled=SpannableString(result.text)
                val semibold=activity.resources.getFont(R.font.pretendard_semibold)
                result.headings.forEach {range->
                    styled.setSpan(TypefaceSpan(semibold),range.first,range.last+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    styled.setSpan(RelativeSizeSpan(1.15f),range.first,range.last+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                result.emphasis.forEach {range->styled.setSpan(TypefaceSpan(semibold),range.first,range.last+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
                result.lists.forEach {range->styled.setSpan(LeadingMarginSpan.Standard(0,ui.dp(18)),range.first,range.last+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
                body.addView(ui.label("",16f).apply {
                    id=R.id.ai_result;text=styled;includeFontPadding=false
                    setTextIsSelectable(true);setLineSpacing(0f,1.5f)
                    setPadding(0,0,0,ui.dp(24))
                })
                val resultActions=LinearLayout(activity).apply {setPadding(0,ui.dp(8),0,ui.dp(16))}
                resultActions.addView(ui.button("결과 복사") {
                    (activity.getSystemService(Activity.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("AI 결과",result.text))
                    Toast.makeText(activity,"복사했습니다",Toast.LENGTH_SHORT).show()
                },LinearLayout.LayoutParams(0,ui.dp(48),1f).apply {marginEnd=ui.dp(8)})
                resultActions.addView(ui.button("원문 ${state.source.page+1}쪽 보기"){hide();go(state.source.page)},LinearLayout.LayoutParams(0,ui.dp(48),1f))
                body.addView(resultActions)
                body.addView(ui.label("AI가 생성한 내용으로, 원문과 다를 수 있어요.",12f,color=ui.muted).apply {setLineSpacing(0f,1.5f);setPadding(0,0,0,ui.dp(16))})
            } else if(state.source!=null) {
                line(if(state.task==AiTask.SUMMARY)"핵심 내용을 짧게 정리해 드려요."else if(state.task==AiTask.EXPLAIN)"원문의 뜻과 주요 용어를 쉽게 풀어드려요."else"원문을 유지하고 번역 결과를 표시해요.")
                body.addView(ui.button("${state.task.label}하기"){session.run()}.apply {setTextColor(Color.WHITE);background=ui.rounded(ui.navy,12)},LinearLayout.LayoutParams(-1,ui.dp(48)))
            }
        }
        if(state.result.isNotEmpty()&&!state.busy) {
            val row=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(ui.dp(12),ui.dp(4),ui.dp(4),ui.dp(6))}
            val input=EditText(activity).apply {
                contentDescription="추가 질문";hint="이 내용에 이어서 질문";setSingleLine();setText(question);textSize=13f;setTextColor(ui.text);setHintTextColor(ui.muted)
                typeface=activity.resources.getFont(R.font.pretendard_regular);imeOptions=EditorInfo.IME_ACTION_SEND
                addTextChangedListener(object:TextWatcher {
                    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
                    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){question=s.toString()}
                    override fun afterTextChanged(s:Editable?)=Unit
                })
            }
            fun submit() {if(question.isNotBlank()){val typed=question;question="";(activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0);session.run(typed)}}
            input.setOnEditorActionListener {_,action,_->if(action==EditorInfo.IME_ACTION_SEND){submit();true}else false}
            row.addView(input,LinearLayout.LayoutParams(0,ui.dp(48),1f));row.addView(ui.button("질문 보내기","다음"){submit()},LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)))
            panel.addView(row)
        }
        if(!wasOpen&&android.animation.ValueAnimator.areAnimatorsEnabled())panel.post {
            if(alive&&session.state.open){panel.translationY=ui.dp(18).toFloat();panel.alpha=.4f;panel.animate().translationY(0f).alpha(1f).setDuration(220).start()}
        }
        wasOpen=true
    }
}
