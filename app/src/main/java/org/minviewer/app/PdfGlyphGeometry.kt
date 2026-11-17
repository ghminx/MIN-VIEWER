package org.minviewer.app

data class GlyphBounds(val left:Float,val top:Float,val right:Float,val bottom:Float)

/** PDF 기준선·글자 방향을 화면에 표시된 페이지의 좌상단 좌표로 변환. */
fun pdfGlyphBounds(x:Float,y:Float,width:Float,height:Float,direction:Int,rotation:Int,pageWidth:Float,pageHeight:Float):GlyphBounds {
    val (dx,dy)=when((direction%360+360)%360){90->0f to width;180->-width to 0f;270->0f to -width;else->width to 0f}
    val (ax,ay)=when((direction%360+360)%360){90->-height to 0f;180->0f to -height;270->height to 0f;else->0f to height}
    val points=listOf(x to y,(x+dx) to (y+dy),(x+ax) to (y+ay),(x+dx+ax) to (y+dy+ay)).map {(px,py)->
        when((rotation%360+360)%360){90->py to px;180->(pageWidth-px) to py;270->(pageHeight-py) to (pageWidth-px);else->px to (pageHeight-py)}
    }
    return GlyphBounds(points.minOf {it.first},points.minOf {it.second},points.maxOf {it.first},points.maxOf {it.second})
}
