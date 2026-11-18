package org.minviewer.app

import android.app.Activity
import android.os.Bundle
import android.graphics.*
import android.view.MotionEvent
import android.widget.*

/** 실제 선택 컨트롤러의 지연·창 변경 회귀를 위한 디버그 전용 화면. */
class TextSelectionTestActivity : Activity() {
    private lateinit var selection: PdfTextSelection
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        val fixture=intent.getBooleanExtra("fixture",false)
        val fixturePage=intent.getIntExtra("fixture_page",0).coerceIn(0,1)
        val bitmap=if(fixture) {
            val file=java.io.File(cacheDir,"selection-probe.pdf")
            assets.open("text-selection-complex.pdf").use {input->file.outputStream().use {input.copyTo(it)}}
            val descriptor=android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            android.graphics.pdf.PdfRenderer(descriptor).use {renderer->renderer.openPage(fixturePage).use {page->
                Bitmap.createBitmap(page.width*2,page.height*2,Bitmap.Config.ARGB_8888).also {page.render(it,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)}
            }}
        } else Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);if(!fixture)canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=40f}
        val text="Alpha beta gamma"
        if(!fixture)canvas.drawText(text,100f,300f,paint)
        val glyphs=mutableListOf<TextGlyph>()
        var x=100f
        for(c in text) {
            val w=paint.measureText(c.toString())
            glyphs.add(TextGlyph(c.toString(),x/800f,260f/1000f,(x+w)/800f,300f/1000f,""));x+=w
        }
        // 제품과 같은 네이티브 ImageView 경로를 검증하는 시험 화면.
        @android.annotation.SuppressLint("AppCompatCustomView")
        val image=object:ImageView(this) {
            override fun onDraw(c:Canvas) {super.onDraw(c);selection.draw(c,this)}
        }.apply {setImageBitmap(bitmap);setTag(R.id.pdf_page_index,0);scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="선택 시험 문서"}
        selection=PdfTextSelection(this,{rawX,rawY->
            val r=Rect();if(image.getGlobalVisibleRect(r)&&r.contains(rawX.toInt(),rawY.toInt()))image else null
        },{target,event->
            val transform=Matrix();target.transformMatrixToGlobal(transform)
            val inverse=Matrix();transform.invert(inverse)
            val p=floatArrayOf(event.rawX,event.rawY);inverse.mapPoints(p)
            target.imageMatrix.invert(inverse);inverse.mapPoints(p)
            p[0]/bitmap.width to p[1]/bitmap.height
        },{_,receive->
            image.postDelayed({receive(runCatching {
                if(fixture) {
                    com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
                    assets.open("text-selection-complex.pdf").use {PdfTextExtractor.read(it,fixturePage,"")}
                } else TextSelectionPage(glyphs)
            })},intent.getLongExtra("delay_ms",0).coerceIn(0,5000))
        },{})
        root.addView(image,LinearLayout.LayoutParams(-1,0,1f))
        val result=TextView(this).apply {contentDescription="붙여넣기 결과";textSize=18f}
        root.addView(Button(this).apply {this.text="붙여넣기 확인";setOnClickListener {
            val clipboard=getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            result.text=clipboard.primaryClip?.getItemAt(0)?.text
        }})
        root.addView(result);setContentView(root)
    }
    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        if(::selection.isInitialized&&selection.touch(event))return true
        return super.dispatchTouchEvent(event)
    }
    override fun onDestroy(){if(::selection.isInitialized)selection.clear();super.onDestroy()}
}
