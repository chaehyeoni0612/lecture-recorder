package com.lecturerec.app

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.color.MaterialColors

/** 글자 크기는 세 단계만 씀: 제목 16, 본문 14, 보조 12 */
object Ui {
    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun color(v: View, attr: Int) = MaterialColors.getColor(v, attr)

    fun title(c: Context, t: String) = TextView(c).apply {
        text = t
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
    }

    fun body(c: Context, t: String) = TextView(c).apply {
        text = t
        textSize = 14f
    }

    fun sub(c: Context, t: String) = TextView(c).apply {
        text = t
        textSize = 12f
        post { setTextColor(color(this, com.google.android.material.R.attr.colorOnSurfaceVariant)) }
    }

    fun divider(c: Context) = View(c).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        post { setBackgroundColor(color(this, com.google.android.material.R.attr.colorOutlineVariant)) }
    }

    /** 구역 이름: 작은 굵은 글씨 + 아래 여백 */
    fun section(c: Context, t: String) = TextView(c).apply {
        text = t
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(c, 20), 0, dp(c, 6))
        post { setTextColor(color(this, com.google.android.material.R.attr.colorOnSurfaceVariant)) }
    }
}
