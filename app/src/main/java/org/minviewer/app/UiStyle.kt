package org.minviewer.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.widget.Button
import android.widget.TextView
import android.widget.EditText
import android.widget.LinearLayout
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.text.method.HideReturnsTransformationMethod

/** 앱 UI의 색상·서체·터치 영역 공유. 문서 렌더링에는 적용하지 않음. */
class UiStyle(val activity: Activity) {
    val navy = activity.getColor(R.color.ui_navy)
    val dark = ThemePreferences.isDark(activity)
    val text = activity.getColor(if (dark) R.color.ui_text_dark else R.color.ui_text_light)
    val muted = activity.getColor(if (dark) R.color.ui_muted_dark else R.color.ui_muted_light)
    val surface = activity.getColor(if (dark) R.color.ui_surface_dark else R.color.ui_surface_light)
    val background = activity.getColor(if (dark) R.color.ui_background_dark else R.color.ui_background_light)
    val selected = activity.getColor(if (dark) R.color.ui_selected_dark else R.color.ui_selected_light)
    val line = activity.getColor(if (dark) R.color.ui_line_dark else R.color.ui_line_light)
    val danger = activity.getColor(if (dark) R.color.ui_danger_dark else R.color.ui_danger)
    fun dp(value: Int) = (value * activity.resources.displayMetrics.density + .5f).toInt()
    fun useSidePanel(): Boolean {
        val size=activity.resources.configuration
        return size.screenWidthDp>=840||(size.screenWidthDp>=600&&size.screenWidthDp>size.screenHeightDp)
    }
    fun rounded(color: Int, radius: Int = 8, border: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        border?.let { setStroke(dp(1), it) }
    }
    fun label(value: String, size: Float = 14f, bold: Boolean = false, color: Int = text) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = activity.resources.getFont(if (bold) R.font.pretendard_semibold else R.font.pretendard_regular)
    }
    fun searchInput(value: String = "") = EditText(activity).apply {
        setSingleLine(); hint = "검색어"; contentDescription = "검색어"; setText(value)
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_ui_search, 0, 0, 0)
        compoundDrawablePadding = dp(10)
    }
    fun confirmDelete(message: String, title: String = "최근 기록을 지울까요?", action: String = "기록 지우기", remove: () -> Unit) {
        val dialog = AlertDialog.Builder(activity).setTitle(title).setMessage(message)
            .setNegativeButton("취소", null).setPositiveButton(action) { _, _ -> remove() }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                setTextColor(Color.WHITE)
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x30ffffff),
                    rounded(activity.getColor(R.color.ui_danger),10),null)
            }
        }
        dialog.show()
    }

    fun passwordDialog(title:String,error:String?=null,cancelable:Boolean=true,cancel:()->Unit,submit:(String)->Unit):AlertDialog {
        val root=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),dp(8))}
        root.addView(label("비밀번호",14f,true))
        val row=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL}
        val input=EditText(activity).apply {
            setSingleLine()
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            contentDescription="문서 비밀번호";isSaveEnabled=false
            minimumHeight=dp(48)
        }
        row.addView(input,LinearLayout.LayoutParams(0,-2,1f))
        val eye=button("비밀번호 표시"){}.apply {
            text="";setPadding(dp(12),0,dp(12),0);setCompoundDrawablesRelative(icon("표시"),null,null,null)
        }
        eye.setOnClickListener {
            val reveal=input.transformationMethod is PasswordTransformationMethod
            input.transformationMethod=if(reveal)HideReturnsTransformationMethod.getInstance()else PasswordTransformationMethod.getInstance()
            input.setSelection(input.text.length)
            eye.contentDescription=if(reveal)"비밀번호 숨기기"else"비밀번호 표시"
            eye.setCompoundDrawablesRelative(icon(if(reveal)"숨기기"else"표시"),null,null,null)
        }
        row.addView(eye,LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(row)
        val errorLabel=label(error.orEmpty(),12f,color=danger).apply {setPadding(0,dp(8),0,0);visibility=if(error==null)android.view.View.GONE else android.view.View.VISIBLE}
        root.addView(errorLabel)
        val dialog=AlertDialog.Builder(activity).setTitle(title).setView(root).setCancelable(cancelable)
            .setNegativeButton("취소"){_,_->cancel()}.setOnCancelListener{cancel()}.setPositiveButton("열기",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value=input.text.toString()
                if(value.isEmpty()){errorLabel.text="비밀번호를 입력해 주세요";errorLabel.visibility=android.view.View.VISIBLE;input.requestFocus()}else{dialog.dismiss();submit(value)}
            }
        }
        dialog.show();input.requestFocus()
        return dialog
    }

    fun icon(kind: String, color: Int = text) = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        init { setBounds(0,0,dp(24),dp(24)) }
        override fun draw(canvas: Canvas) {
            canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
            canvas.scale(bounds.width()/24f,bounds.height()/24f);UiIcon.draw(canvas,kind,paint);canvas.restore()
        }
        override fun setAlpha(alpha: Int) { paint.alpha=alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter=filter }
        @Deprecated("Android Drawable 호환성") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    fun button(label: String, icon: String? = null, color: Int = text, action: () -> Unit): Button {
        val button = if (icon == null) Button(activity) else object : Button(activity) {
            private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                pen.color = if (isEnabled) currentTextColor else muted
                pen.alpha = if (isEnabled) 255 else 90
                canvas.save()
                canvas.translate(width / 2f - dp(12), height / 2f - dp(12))
                canvas.scale(dp(24) / 24f, dp(24) / 24f)
                UiIcon.draw(canvas, icon, pen)
                canvas.restore()
            }
        }
        return button.apply {
            text = label; contentDescription = label; textSize = 12f; isAllCaps = false; setTextColor(color)
            typeface = activity.resources.getFont(R.font.pretendard_semibold)
            minWidth = dp(48); minimumWidth = dp(48); minHeight = dp(48); minimumHeight = dp(48)
            setPadding(dp(if (icon == null) 12 else 0), 0, dp(if (icon == null) 12 else 0), 0)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rounded(selected, border = if (dark) activity.getColor(R.color.ui_accent_dark) else navy))
                addState(intArrayOf(android.R.attr.state_selected), rounded(selected))
                addState(intArrayOf(android.R.attr.state_pressed), rounded(selected))
                addState(intArrayOf(), rounded(Color.TRANSPARENT))
            }
            gravity = Gravity.CENTER; setOnClickListener { action() }
        }
    }
}

internal object UiIcon {
    fun draw(canvas: Canvas, kind: String, paint: Paint) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.7f; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        fun line(x: Float, y: Float, xx: Float, yy: Float) = canvas.drawLine(x, y, xx, yy, paint)
        fun path(vararg points: Float) { val p = Path(); p.moveTo(points[0], points[1]); for (i in 2 until points.size step 2) p.lineTo(points[i], points[i + 1]); canvas.drawPath(p, paint) }
        when (kind) {
            "펜" -> { path(7f,3f,17f,3f,17f,15f,12f,22f,7f,15f,7f,3f); line(7f,15f,17f,15f); line(12f,16f,12f,20f) }
            "형광펜" -> { path(7f,2f,17f,2f,17f,15f,14f,19f,9f,19f,6f,15f,7f,2f); line(7f,8f,17f,8f); path(9f,19f,9f,22f,14f,22f,14f,19f) }
            "지우개" -> { path(3f,14f,13f,3f,21f,10f,12f,21f,9f,21f,3f,15f); line(8f,9f,16f,17f); line(12f,21f,22f,21f) }
            "올가미" -> { paint.pathEffect = DashPathEffect(floatArrayOf(3f,3f),0f); canvas.drawOval(3f,4f,21f,18f,paint); paint.pathEffect = null; path(5f,15f,3f,21f,8f,22f) }
            "실행 취소", "다시 실행" -> { if (kind == "다시 실행") {canvas.save();canvas.scale(-1f,1f,12f,12f)}; path(8f,4f,3f,9f,8f,14f); val p = Path();p.moveTo(3f,9f);p.lineTo(13f,9f);p.quadTo(21f,9f,21f,19f);canvas.drawPath(p,paint);if(kind=="다시 실행")canvas.restore() }
            "격자", "목록" -> for (y in listOf(3f,14f)) for (x in listOf(3f,14f)) canvas.drawRoundRect(x,y,x+7,y+7,1f,1f,paint)
            "행 목록" -> for (y in listOf(5f,12f,19f)) {line(3f,y,4f,y);line(8f,y,21f,y)}
            "최근" -> {canvas.drawCircle(12f,12f,9f,paint);path(12f,6f,12f,12f,17f,12f)}
            "검색" -> {canvas.drawCircle(10f,10f,6.5f,paint);line(15f,15f,21f,21f)}
            "설정" -> {path(9f,3f,15f,3f,16f,6f,19f,7f,21f,12f,19f,17f,16f,18f,15f,21f,9f,21f,8f,18f,5f,17f,3f,12f,5f,7f,8f,6f,9f,3f);canvas.drawCircle(12f,12f,3f,paint)}
            "이전", "다음" -> {if(kind=="다음"){canvas.save();canvas.scale(-1f,1f,12f,12f)};path(15f,5f,8f,12f,15f,19f);if(kind=="다음")canvas.restore()}
            "확대", "축소" -> {canvas.drawCircle(10f,10f,7f,paint);line(15f,15f,21f,21f);line(6f,10f,14f,10f);if(kind=="확대")line(10f,6f,10f,14f)}
            "선택" -> path(4f,12f,9f,17f,20f,6f)
            "도구 펼치기" -> path(5f,15f,12f,8f,19f,15f)
            "도구 접기" -> path(5f,9f,12f,16f,19f,9f)
            "내보내기" -> {path(5f,10f,5f,21f,19f,21f,19f,10f);line(12f,15f,12f,2f);path(7f,7f,12f,2f,17f,7f)}
            "백업" -> {canvas.drawRect(3f,7f,21f,21f,paint);canvas.drawRect(2f,3f,22f,7f,paint);line(9f,11f,15f,11f)}
            "표시", "숨기기" -> {val p=Path();p.moveTo(2f,12f);p.quadTo(12f,-1f,22f,12f);p.quadTo(12f,25f,2f,12f);canvas.drawPath(p,paint);canvas.drawCircle(12f,12f,3f,paint);if(kind=="숨기기")line(3f,3f,21f,21f)}
            "색상" -> {canvas.drawCircle(12f,12f,8f,paint);paint.style=Paint.Style.FILL;canvas.drawCircle(12f,12f,5f,paint)}
            "굵기" -> {paint.strokeWidth=3.5f;line(4f,12f,20f,12f)}
            "추가" -> {line(12f,4f,12f,20f);line(4f,12f,20f,12f)}
            "닫기", "선택 해제" -> {line(6f,6f,18f,18f);line(6f,18f,18f,6f)}
            "뒤로" -> {path(14f,5f,7f,12f,14f,19f);line(7f,12f,21f,12f)}
            "복사" -> {canvas.drawRoundRect(8f,8f,21f,21f,2f,2f,paint);path(16f,5f,16f,3f,3f,3f,3f,16f,5f,16f)}
            "선택 삭제" -> {path(5f,6f,19f,6f,18f,21f,6f,21f,5f,6f);line(3f,6f,21f,6f);path(8f,5f,8f,2f,16f,2f,16f,5f)}
            "크기 조절" -> {path(3f,9f,3f,3f,9f,3f);path(15f,21f,21f,21f,21f,15f);line(3f,3f,9f,9f);line(15f,15f,21f,21f)}
            else -> for(y in listOf(5f,12f,19f))canvas.drawCircle(12f,y,1f,paint)
        }
    }
}
