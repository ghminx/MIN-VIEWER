package org.minviewer.app

data class AiPanelLayout(val side:Boolean,val full:Boolean,val compact:Boolean)

/** 창 크기·글자 배율에 따른 패널 배치. dp 기준이며 문서와 버튼의 실제 측정은 View에서 수행. */
fun aiPanelLayout(width:Float,height:Float,fontScale:Float,open:Boolean,expanded:Boolean):AiPanelLayout {
    val side=width>=720||(width>=600&&height<480)
    val full=open&&((expanded&&height<480)||(!side&&height<360)||(fontScale>=1.5f&&height<600))
    return AiPanelLayout(side,full,width<360||height<420||fontScale>=1.3f)
}
