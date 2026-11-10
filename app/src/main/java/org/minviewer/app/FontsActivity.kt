package org.minviewer.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class FontsActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private val fonts by lazy { UserFonts(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.apply(this)
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (ThemePreferences.isDark(this)) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { text = "사용 권한이 있는 TTF 또는 OTF 글꼴을 선택해 주세요. 원본 파일은 변경하지 않습니다." }
        val add = Button(this).apply {
            text = "글꼴 추가"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, 1)
            }
        }
        val back = Button(this).apply { text = "뒤로"; setOnClickListener { finish() } }
        val scroll = ScrollView(this).apply { addView(list) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(16, insets.systemWindowInsetTop + 16, 16, insets.systemWindowInsetBottom + 16)
                insets
            }
            addView(back)
            addView(status)
            addView(add)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        refresh()
    }

    @Deprecated("Android 파일 선택 결과 처리")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != 1 || resultCode != RESULT_OK) return
        status.text = "글꼴을 추가하는 중입니다."
        worker.execute {
            try {
                fonts.add(uri)
                runOnUiThread { if (!isFinishing && !isDestroyed) { status.text = "글꼴을 추가했습니다."; refresh() } }
            } catch (error: Exception) {
                runOnUiThread { if (!isFinishing && !isDestroyed) status.text = "글꼴 오류: ${error.message}" }
            }
        }
    }

    private fun refresh() {
        list.removeAllViews()
        val entries = fonts.all()
        if (entries.isEmpty()) {
            list.addView(TextView(this).apply { text = "추가한 글꼴이 없습니다." })
            return
        }
        entries.forEach { entry ->
            val row = LinearLayout(this)
            row.addView(TextView(this).apply { text = entry.name; textSize = 16f }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply {
                text = "삭제"
                contentDescription = "${entry.name} 삭제"
                setOnClickListener {
                    AlertDialog.Builder(this@FontsActivity).setMessage("앱에 저장된 글꼴을 삭제할까요? 원본 파일은 유지됩니다.")
                        .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ ->
                            try { fonts.remove(entry); refresh() } catch (error: java.io.IOException) { status.text = "글꼴 오류: ${error.message}" }
                        }.show()
                }
            })
            list.addView(row)
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
