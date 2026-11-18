package org.minviewer.app

import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.java.GenerativeModelFutures
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** 로컬 시험 빌드에만 포함하는 AICore 연결. 원문·응답을 기록하지 않음. */
class NanoAiEngine : AiEngine {
    private val worker = Executors.newSingleThreadExecutor()
    private var promptClient: GenerativeModelFutures? = null
    @Volatile private var pending: ListenableFuture<*>? = null

    override fun start(request: AiRequest, receive: (Result<String>) -> Unit): () -> Unit {
        val job = worker.submit {
            val outcome = runCatching {
                generate(request)
            }.recoverCatching { error ->
                val cause = if(error is ExecutionException)error.cause ?: error else error
                if(cause is IOException)throw cause
                if(cause is InterruptedException)throw cause
                val code=(cause as? GenAiException)?.errorCode
                throw IOException("기기 내 AI 요청이 거절되었습니다 (${cause.javaClass.simpleName}${code?.let {": $it"}?:""}). 지원 상태·입력 길이·사용 한도를 확인한 뒤 다시 요청해 주세요.")
            }
            receive(outcome)
        }
        return { pending?.cancel(true); job.cancel(true) }
    }

    private fun requireAvailable(status: Int) {
        when(status) {
            FeatureStatus.AVAILABLE -> Unit
            FeatureStatus.DOWNLOADABLE -> throw AiPreparationRequired()
            FeatureStatus.DOWNLOADING -> throw IOException("AI 모델을 준비하고 있습니다. 준비가 끝난 뒤 다시 요청해 주세요.")
            else -> throw IOException("이 기기에서 요청한 AI 기능을 사용할 수 없습니다.")
        }
    }

    override fun prepare(request: AiRequest, receive: (Result<Unit>) -> Unit): () -> Unit {
        val job=worker.submit {
            receive(runCatching {
                val callback=object:DownloadCallback {}
                val client=promptClient?:GenerativeModelFutures.from(Generation.getClient()).also {promptClient=it}
                await(client.download(callback),600)
                Unit
            }.recoverCatching {error->
                val cause=if(error is ExecutionException)error.cause?:error else error
                val code=(cause as? GenAiException)?.errorCode
                throw IOException("AI 모델 준비 실패 (${cause.javaClass.simpleName}${code?.let {": $it"}?:""}). 기기의 연결·저장 공간을 확인해 주세요.")
            })
        }
        return {pending?.cancel(true);job.cancel(true)}
    }

    private fun generate(input: AiRequest): String {
        val client = promptClient ?: GenerativeModelFutures.from(Generation.getClient()).also { promptClient = it }
        requireAvailable(await(client.checkStatus()))
        val builder = GenerateContentRequest.Builder(TextPart(AiPrompts.build(input))).apply {
            temperature = .2f; candidateCount = 1
        }
        val limit = minOf(4000, await(client.getTokenLimit()))
        val available=limit-await(client.countTokens(builder.build())).totalTokens-32
        if(available < 512)
            throw IOException("선택한 내용이 AI 입력 한도보다 깁니다. 더 짧은 범위를 선택해 주세요. 원문은 자르지 않았습니다.")
        builder.maxOutputTokens=minOf(if(input.task==AiTask.SUMMARY)512 else 2048,available)
        val request=builder.build()
        val candidate=await(client.generateContent(request)).candidates.firstOrNull()
        if(candidate?.finishReason==Candidate.FinishReason.MAX_TOKENS)
            throw IOException("출력 길이 한도에 도달했습니다. 미완성 결과는 표시하지 않았습니다. 더 짧은 범위를 선택해 주세요.")
        return candidate?.text.orEmpty()
    }

    private fun <T> await(future: ListenableFuture<T>, seconds:Long=120): T {
        pending = future
        try { return future.get(seconds, TimeUnit.SECONDS) }
        catch(error: TimeoutException) { throw IOException("AI 응답 시간이 초과되었습니다. 자동 재요청하지 않았습니다.", error) }
        finally { if(!future.isDone)future.cancel(true); pending = null }
    }

    override fun close() {
        pending?.cancel(true)
        worker.submit {
            promptClient?.let { client ->
                try { client.clearImplicitCaches().get(10, TimeUnit.SECONDS) }
                catch(error: Exception) { android.util.Log.w("MinViewerAI", "AI 캐시 정리 실패: ${error.javaClass.simpleName}") }
                finally { client.getGenerativeModel().close() }
            }
            promptClient = null
        }
        worker.shutdown()
    }
}
