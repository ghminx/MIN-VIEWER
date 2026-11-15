package org.minviewer.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.view.MotionEvent
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

class PresentationMode(private val activity: Activity, private val controls: View, private val exit: () -> Unit) {
    var active = false
        private set
    private var previousSystemUi = 0
    private var closeButton: View? = null
    private var exitGesture = false

    fun isExitGesture(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val bounds = Rect()
            exitGesture = closeButton?.getGlobalVisibleRect(bounds) == true && bounds.contains(event.rawX.toInt(), event.rawY.toInt())
        }
        return exitGesture
    }

    @Suppress("DEPRECATION")
    fun enter() {
        if (active) return
        active = true
        previousSystemUi = activity.window.decorView.systemUiVisibility
        controls.visibility = View.GONE
        (controls.parent as View).setPadding(0, 0, 0, 0)
        activity.window.decorView.systemUiVisibility = previousSystemUi or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val size = (48 * activity.resources.displayMetrics.density).toInt()
        val button = TextView(activity).apply {
            text = "×"
            textSize = 28f
            gravity = Gravity.CENTER
            contentDescription = "발표 모드 종료"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = size / 2f }
            setOnClickListener { exit() }
        }
        activity.addContentView(button, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END))
        closeButton = button
    }

    @Suppress("DEPRECATION")
    fun leave() {
        if (!active) return
        active = false
        (closeButton?.parent as? ViewGroup)?.removeView(closeButton)
        closeButton = null
        controls.visibility = View.VISIBLE
        activity.window.decorView.systemUiVisibility = previousSystemUi
        (controls.parent as View).requestApplyInsets()
    }
}
