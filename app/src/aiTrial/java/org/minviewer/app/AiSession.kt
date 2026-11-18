package org.minviewer.app

enum class AiTask(val label: String) { SUMMARY("요약"), EXPLAIN("설명"), TRANSLATE("번역"), QUESTION("질문") }
data class AiRequest(val source: AiSource, val task: AiTask, val language: String = "영어", val question: String = "", val previousAnswer: String = "")
interface AiEngine {
    fun start(request: AiRequest, receive: (Result<String>) -> Unit): () -> Unit
    fun close()
    fun prepare(request: AiRequest, receive: (Result<Unit>) -> Unit): () -> Unit
}
class AiPreparationRequired : java.io.IOException("AI 모델 준비가 필요합니다. 필요한 모델을 준비한 뒤 다시 실행해 주세요.")
data class AiState(val open: Boolean = false, val source: AiSource? = null, val task: AiTask = AiTask.SUMMARY,
    val language: String = "영어", val busy: Boolean = false, val result: String = "", val error: String = "",
    val preparationRequired: Boolean = false, val preparing: Boolean = false)

/** 문서 수명의 AI 요청·응답 상태. Android 화면은 observer를 통해 갱신. */
class AiSession(private val engine: AiEngine) {
    @Volatile var state = AiState(); private set
    var observer: (() -> Unit)? = null
    var selectedSource: AiSource? = null
    var draft = ""
    var expanded = false
    private var cancelRequest: (() -> Unit)? = null
    private var generation = 0
    @Synchronized
    fun show(source: AiSource, task: AiTask = AiTask.SUMMARY) {
        cancel()
        state = AiState(open = true, source = source, task = task, language = state.language)
        if(source.selected)selectedSource=source
        observer?.invoke()
    }
    @Synchronized fun choose(task: AiTask, language: String = state.language) {
        cancel()
        state = state.copy(task = task, language = language, result = "", error = "",preparationRequired=false)
        observer?.invoke()
    }
    @Synchronized fun run(question: String = "") {
        if(state.busy)return
        val source = requireNotNull(state.source)
        if(source.text.isBlank()) { fail("추출할 텍스트가 없습니다. 이미지 문서는 아직 지원하지 않습니다."); return }
        val token = ++generation
        val request = AiRequest(source, if(question.isBlank())state.task else AiTask.QUESTION, state.language, question, state.result)
        state = state.copy(busy = true, error = "", result = "")
        observer?.invoke()
        cancelRequest = engine.start(request) { outcome ->
            synchronized(this) {
                if (token == generation) {
                    state = completed(outcome)
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
        val request=AiRequest(requireNotNull(state.source),state.task,state.language)
        cancel()
        val token=++generation
        state=state.copy(busy=true,preparing=true,error="",result="")
        observer?.invoke()
        cancelRequest=engine.prepare(request) {outcome->synchronized(this){
            if(token==generation) {
                state=outcome.fold({state.copy(busy=false,preparing=false,preparationRequired=false)},
                    {state.copy(busy=false,preparing=false,error=it.message?:"모델 준비 실패")})
                observer?.invoke()
            }
        }}
    }
    @Synchronized fun fail(message: String) {
        cancel()
        state = state.copy(open = true, error = message)
        observer?.invoke()
    }
    @Synchronized fun reopen() { state=state.copy(open=true);observer?.invoke() }
    @Synchronized fun hide() { cancel();state=state.copy(open=false);observer?.invoke() }
    @Synchronized fun cancel() {
        generation++
        cancelRequest?.invoke(); cancelRequest = null
        state = state.copy(busy = false,preparing=false)
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
