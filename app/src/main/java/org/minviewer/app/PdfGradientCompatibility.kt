package org.minviewer.app

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/** Skia의 긴 그라데이션 함수를 동일한 구간 함수 트리로 분할. */
object PdfGradientCompatibility {
    private fun name(value:String)=COSName.getPDFName(value)
    fun normalize(source:File,target:File) {
        PDDocument.load(source,MemoryUsageSetting.setupTempFileOnly().setTempDir(source.parentFile)).use {document->
            for(item in document.document.objects) {
                val pattern=item.`object` as? COSDictionary?:continue
                if(pattern.getInt(name("PatternType"))!=2)continue
                val shading=pattern.getDictionaryObject(name("Shading")) as? COSDictionary?:continue
                val function=shading.getDictionaryObject(name("Function")) as? COSDictionary?:continue
                split(function)
            }
            document.save(target)
        }
    }

    private fun split(function:COSDictionary) {
        if(function.getInt(name("FunctionType"))!=3)return
        val functions=function.getDictionaryObject(name("Functions")) as COSArray
        if(functions.size()<=32)return
        val bounds=function.getDictionaryObject(name("Bounds")) as COSArray
        val encode=function.getDictionaryObject(name("Encode")) as COSArray
        val domain=function.getDictionaryObject(name("Domain")) as COSArray
        require(bounds.size()==functions.size()-1&&encode.size()==functions.size()*2&&domain.size()==2)
        val grouped=COSArray();val groupedBounds=COSArray();val groupedEncode=COSArray()
        for(first in 0 until functions.size() step 32) {
            val end=minOf(first+32,functions.size())
            val low=if(first==0)domain.getObject(0)else bounds.getObject(first-1)
            val high=if(end==functions.size())domain.getObject(1)else bounds.getObject(end-1)
            val child=COSDictionary().apply {
                setInt(name("FunctionType"),3)
                setItem(name("Domain"),COSArray().apply {add(low);add(high)})
                setItem(name("Functions"),COSArray().apply {for(i in first until end)add(functions.getObject(i))})
                setItem(name("Bounds"),COSArray().apply {for(i in first until end-1)add(bounds.getObject(i))})
                setItem(name("Encode"),COSArray().apply {for(i in first*2 until end*2)add(encode.getObject(i))})
            }
            grouped.add(child)
            if(first>0)groupedBounds.add(low)
            groupedEncode.add(COSFloat((low as COSNumber).floatValue()));groupedEncode.add(COSFloat((high as COSNumber).floatValue()))
        }
        function.setItem(name("Functions"),grouped);function.setItem(name("Bounds"),groupedBounds);function.setItem(name("Encode"),groupedEncode)
        split(function)
    }
}
