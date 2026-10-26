package com.lecturerec.app

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }

        fun label(t: String, hint: String) {
            box.addView(TextView(this).apply {
                text = t
                textSize = 16f
                setPadding(0, dp(16), 0, 0)
            })
            box.addView(TextView(this).apply {
                text = hint
                textSize = 12f
            })
        }

        label("Gemini API 키", "aistudio.google.com 에서 발급. 이 폰에만 저장됩니다.")
        val keyEdit = EditText(this).apply {
            setText(Prefs.apiKey(this@SettingsActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        box.addView(keyEdit)

        label(
            "전사 모델 (위에서부터 사용)",
            "한 줄에 하나. 위 모델이 바쁘면 다음 줄로 넘어가고, 전부 바쁘면 기다렸다가 다시 시도합니다."
        )
        val modelsEdit = EditText(this).apply {
            setText(Prefs.modelsText(this@SettingsActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP
        }
        box.addView(modelsEdit)

        label(
            "과목 일정",
            "한 줄에 하나: 과목,시작일,종료일 (예: 혈액종양 1차,2026-11-06,2026-11-26)\n오늘 날짜에 해당하는 과목이 기본으로 선택됩니다."
        )
        val schedEdit = EditText(this).apply {
            setText(Prefs.scheduleText(this@SettingsActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            gravity = Gravity.TOP
        }
        box.addView(schedEdit)

        val save = Button(this).apply { text = "저장" }
        save.setOnClickListener {
            Prefs.save(this, keyEdit.text.toString(), modelsEdit.text.toString(), schedEdit.text.toString())
            Toast.makeText(this, "저장했습니다", Toast.LENGTH_SHORT).show()
            finish()
        }
        box.addView(save, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(24) })

        val scroll = ScrollView(this)
        scroll.addView(box)
        setContentView(scroll)
    }
}
