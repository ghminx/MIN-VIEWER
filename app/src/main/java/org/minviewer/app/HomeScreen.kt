package org.minviewer.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

class HomeScreen(
    private val activity: Activity,
    private val recent: RecentDocuments,
    private val openPicker: () -> Unit,
    private val openRecent: (RecentDocument) -> Unit
) : FrameLayout(activity) {
    private val navy = Color.rgb(25, 45, 77)
    private val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val entries = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val clear = TextView(activity)

    init {
        setBackgroundColor(Color.rgb(249, 248, 246))
        setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        addView(body, LayoutParams(-1, -1))
        body.addView(LogoView(activity), LinearLayout.LayoutParams(-1, dp(76)))
        val centered = FrameLayout(activity)
        body.addView(centered, LinearLayout.LayoutParams(-1, 0, 1f))
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), 0)
        }
        centered.addView(column, LayoutParams(-1, -1, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        centered.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val width = minOf(dp(760), view.width)
            if (width > 0 && column.layoutParams.width != width) {
                column.layoutParams = (column.layoutParams as LayoutParams).apply { this.width = width }
            }
        }
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(activity).apply {
            text = "최근 문서"
            textSize = 20f
            setTextColor(navy)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        clear.apply {
            text = "전체 삭제"
            textSize = 14f
            setTextColor(navy)
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setOnClickListener {
                AlertDialog.Builder(activity).setMessage("최근 문서 목록을 모두 지울까요? 원본 파일은 삭제되지 않습니다.")
                    .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ -> recent.clear(); refresh() }.show()
            }
        }
        header.addView(clear)
        header.addView(TextView(activity).apply {
            text = "+"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "파일 열기"
            background = rounded(navy, 10f)
            setOnClickListener { openPicker() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        column.addView(header)
        val scroll = ScrollView(activity).apply { isFillViewport = true }
        scroll.addView(entries)
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        refresh()
    }

    fun refresh() {
        entries.removeAllViews()
        val items = recent.all()
        clear.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (items.isEmpty()) {
            entries.addView(TextView(activity).apply {
                text = "최근에 연 문서가 없습니다\n\n+ 버튼을 눌러 문서를 열어 보세요"
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(95, 105, 119))
            }, LinearLayout.LayoutParams(-1, dp(240)))
            return
        }
        items.forEach { item ->
            val row = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                minimumHeight = dp(72)
                setOnClickListener { openRecent(item) }
            }
            val extension = item.name.substringAfterLast('.', "FILE").uppercase().take(4)
            row.addView(TextView(activity).apply {
                text = extension
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = rounded(navy, 6f)
            }, LinearLayout.LayoutParams(dp(46), dp(46)))
            val details = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(8), 0)
            }
            details.addView(TextView(activity).apply {
                text = item.name
                textSize = 16f
                setTextColor(navy)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            details.addView(TextView(activity).apply {
                text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.openedAt))
                textSize = 12f
                setTextColor(Color.rgb(95, 105, 119))
            })
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(activity).apply {
                text = "⋮"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(navy)
                contentDescription = "${item.name} 목록에서 삭제"
                setOnClickListener {
                    AlertDialog.Builder(activity).setMessage("최근 문서 목록에서 삭제할까요? 원본 파일은 삭제되지 않습니다.")
                        .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ -> recent.remove(item.uri); refresh() }.show()
                }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            entries.addView(row)
            entries.addView(View(activity).apply { setBackgroundColor(Color.rgb(225, 227, 230)) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius * resources.displayMetrics.density
    }

    private class LogoView(activity: Activity) : View(activity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.rgb(25, 45, 77))
            val density = resources.displayMetrics.density
            canvas.save()
            canvas.translate(20 * density, (height - 28 * density) / 2)
            canvas.scale(density * 0.35f, density * 0.35f)
            paint.color = Color.WHITE
            val shapes = listOf(
                floatArrayOf(0f,80f, 0f,0f, 19f,0f, 48f,40f, 77f,0f, 96f,0f, 96f,80f, 77f,80f, 77f,33f, 48f,71f, 19f,33f, 19f,80f),
                floatArrayOf(114f,0f, 133f,0f, 133f,80f, 114f,80f),
                floatArrayOf(151f,80f, 151f,0f, 170f,0f, 214f,51f, 214f,0f, 233f,0f, 233f,80f, 214f,80f, 170f,29f, 170f,80f)
            )
            shapes.forEach { points ->
                val path = Path().apply {
                    moveTo(points[0], points[1])
                    for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
                    close()
                }
                canvas.drawPath(path, paint)
            }
            paint.color = Color.rgb(25, 45, 77)
            canvas.drawRect(0f, 37f, 233f, 43f, paint)
            canvas.restore()
            paint.color = Color.WHITE
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            paint.textSize = 20 * density
            canvas.drawText("VIEWER", 110 * density, height / 2f + 7 * density, paint)
        }
    }
}
