package org.minviewer.aiprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import com.google.mlkit.genai.summarization.*;
import com.google.mlkit.genai.common.FeatureStatus;
import java.util.concurrent.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public class AiProbeActivity extends Activity {
    private TextView text;
    private Summarizer summarizer;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        text = new TextView(this); text.setPadding(32,80,32,32); text.setTextSize(20); text.setText("AI 기능 지원 확인 중"); setContentView(text);
        String language = getIntent().getStringExtra("language");
        worker.submit(() -> {
            JSONObject report = new JSONObject();
            try {
                boolean korean = !"en".equals(language);
                report.put("language", korean ? "ko" : "en");
                summarizer = Summarization.getClient(SummarizerOptions.builder(this)
                    .setInputType(SummarizerOptions.InputType.ARTICLE)
                    .setOutputType(SummarizerOptions.OutputType.THREE_BULLETS)
                    .setLanguage(korean ? SummarizerOptions.Language.KOREAN : SummarizerOptions.Language.ENGLISH).build());
                int status = summarizer.checkFeatureStatus().get(45, TimeUnit.SECONDS);
                report.put("status", status);
                report.put("statusName", status == FeatureStatus.AVAILABLE ? "AVAILABLE" : status == FeatureStatus.DOWNLOADABLE ? "DOWNLOADABLE" : status == FeatureStatus.DOWNLOADING ? "DOWNLOADING" : "UNAVAILABLE");
                if (status == FeatureStatus.AVAILABLE) {
                    String sample = korean ? SAMPLE_KO : SAMPLE_EN;
                    report.put("inputCharacters", sample.length());
                    long start = android.os.SystemClock.elapsedRealtime();
                    String summary = summarizer.runInference(SummarizationRequest.builder(sample).build()).get(120, TimeUnit.SECONDS).getSummary();
                    report.put("elapsedMs", android.os.SystemClock.elapsedRealtime()-start);
                    report.put("summary", summary);
                    if (summary.trim().isEmpty()) throw new IllegalStateException("Empty summary");
                    report.put("success", true);
                }
            } catch (Exception e) {
                Throwable cause = e instanceof ExecutionException && e.getCause()!=null ? e.getCause() : e;
                try { report.put("error", cause.getClass().getName()+": "+cause.getMessage()); }
                catch (org.json.JSONException diagnosticError) { android.util.Log.e("MinAiProbe", "Error recording failed", diagnosticError); }
            } finally {
                if (summarizer != null) summarizer.close();
                try { java.nio.file.Files.write(new File(getFilesDir(),"result.json").toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8)); }
                catch (Exception e) { android.util.Log.e("MinAiProbe", "Result write failed", e); }
                runOnUiThread(() -> { if (!isDestroyed()) text.setText(report.toString()); });
            }
        });
    }
    @Override public void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
    static final String SAMPLE_KO = "도서관은 다음 달부터 시민을 위한 새로운 독서 프로그램을 운영한다. 프로그램의 목적은 주민들이 꾸준히 책을 읽고 서로 의견을 나눌 수 있도록 돕는 것이다. 참여 대상은 지역 주민이며, 초등학생을 위한 모임과 성인을 위한 모임을 따로 운영한다. 신청은 도서관 안내 데스크에서 할 수 있으며 참가비는 무료다. 각 모임은 일주일에 한 번 진행되고 정원은 열다섯 명이다. " +
        "어린이 모임에서는 사서가 선정한 그림책과 짧은 이야기를 함께 읽고 등장인물의 행동에 대해 이야기한다. 성인 모임은 소설과 역사, 과학 분야의 책을 번갈아 읽는다. 책을 미리 다 읽지 못한 사람도 참여할 수 있도록 모임 시작 때 내용을 간단히 소개한다. 참가자는 다른 사람의 의견을 존중하고 개인 정보를 외부에 공개하지 않아야 한다. " +
        "도서관은 프로그램과 함께 오래된 열람실의 조명과 의자를 교체한다. 공사는 주말에 진행하며 공사 기간에도 자료 대출과 반납은 가능하다. 다만 소음이 발생하는 시간에는 일부 좌석의 이용이 제한된다. 이용자는 방문 전에 공지 사항을 확인하는 것이 좋다. 도서관은 두 달 동안의 참여 현황과 만족도 설문을 바탕으로 운영 시간을 조정하고 프로그램을 계속할지 결정할 계획이다.";
    static final String SAMPLE_EN = "The city library will launch a free reading program next month. Residents can join separate groups for children and adults. Each group will meet once a week and accept up to fifteen participants. Registration is available at the library information desk. Children's meetings will focus on picture books and short stories, while adult meetings will alternate between fiction, history, and science. Participants do not need to finish the book before attending because a librarian will introduce its main ideas. The library will also replace old lights and chairs during weekends. Book loans and returns will remain available during construction, although some seats may be unavailable while noisy work is taking place. After two months, the library will review attendance and participant feedback before deciding whether to continue the program.";
}
