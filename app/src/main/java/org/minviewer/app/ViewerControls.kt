package org.minviewer.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView

class ViewerControls(
    activity: Activity,
    title: String,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onList: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    menuItems: List<Pair<String, () -> Unit>>
) : LinearLayout(activity) {
    val status: TextView
    val listButton: TextView
    private val titleView: TextView
    private val bottom: LinearLayout
    private val notice: TextView
    private var documentNotice = ""
    private val navy = Color.rgb(25, 45, 77)

    init {
        orientation = VERTICAL
        val top = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(navy) }
        top.addView(action("‹", "뒤로", onBack))
        titleView = TextView(activity).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(titleView, LayoutParams(0, dp(48), 1f))
        top.addView(action("검색", "검색", onSearch))
        if (menuItems.isNotEmpty()) top.addView(action("⋮", "더보기") {
            val anchor = top.getChildAt(top.childCount - 1)
            PopupMenu(activity, anchor).apply {
                menuItems.forEachIndexed { index, item -> menu.add(0, index, index, item.first) }
                setOnMenuItemClickListener { item -> menuItems[item.itemId].second(); true }
                show()
            }
        })
        addView(top)
        notice = TextView(activity).apply {
            visibility = View.GONE
            textSize = 14f
            setTextColor(navy)
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        addView(notice)
        bottom = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(navy) }
        listButton = action("목록", "목록", onList)
        bottom.addView(listButton)
        bottom.addView(action("‹", "이전", onPrevious))
        status = TextView(activity).apply {
            text = "불러오는 중"
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
        }
        status.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val message = s?.toString().orEmpty()
                val show = message.contains("오류") || message.contains("실패") || message.contains("없습니다") || message.contains("권한")
                notice.text = if (show) message else documentNotice
                notice.visibility = if (show || documentNotice.isNotEmpty()) View.VISIBLE else View.GONE
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        bottom.addView(status, LayoutParams(0, dp(48), 1f))
        bottom.addView(action("›", "다음", onNext))
        bottom.addView(action("−", "축소", onZoomOut))
        bottom.addView(action("+", "확대", onZoomIn))
        addView(bottom)
    }

    fun setTitle(title: String) { titleView.text = title }

    fun setDocumentNotice(message: String) {
        documentNotice = message
        notice.text = message
        notice.visibility = if (message.isNotEmpty()) View.VISIBLE else View.GONE
    }

    fun takeBottom(): LinearLayout {
        removeView(bottom)
        return bottom
    }

    private fun action(label: String, description: String, click: () -> Unit) = TextView(context).apply {
        text = label
        contentDescription = description
        textSize = if (label.length > 1) 13f else 23f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        setOnClickListener { click() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
