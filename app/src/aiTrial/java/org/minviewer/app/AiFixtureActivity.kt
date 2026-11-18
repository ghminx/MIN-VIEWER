package org.minviewer.app

import android.app.Activity
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** 사용자 문서를 쓰지 않는 로컬 AI 시험 진입점. 공개 빌드에는 포함하지 않음. */
class AiFixtureActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val fixtureDir=File(getExternalFilesDir(null),"ai-fixtures").apply {mkdirs()}
        val format=intent.getStringExtra("format")
        if(format in listOf("hwp","hwpx","docx")) {
            val store=ImportedDocumentStore(File(filesDir,"imported-documents"))
            val staged=File(fixtureDir,"source.$format").inputStream().use {store.prepare(it,"AI-형식-시험.$format"){false}}
            val entry=staged.matches.firstOrNull()?.also {store.discard(staged)}?:store.commit(staged)
            startActivity(Intent(this,MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("content://$packageName.imports/${entry.id}")))
            finish();return
        }
        val output = ByteArrayOutputStream()
        val pdf=PdfDocument()
        try {
            repeat(2) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(595,842,index+1).create())
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {typeface=resources.getFont(R.font.pretendard_regular);textSize=14f}
                page.canvas.drawText("도시의 녹지와 열섬 완화 · ${index+1}쪽",42f,58f,paint)
                var y=100f
                for(paragraph in (if(index==0)SAMPLE else NUMERIC_SAMPLE).split('\n')) {
                    var rest=paragraph
                    while(rest.isNotEmpty()) {
                        val n=paint.breakText(rest,true,510f,null)
                        page.canvas.drawText(rest.substring(0,n),42f,y,paint);rest=rest.substring(n);y+=23
                    }
                    y+=10
                }
                pdf.finishPage(page)
            }
            pdf.writeTo(output)
        } finally {pdf.close()}
        val store=ImportedDocumentStore(File(filesDir,"imported-documents"))
        val staged=store.prepare(ByteArrayInputStream(output.toByteArray()),"AI-읽기-시험.pdf"){false}
        val entry=staged.matches.firstOrNull()?.also {store.discard(staged)} ?: store.commit(staged)
        startActivity(Intent(this,PdfActivity::class.java).setData(Uri.parse("content://$packageName.imports/${entry.id}"))
            .putExtra("name",entry.name).putExtra("restore_page",intent.getIntExtra("page",0)))
        finish()
    }
    companion object {
        const val NUMERIC_SAMPLE="운영팀은 2026년 11월 18일에 전력 사용량을 12 kWh로 기록했다. 전날보다 사용량이 8.5% 줄었고 전기 요금은 3,500원이었다. 이는 하루 동안의 관찰값이며 절감 효과가 앞으로도 유지된다는 뜻은 아니다.\n"+
            "다음 달에는 세 구역에서 같은 시각에 사용량을 측정할 계획이다. 현재는 실험을 완료하지 않았으며 절감의 원인이 새 조명 때문이라고 단정할 수 없다. 날씨와 이용 시간도 사용량에 영향을 줄 수 있다.\n"+
            "담당자는 비교할 때 측정 날짜, 전력 단위와 요금 기준을 함께 확인해야 한다고 설명했다. 이 자료는 예시를 위한 기록이며 실제 청구서가 아니다."
        const val SAMPLE = "도시의 녹지는 그늘을 만들고 증산 작용을 통해 주변 공기의 온도를 낮춘다. 나뭇잎은 햇빛이 지표면에 직접 닿는 것을 줄여 준다. 잎에서 수분이 증발할 때 주변의 열을 흡수하는 현상도 공기를 시원하게 만드는 데 기여한다.\n" +
            "공원을 늘리는 일과 함께 녹지를 서로 연결하는 설계가 필요하다. 가로수와 작은 정원이 이어지면 바람이 흐르는 통로를 만들 수 있다. 공원 하나를 크게 만드는 것만으로 모든 지역의 더위를 해결할 수 있는 것은 아니다. 도시마다 기후와 건물 밀도가 다르므로 지역 조건을 함께 고려해야 한다.\n" +
            "연구팀은 여름철에 세 지역의 기온과 그늘 면적을 비교할 계획이다. 측정은 같은 시각과 같은 높이에서 진행하며 바람과 습도도 함께 기록한다. 특정 날의 기온 차이를 모든 계절의 효과로 일반화하지 않도록 주의해야 한다.\n" +
            "주민들은 가로수가 많은 길을 걸을 때 체감 온도가 낮다고 답했다. 그러나 이번 설문은 자발적으로 참여한 주민들의 응답이므로 도시 전체의 의견을 대표한다고 단정할 수 없다. 실제 기온 측정과 주민 경험을 함께 살펴보는 것이 중요하다.\n" +
            "정리하면 나무는 햇빛을 막고 수분 증발로 열을 흡수하며 연결된 녹지는 바람길 형성에 도움을 줄 수 있다. 효과의 크기는 지역과 날씨에 따라 달라지므로 충분한 관찰이 필요하다."
    }
}
