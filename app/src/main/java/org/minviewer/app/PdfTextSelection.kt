package org.minviewer.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ImageView
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.graphics.Rect
import android.widget.Toast

/** 페이지 위 선택 범위와 클립보드 조작. */
internal class PdfTextSelection(
    private val activity: Activity,
    private val imageAt: (Float, Float) -> ImageView?,
    private val point: (ImageView, MotionEvent) -> Pair<Float, Float>?,
    private val load: (Int, (Result<TextSelectionPage>) -> Unit) -> Unit,
    private val cancelGesture: () -> Unit
) {
    var onAi: ((String, Int, String) -> Unit)? = null
    private var image: ImageView? = null
    private var page: TextSelectionPage? = null
    private var start = -1
    private var end = -1
    private var draggingStart = false
    private var active = false
    private var fingerDown = false
    private var generation = 0
    private var hold: Runnable? = null
    private var downX = 0f
    private var downY = 0f
    private var popup: ActionMode? = null
    private var latest: Pair<Float,Float>? = null
    private var moved = false
    private var handleOffset = 0f to 0f
    private var initialWord: IntRange? = null

    fun clear() {
        hold?.let { image?.removeCallbacks(it) }; hold = null
        generation++
        popup?.finish(); popup = null
        image?.parent?.requestDisallowInterceptTouchEvent(false)
        image?.invalidate(); image = null; page = null; active = false; fingerDown = false
        initialWord=null;handleOffset=0f to 0f
    }

    fun touch(event: MotionEvent): Boolean {
        if(event.pointerCount>1) { clear();return false }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            val consumed = active
            clear()
            return consumed
        }
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val old = image
            val handle = if(active && page!=null) touchedHandle(event) else null
            if (handle != null) {
                draggingStart = handle; fingerDown = true
                initialWord=null
                val pos=point(old!!,event)!!
                val g=page!!.glyphs[if(handle)start else end]
                handleOffset=(pos.first-(if(handle)g.left else g.right)) to (pos.second-g.bottom)
                popup?.finish();popup=null
                cancelGesture()
                return true
            }
            clear()
            val target = imageAt(event.rawX, event.rawY) ?: return false
            val initial = point(target, event) ?: return false
            downX = event.rawX; downY = event.rawY; image = target
            fingerDown = true; latest = initial; moved = false
            val token = generation
            hold = Runnable {
                active = true
                cancelGesture()
                target.parent.requestDisallowInterceptTouchEvent(true)
                load(target.getTag(R.id.pdf_page_index) as Int) { result ->
                    if (generation != token || !target.isAttachedToWindow) return@load
                    result.onSuccess { value ->
                        val drawable=target.drawable?:return@onSuccess
                        val padding=6*activity.resources.displayMetrics.density
                        val word=value.wordAt(initial.first,initial.second,
                            padding/target.imageMatrix.mapRadius(drawable.intrinsicWidth.toFloat()),
                            padding/target.imageMatrix.mapRadius(drawable.intrinsicHeight.toFloat()))
                        if (word == null) {
                            clear()
                        } else {
                            page = value; start = word.first; end = word.last
                            initialWord=word
                            if(moved)latest?.let {updateRange(value.hit(it.first,it.second))}
                            draggingStart = false; target.invalidate()
                            if (!fingerDown) showCopy()
                        }
                    }.onFailure { error ->
                        Toast.makeText(activity, "텍스트 선택 실패: ${error.message}", Toast.LENGTH_LONG).show(); clear()
                    }
                }
            }
            target.postDelayed(hold!!, ViewConfiguration.getLongPressTimeout().toLong())
            return false
        }
        if (!active) {
            if (event.actionMasked != MotionEvent.ACTION_MOVE || kotlin.math.hypot(event.rawX-downX, event.rawY-downY) > ViewConfiguration.get(activity).scaledTouchSlop) {
                hold?.let { image?.removeCallbacks(it) }; hold = null
            }
            return false
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            val value = page
            val pos = image?.let { point(it, event) }
            latest=pos; moved=true
            if (value != null && pos != null) {
                popup?.finish();popup=null
                val index = value.hit(pos.first-handleOffset.first, pos.second-handleOffset.second)
                updateRange(index)
                image?.invalidate()
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) { fingerDown = false; showCopy() }
        return true
    }

    private fun updateRange(index:Int) {
        val anchor=initialWord
        if(anchor!=null) {
            if(index<anchor.first){start=index;end=anchor.last}else{start=anchor.first;end=maxOf(anchor.last,index)}
        } else if(draggingStart)start=minOf(index,end) else end=maxOf(index,start)
    }

    private fun showCopy() {
        val target = image ?: return
        if (page == null) return
        popup?.finish()
        popup = target.startActionMode(object:ActionMode.Callback2() {
            override fun onCreateActionMode(mode:ActionMode,menu:Menu):Boolean {
                menu.add(0,1,0,"복사").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(0,2,1,"모두 선택").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                if(onAi!=null) {
                    menu.add(0,5,2,"요약").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                    menu.add(0,3,3,"설명").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                    menu.add(0,4,4,"번역").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                }
                return true
            }
            override fun onPrepareActionMode(mode:ActionMode,menu:Menu)=false
            override fun onActionItemClicked(mode:ActionMode,item:MenuItem):Boolean {
                val text = page?.select(start, end)?.text.orEmpty()
                if(item.itemId==2) {
                    start=0;end=page!!.glyphs.lastIndex;initialWord=null
                    target.invalidate();mode.invalidateContentRect()
                    return true
                }
                if(item.itemId in 3..5&&text.isNotEmpty()) {
                    mode.finish()
                    onAi?.invoke(text,target.getTag(R.id.pdf_page_index) as Int,when(item.itemId){4->"translate";5->"summary";else->"explain"})
                    return true
                }
                if (item.itemId==1 && text.isNotEmpty()) {
                    (activity.getSystemService(Activity.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("문서 텍스트", text))
                    Toast.makeText(activity, "복사했습니다", Toast.LENGTH_SHORT).show()
                }
                clear(); return true
            }
            override fun onDestroyActionMode(mode:ActionMode) {popup=null}
            override fun onGetContentRect(mode:ActionMode,view:View,out:Rect) {
                val drawable=target.drawable?:return
                val rect=RectF()
                page?.select(start,end)?.glyphs?.forEach {g->rect.union(g.left*drawable.intrinsicWidth,g.top*drawable.intrinsicHeight,g.right*drawable.intrinsicWidth,g.bottom*drawable.intrinsicHeight)}
                target.imageMatrix.mapRect(rect);rect.roundOut(out)
                out.intersect(0,0,target.width,target.height)
            }
        },ActionMode.TYPE_FLOATING)
    }

    private fun handlePoint(first:Boolean):FloatArray? {
        val target=image?:return null;val drawable=target.drawable?:return null
        val g=page?.glyphs?.get(if(first)start else end)?:return null
        val p=floatArrayOf((if(first)g.left else g.right)*drawable.intrinsicWidth,g.bottom*drawable.intrinsicHeight)
        target.imageMatrix.mapPoints(p)
        val dp=activity.resources.displayMetrics.density
        p[0]+=if(first)-7*dp else 7*dp;p[1]+=9*dp
        return p
    }

    private fun touchedHandle(event:MotionEvent):Boolean? {
        val target=image?:return null
        val matrix=android.graphics.Matrix();target.transformMatrixToGlobal(matrix)
        val inverse=android.graphics.Matrix();if(!matrix.invert(inverse))return null
        val p=floatArrayOf(event.rawX,event.rawY);inverse.mapPoints(p)
        fun distance(first:Boolean):Float {val h=handlePoint(first)!!;return kotlin.math.hypot(h[0]-p[0],h[1]-p[1])}
        val a=distance(true);val b=distance(false)
        return if(minOf(a,b)<=24*activity.resources.displayMetrics.density) a<=b else null
    }

    fun draw(canvas: Canvas, target: ImageView) {
        val value = page ?: return
        if(target!==image&&target.getTag(R.id.pdf_page_index)==image?.getTag(R.id.pdf_page_index))image=target
        if (target !== image || start < 0 || end < 0) return
        val drawable = target.drawable ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(100, 38, 132, 255) }
        val saved = canvas.save()
        canvas.concat(target.imageMatrix)
        val w = drawable.intrinsicWidth.toFloat(); val h = drawable.intrinsicHeight.toFloat()
        value.select(start, end).glyphs.forEach { g -> canvas.drawRect(RectF(g.left*w,g.top*h,g.right*w,g.bottom*h),paint) }
        paint.color = Color.rgb(38, 132, 255)
        canvas.restoreToCount(saved)
        val radius=9*activity.resources.displayMetrics.density
        for(first in listOf(true,false))handlePoint(first)?.let {p->canvas.drawCircle(p[0],p[1],radius,paint)}
    }
}
