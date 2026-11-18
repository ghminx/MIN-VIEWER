package org.minviewer.app

import org.junit.Assert.*
import org.junit.Test

class AiResultTextTest {
    @Test fun proseResponseRemainsProseAndSummaryDoesNotRequireThreeBullets() {
        val original="나무는 그늘과 증산으로 주변을 식히는 데 도움을 줄 수 있습니다.\n\n효과는 지역과 날씨에 따라 달라 관찰이 필요합니다."
        val result=AiResultText.format(original)
        assertEquals(original,result.text)
        assertTrue(result.headings.isEmpty());assertTrue(result.lists.isEmpty())
        val prompt=AiPrompts.build(AiRequest(AiSource(0,original),AiTask.SUMMARY))
        assertFalse(prompt.contains("세 항목"))
        assertTrue(prompt.contains("필요한 경우에만"))
    }

    @Test fun headingsAndSummaryItemsHaveReadingSpaceWithoutLosingContent() {
        val result=AiResultText.format("## 핵심 내용\n- **전력 사용량**은 12 kWh입니다.\n- 절감률은 8.5%입니다.")
        assertEquals("핵심 내용\n\n• 전력 사용량은 12 kWh입니다.\n\n• 절감률은 8.5%입니다.",result.text)
        assertEquals(listOf("핵심 내용"),result.headings.map {result.text.substring(it)})
        assertEquals(listOf("전력 사용량"),result.emphasis.map {result.text.substring(it)})
        assertEquals(listOf("• 전력 사용량은 12 kWh입니다.","• 절감률은 8.5%입니다."),result.lists.map {result.text.substring(it)})
    }

    @Test fun literalTranslationKeepsParagraphsSymbolsAndNumbersExactly() {
        val original="## Original heading\n\n**12 kWh** costs 3,500 won.\n-3°C; 8.5%; x * y; <script>text only</script>"
        val result=AiResultText.format(original,literal=true)
        assertEquals(original,result.text)
        assertTrue(result.headings.isEmpty());assertTrue(result.emphasis.isEmpty());assertTrue(result.lists.isEmpty())
    }

    @Test fun longExplanationKeepsParagraphsOrderedStepsAndLiteralMath() {
        val result=AiResultText.format("**원리 설명**\n\n물은 주변 열을 흡수합니다.\n이 설명은 다음 줄에서도 이어집니다.\n\n1. 온도를 확인합니다.\n2. -3°C에서도 수치를 보존합니다.\n\nx * y = 12; √dk; 🚀")
        assertEquals("원리 설명\n\n물은 주변 열을 흡수합니다.\n이 설명은 다음 줄에서도 이어집니다.\n\n1. 온도를 확인합니다.\n\n2. -3°C에서도 수치를 보존합니다.\n\nx * y = 12; √dk; 🚀",result.text)
        assertEquals(listOf("원리 설명"),result.headings.map{result.text.substring(it)})
        assertEquals(2,result.lists.size)
    }
}
