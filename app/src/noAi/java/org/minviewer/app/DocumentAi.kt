package org.minviewer.app

import android.app.Activity
import android.view.MotionEvent
import android.view.View

/** 공개·일반 디버그 빌드의 AI 미포함 경계. SDK 또는 AI UI를 생성하지 않음. */
@Suppress("UNUSED_PARAMETER")
class DocumentAi(activity: Activity, controls: ViewerControls, document: View,
    load: ((Result<AiSource>) -> Unit) -> Unit, go: (Int) -> Unit, beforeOpen: () -> Unit,
    retained: Any? = null, viewportChanging: () -> Unit = {}) {
    val view = document
    fun selected(text: String, page: Int, task: String) = Unit
    fun hide() = false
    fun ownsTouch(event: MotionEvent) = false
    fun retain(): Any? = null
    fun close(keep: Boolean) = Unit
    fun reset() = Unit
    fun documentReady() = Unit
    companion object { const val enabled = false }
}
