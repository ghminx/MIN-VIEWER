package org.minviewer.app

/** AI 응답의 제목·목록·강조를 읽기용 텍스트와 범위로 변환. HTML 실행·링크 생성 제외. */
object AiResultText {
    data class Content(val text:String, val headings:List<IntRange> = emptyList(),
        val emphasis:List<IntRange> = emptyList(), val lists:List<IntRange> = emptyList())

    fun format(raw:String, literal:Boolean=false):Content {
        if(literal)return Content(raw)
        data class Block(val text:String,val heading:Boolean,val list:Boolean)
        val blocks=mutableListOf<Block>()
        var separated=true
        raw.lines().forEach {line->
            val trimmed=line.trim()
            if(trimmed.isEmpty())separated=true
            else {
                val heading=Regex("^#{1,6} +(.+)$").matchEntire(trimmed)
                val boldHeading=Regex("^\\*\\*(.+?)\\*\\*:?").matchEntire(trimmed)
                val bullet=Regex("^[-*•] +").find(trimmed)
                val ordered=Regex("^\\d+[.)] +").containsMatchIn(trimmed)
                val isHeading=heading!=null||boldHeading!=null
                val isList=bullet!=null||ordered
                val text=heading?.groupValues?.get(1)?:boldHeading?.groupValues?.get(1)
                    ?:if(bullet!=null)"• "+trimmed.substring(bullet.range.last+1)else trimmed
                if(!separated&&!isHeading&&!isList&&blocks.lastOrNull()?.heading==false) {
                    val previous=blocks.removeAt(blocks.lastIndex)
                    blocks+=previous.copy(text=previous.text+"\n"+text)
                }else blocks+=Block(text,isHeading,isList)
                separated=false
            }
        }
        val text=StringBuilder()
        val headings=mutableListOf<IntRange>();val emphasis=mutableListOf<IntRange>();val lists=mutableListOf<IntRange>()
        blocks.forEach {block->
            if(text.isNotEmpty())text.append("\n\n")
            val start=text.length
            var copied=0
            Regex("\\*\\*(.+?)\\*\\*").findAll(block.text).forEach {match->
                text.append(block.text.substring(copied,match.range.first))
                val boldStart=text.length;text.append(match.groupValues[1]);emphasis+=boldStart until text.length
                copied=match.range.last+1
            }
            text.append(block.text.substring(copied))
            if(block.heading)headings+=start until text.length
            if(block.list)lists+=start until text.length
        }
        return Content(text.toString(),headings,emphasis,lists)
    }
}
