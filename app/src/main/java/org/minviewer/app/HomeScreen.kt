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
import android.webkit.WebView
import android.webkit.WebViewClient
import java.text.DateFormat
import java.util.Date

class HomeScreen(
    private val activity: Activity,
    private val recent: RecentDocuments,
    private val openPicker: () -> Unit,
    private val openRecent: (RecentDocument) -> Unit
) : FrameLayout(activity) {
    private val navy = Color.rgb(25, 45, 77)
    private val dark = ThemePreferences.isDark(activity)
    private val primaryTextColor = if (dark) Color.rgb(230, 234, 240) else navy
    private val secondary = if (dark) Color.rgb(180, 190, 203) else Color.rgb(95, 105, 119)
    private val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val entries = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    init {
        setBackgroundColor(if (dark) Color.rgb(25, 29, 36) else Color.rgb(249, 248, 246))
        setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        addView(body, LayoutParams(-1, -1))
        val logoBar = FrameLayout(activity)
        logoBar.addView(LogoView(activity), LayoutParams(-1, -1))
        body.addView(logoBar, LinearLayout.LayoutParams(-1, dp(76)))
        val centered = FrameLayout(activity)
        body.addView(centered, LinearLayout.LayoutParams(-1, 0, 1f))
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), 0)
        }
        centered.addView(column, LayoutParams(-1, -1, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        val headerTitle = TextView(activity).apply {
            text = "최근 문서"
            textSize = 20f
            setTextColor(primaryTextColor)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        var compactWindow: Boolean? = null
        centered.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val width = minOf(dp(760), view.width)
            if (width > 0 && column.layoutParams.width != width) {
                column.layoutParams = (column.layoutParams as LayoutParams).apply { this.width = width }
            }
            if (view.width > 0) {
                val compact = view.width < dp(240)
                if (compactWindow != compact) {
                    compactWindow = compact
                    val margin = if (compact) 12 else 20
                    column.setPadding(dp(margin), dp(20), dp(margin), 0)
                    headerTitle.textSize = if (compact) 16f else 20f
                }
            }
        }
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(headerTitle, LinearLayout.LayoutParams(0, -2, 1f))
        logoBar.addView(TextView(activity).apply {
            text = "⋮"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "화면 설정"
            setOnClickListener {
                AlertDialog.Builder(activity).setTitle("설정").setItems(arrayOf("화면 테마", "글꼴 관리", "최근 문서 전체 삭제", "오픈소스 라이선스")) { _, index ->
                    if (index == 0) {
                        AlertDialog.Builder(activity).setTitle("화면 테마")
                            .setSingleChoiceItems(arrayOf("시스템 설정", "라이트", "다크"), ThemePreferences.selected(activity)) { dialog, selected ->
                                dialog.dismiss()
                                ThemePreferences.select(activity, selected)
                            }.setNegativeButton("취소", null).show()
                    } else if (index == 1) activity.startActivity(android.content.Intent(activity, FontsActivity::class.java))
                    else if (index == 2) confirmClear()
                    else showLicenses()
                }.show()
            }
        }, LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
        header.addView(TextView(activity).apply {
            text = "+"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "파일 열기"
            background = rounded(navy, 24f)
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
        if (items.isEmpty()) {
            entries.addView(TextView(activity).apply {
                text = "최근에 연 문서가 없습니다\n\n+ 버튼을 눌러 문서를 열어 보세요"
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(secondary)
            }, LinearLayout.LayoutParams(-1, dp(240)))
            return
        }
        items.forEach { item ->
            val row = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(4), dp(8))
                minimumHeight = dp(76)
                background = rounded(if (dark) Color.rgb(37, 46, 56) else Color.WHITE, 12f)
                setOnClickListener { openRecent(item) }
            }
            val extension = item.name.substringAfterLast('.', "FILE").uppercase().take(4)
            row.addView(TextView(activity).apply {
                text = extension
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                val color = when (extension) {
                    "PDF" -> Color.rgb(211, 52, 61)
                    "HWP", "HWPX", "DOC", "DOCX" -> Color.rgb(48, 105, 198)
                    "XLS", "XLSX", "CSV" -> Color.rgb(31, 130, 93)
                    "PPT", "PPTX" -> Color.rgb(226, 111, 40)
                    else -> navy
                }
                background = rounded(color, 6f)
            }, LinearLayout.LayoutParams(dp(46), dp(46)))
            val details = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(8), 0)
            }
            details.addView(TextView(activity).apply {
                text = item.name
                textSize = 16f
                setTextColor(primaryTextColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            details.addView(TextView(activity).apply {
                text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.openedAt))
                textSize = 12f
                setTextColor(secondary)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(activity).apply {
                text = "⋮"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(primaryTextColor)
                contentDescription = "${item.name} 목록에서 삭제"
                setOnClickListener {
                    AlertDialog.Builder(activity).setMessage("최근 문서 목록에서 삭제할까요? 원본 파일은 삭제되지 않습니다.")
                        .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ -> recent.remove(item.uri); refresh() }.show()
                }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            entries.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(activity).setMessage("최근 문서 목록을 모두 지울까요? 원본 파일은 삭제되지 않습니다.")
            .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ -> recent.clear(); refresh() }.show()
    }

    private fun showLicenses() {
        val labels = arrayOf("출처 및 소스 안내", "LibreOffice 및 제3자 고지", "rhwp 라이선스", "rhwp 의존성 고지", "수식 문자 대응표 고지", "libexttextcat 라이선스", "rhwp 전이 의존성 고지", "OpenSymbol 글꼴 고지", "기타 글꼴 라이선스", "Redland 계열 고지", "네이티브 구성 요소 고지", "한글 수식 글꼴 고지")
        AlertDialog.Builder(activity).setTitle("오픈소스 라이선스").setItems(labels) { _, index ->
            if (index == 0) {
                AlertDialog.Builder(activity).setTitle(labels[index]).setMessage(
                    "LibreOffice 26.2.6.3: MPL 2.0 및 포함 구성 요소별 라이선스\n" +
                        "원본: https://github.com/LibreOffice/core/tree/libreoffice-26.2.6.3\n" +
                        "수정 사항 및 빌드 안내: https://github.com/ghminx/MIN-VIEWER\n\n" +
                        "rhwp 0.9.0: MIT, https://github.com/edwardkim/rhwp\n" +
                        "수식 문자 대응표: kordoc, MIT\n" +
                        "Kotlin 표준 라이브러리 및 JetBrains annotations: Apache License 2.0\n\n" +
                        "LibreOffice 상세 고지에는 포함된 제3자 구성 요소와 글꼴 고지가 포함되어 있습니다."
                ).setPositiveButton("닫기", null).show()
            } else {
                val path = when (index) {
                    1 -> "licenses/libreoffice/THIRD_PARTY.html"
                    2 -> "rhwp/LICENSE"
                    3 -> "rhwp/notices/THIRD_PARTY_LICENSES.md"
                    4 -> "licenses/kordoc.txt"
                    5 -> "licenses/libreoffice/libexttextcat.txt"
                    6 -> "licenses/rhwp-transitive-notices.txt"
                    7 -> "licenses/libreoffice/opensymbol.txt"
                    8 -> "licenses/libreoffice/font-notices.txt"
                    9 -> "licenses/libreoffice/redland-notice.txt"
                    10 -> "licenses/libreoffice/native-source-notices.txt"
                    else -> "licenses/rhwp-font-notices.txt"
                }
                val web = WebView(activity).apply {
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    settings.defaultTextEncodingName = "UTF-8"
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) = true
                    }
                    if (index == 6 || index == 10) {
                        loadUrl("file:///android_asset/$path")
                    } else {
                        val text = activity.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val page = if (index == 1) text else "<html><meta charset=\"utf-8\"><body><pre style=\"white-space:pre-wrap\">" +
                            android.text.TextUtils.htmlEncode(text) + "</pre></body></html>"
                        loadDataWithBaseURL(null, page, "text/html", "UTF-8", null)
                    }
                }
                AlertDialog.Builder(activity).setTitle(labels[index]).setView(web).setPositiveButton("닫기", null).create().apply {
                    setOnDismissListener { web.destroy() }
                    show()
                }
            }
        }.setNegativeButton("닫기", null).show()
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
