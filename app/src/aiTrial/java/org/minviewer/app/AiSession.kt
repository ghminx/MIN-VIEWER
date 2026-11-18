package org.minviewer.app

enum class AiTask(val label: String) { SUMMARY("요약"), EXPLAIN("설명"), TRANSLATE("번역"), QUESTION("질문") }
data class AiRequest(val source: AiSource, val task: AiTask, val language: String = "영어", val question: String = "", val previousAnswer: String = "")
interface AiEngine {
    fun start(request: AiRequest, receive: (Result<String>) -> Unit): () -> Unit
    fun close()
    fun prepare(receive: (Result<Unit>) -> Unit): () -> Unit
}
class AiPreparationRequired : java.io.IOException("AI 모델 준비가 필요합니다. 필요한 모델을 준비한 뒤 다시 실행해 주세요.")
data class AiState(val open: Boolean = false, val source: AiSource? = null, val task: AiTask = AiTask.SUMMARY,
    val language: String = "영어", val busy: Boolean = false, val result: String = "", val error: String = "",
    val preparationRequired: Boolean = false, val preparing: Boolean = false,
    val requestTask:AiTask? = null, val retryReady:Boolean=false, val loadingSource:Boolean=false)

/** 문서 수명의 AI 요청·응답 상태. Android 화면은 observer를 통해 갱신. */
class AiSession(private val engine: AiEngine) {
    @Volatile var state = AiState(); private set
    var observer: (() -> Unit)? = null
    var selectedSource: AiSource? = null
    var draft = ""
    var expanded = false
    private var cancelRequest: (() -> Unit)? = null
    private var retryRequest:AiRequest? = null
    private var generation = 0
    @Synchronized fun loadSource(load:((Result<AiSource>)->Unit)->Unit) {
        cancel()
        val token=++generation
        state=AiState(open=true,task=state.task,language=state.language,loadingSource=true)
        observer?.invoke()
        load {outcome->synchronized(this){
            if(token==generation) {
                state=outcome.fold({state.copy(source=it,loadingSource=false)},
                    {state.copy(error=it.message?:"원문을 읽을 수 없습니다.",loadingSource=false)})
                observer?.invoke()
            }
        }}
    }
    @Synchronized fun detach() {
        observer=null
        if(state.loadingSource){generation++;state=state.copy(loadingSource=false)}
    }
    @Synchronized
    fun show(source: AiSource, task: AiTask = AiTask.SUMMARY) {
        cancel()
        state = AiState(open = true, source = source, task = task, language = state.language)
        if(source.selected)selectedSource=source
        observer?.invoke()
    }
    @Synchronized fun choose(task: AiTask, language: String = state.language) {
        cancel()
        state = state.copy(task = task, language = language, result = "", error = "",preparationRequired=false,requestTask=null)
        observer?.invoke()
    }
    @Synchronized fun run(question: String = "") {
        if(state.busy||state.loadingSource)return
        val source = requireNotNull(state.source)
        if(source.text.isBlank()) { fail("추출할 텍스트가 없습니다. 이미지 문서는 아직 지원하지 않습니다."); return }
        val request = AiRequest(source, if(question.isBlank())state.task else AiTask.QUESTION, state.language, question, state.result)
        execute(request)
    }
    @Synchronized fun retry() {
        if(state.busy)return
        val request=retryRequest
        if(request==null)run()else execute(request)
    }
    private fun execute(request:AiRequest) {
        val token=++generation
        retryRequest=request
        state = state.copy(busy = true, error = "", result = "",requestTask=request.task,retryReady=false)
        observer?.invoke()
        cancelRequest = engine.start(request) { outcome ->
            synchronized(this) {
                if (token == generation) {
                    state = completed(outcome)
                    if(state.error.isEmpty())retryRequest=null
                    observer?.invoke()
                }
            }
        }
    }
    private fun completed(outcome:Result<String>):AiState = outcome.fold({raw->
        if(raw.isBlank())state.copy(busy=false,error="AI가 결과를 반환하지 않았습니다. 다시 요청해 주세요.")
        else state.copy(busy=false,result=raw)
    },{error->state.copy(busy=false,error=error.message?:"AI 요청 실패",preparationRequired=error is AiPreparationRequired)})
    @Synchronized fun prepare() {
        val request=retryRequest?:AiRequest(requireNotNull(state.source),state.task,state.language)
        cancel()
        retryRequest=request
        val token=++generation
        state=state.copy(busy=true,preparing=true,error="",result="",requestTask=request.task)
        observer?.invoke()
        cancelRequest=engine.prepare {outcome->synchronized(this){
            if(token==generation) {
                state=outcome.fold({state.copy(busy=false,preparing=false,preparationRequired=false,retryReady=true)},
                    {state.copy(busy=false,preparing=false,error=it.message?:"모델 준비 실패")})
                observer?.invoke()
            }
        }}
    }
    @Synchronized private fun fail(message: String) {
        cancel()
        state = state.copy(open = true, error = message)
        observer?.invoke()
    }
    @Synchronized fun reopen() { state=state.copy(open=true);observer?.invoke() }
    @Synchronized fun hide() {
        if(state.busy||state.loadingSource)cancel()
        state=state.copy(open=false);observer?.invoke()
    }
    @Synchronized fun cancel() {
        generation++
        cancelRequest?.invoke(); cancelRequest = null
        retryRequest=null
        state = state.copy(busy = false,preparing=false,retryReady=false,loadingSource=false)
        observer?.invoke()
    }
    @Synchronized fun close() {
        cancel()
        observer = null
        state = AiState()
        selectedSource=null;draft="";expanded=false
        engine.close()
    }
}
